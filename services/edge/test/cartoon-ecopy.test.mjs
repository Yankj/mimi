import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {deflateSync} from 'node:zlib';
import {ApiError} from '../supabase/functions/_shared/core/facade-r2/src/common.mjs';
import {cartoonStructureAccepts, decodeCartoonGray} from '../supabase/functions/_shared/cartoon-ecopy/image-decode.mjs';
import {renderInstructEditPrompt} from '../supabase/functions/_shared/cartoon-ecopy/prompt.mjs';
import {
  CARTOON_ATTEMPT_TIMEOUT_MS, CARTOON_CHAIN_BUDGET_MS,
  createCartoonECopyService, createCartoonImageProvider, defaultCartoonJudge, handleCartoonECopy,
  memoryCartoonStorage, memoryCartoonStore, readCartoonProviderConfig, runCartoonFallback,
} from '../supabase/functions/_shared/cartoon-ecopy/service.mjs';

globalThis.fetch = () => { throw new Error('external_network_forbidden'); };
const spec = JSON.parse(readFileSync(new URL('../../../docs/design/prompts/cartoon-ecopy/prompt-pack/spec.json', import.meta.url), 'utf8'));
const USER = '11111111-1111-4111-8111-111111111111';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

function service(extra = {}) {
  const calls = [];
  const provider = extra.provider ?? {
    configured: extra.configured !== false,
    model: 'test-model',
    async edit(input) {
      calls.push(input);
      if (extra.fail) throw new Error('provider down');
      if (extra.output) return extra.output(input);
      return {bytes: Buffer.from(input.image), mime: input.mime};
    },
  };
  const rows = memoryCartoonStore();
  const files = memoryCartoonStorage();
  let n = 0;
  const api = createCartoonECopyService({
    spec, verifyUser: async authorization => {
      if (authorization !== 'Bearer test') throw new ApiError(401, 'session_required');
      return {sub: USER};
    },
    provider, store: rows, storage: files, judge: extra.judge, env: extra.env, fetchImpl: extra.fetchImpl,
    budgetMs: extra.budgetMs, attemptMs: extra.attemptMs,
    now: () => '2026-11-09T00:00:00.000Z',
    newId: () => `22222222-2222-4222-8222-${String(++n).padStart(12, '0')}`,
  });
  return {api, calls, rows, files};
}
const body = (extra = {}) => ({image_base64: PNG.toString('base64'), mime_type: 'image/png', ...extra});

test('prompt uses spec defaults and does not carry identity or the handwriting example', () => {
  const text = renderInstructEditPrompt(spec, spec.defaults);
  assert.match(text, /richer storybook finish, same content/);
  assert.match(text, /cute warm picture-book illustration/);
  assert.match(text, /Do not add cheek blush/);
  assert.doesNotMatch(text, /You may add only/);
  assert.doesNotMatch(text, /妈妈 我爱你/);
  assert.equal(spec.defaults.preset, 'storybook');
  assert.equal(spec.defaults.style_preset, 'storybook_handdrawn');
  assert.equal(spec.defaults.decoration_level, 'off');
  const sparkle = renderInstructEditPrompt(spec, {...spec.defaults, decoration_level: 'sparkle', style_preset: 'cartoon'});
  assert.match(sparkle, /You may add only: cheek blush, eye highlights, soft glow\/twinkle/);
  assert.match(sparkle, /cute cartoon illustration/);
});

test('missing provider config is not_configured and never stores a pair', async () => {
  assert.equal(readCartoonProviderConfig(() => undefined).configured, false);
  assert.equal(readCartoonProviderConfig(name => ({CARTOON_ECOPY_PROVIDER: 'generic', CARTOON_ECOPY_API_KEY: 'example-key-value', CARTOON_ECOPY_MODEL: 'm'}[name])).configured, false);
  const {api, rows, files} = service({configured: false});
  const response = await handleCartoonECopy(api, new Request('https://example.invalid/functions/v1/cartoon-ecopy/artworks', {
    method: 'POST', headers: {authorization: 'Bearer test', 'content-type': 'application/json'}, body: JSON.stringify(body()),
  }));
  assert.equal(response.status, 503);
  const payload = await response.json();
  assert.equal(payload.error.code, 'not_configured');
  assert.match(payload.error.message, /CARTOON_ECOPY_API_KEY/);
  assert.doesNotMatch(payload.error.message, /secret/);
  assert.equal(rows.rows.size, 0);
  assert.equal(files.objects.size, 0);
});

test('identity fields are rejected before the model sees them', async () => {
  const {api, calls} = service();
  const response = await handleCartoonECopy(api, new Request('https://example.invalid/cartoon-ecopy/artworks', {
    method: 'POST', headers: {authorization: 'Bearer test'}, body: JSON.stringify(body({child_name: '不应发送'})),
  }));
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error.code, 'privacy_field');
  assert.equal(calls.length, 0);
});

test('a ready pair stores both files and delete removes them together', async () => {
  const {api, calls, rows, files} = service();
  const created = await api.create('Bearer test', body());
  assert.equal(created.artwork.status, 'ready');
  assert.equal(created.artwork.preset, 'storybook');
  assert.equal(created.artwork.model, 'test-model');
  assert.equal(created.artwork.spec_version, spec.version);
  assert.equal(files.objects.size, 2);
  assert.ok([...files.objects.keys()].every(path => path.startsWith(`${USER}/`)));
  assert.doesNotMatch(created.artwork.original_url, /\/object\/public\//);
  assert.doesNotMatch(created.artwork.output_url, /\/object\/public\//);
  assert.doesNotMatch(calls[0].prompt, /不应发送|妈妈 我爱你/);
  const listed = await api.list('Bearer test');
  assert.equal(listed.artworks.length, 1);
  const removed = await api.remove('Bearer test', created.artwork.id);
  assert.equal(removed.deleted, true);
  assert.equal(rows.rows.size, 0);
  assert.equal(files.objects.size, 0);
  await assert.rejects(api.remove('Bearer test', created.artwork.id), error => error.code === 'not_found');
});

test('acceptance chain retries light once, then keeps the cropped original', async () => {
  let seen = 0;
  const judge = async () => ++seen === 2;
  const {api, calls} = service({judge});
  const created = await api.create('Bearer test', body());
  assert.deepEqual(calls.map(call => call.prompt.includes('clean up and add texture')), [false, true]);
  assert.equal(created.artwork.status, 'ready');
  assert.equal(created.artwork.preset, 'light');
  const failing = service({fail: true});
  const fallback = await failing.api.create('Bearer test', body({preset: 'light'}));
  assert.equal(fallback.artwork.status, 'fallback_crop');
  assert.equal(fallback.artwork.model, null);
  const paths = [...failing.files.objects.keys()];
  assert.equal(paths.length, 2);
  assert.deepEqual(failing.files.objects.get(paths[0]), failing.files.objects.get(paths[1]));
  const chain = await runCartoonFallback({
    requestedPreset: 'storybook', original: {bytes: PNG, mime: 'image/png'},
    edit: async () => { throw new Error('down'); }, judge: async () => false,
  });
  assert.equal(chain.status, 'fallback_crop');
  assert.equal(defaultCartoonJudge({bytes: PNG}), true);
  assert.equal(defaultCartoonJudge({bytes: Buffer.from('not an image')}), false);
});

test('storage failure does not leave a half pair', async () => {
  const {api, rows, files} = service();
  files.put = async () => { throw new ApiError(503, 'storage_unavailable'); };
  await assert.rejects(api.create('Bearer test', body()), error => error.code === 'storage_unavailable');
  assert.equal(rows.rows.size, 0);
  assert.equal(files.objects.size, 0);
});

test('generic provider sends only the prompt and the image', async () => {
  let seen;
  const provider = createCartoonImageProvider({
    env: name => ({
      CARTOON_ECOPY_PROVIDER: 'generic', CARTOON_ECOPY_API_KEY: 'example-not-a-real-key',
      CARTOON_ECOPY_MODEL: 'edit-test', CARTOON_ECOPY_BASE_URL: 'https://model.example.invalid/edit',
    }[name]),
    fetchImpl: async (url, init) => {
      seen = {url, auth: init.headers.Authorization, body: JSON.parse(init.body)};
      return Response.json({image_base64: PNG.toString('base64'), mime_type: 'image/png'});
    },
  });
  const image = await provider.edit({prompt: 'same drawing', image: PNG, mime: 'image/png'});
  assert.equal(image.mime, 'image/png');
  assert.equal(seen.url, 'https://model.example.invalid/edit');
  assert.equal(seen.auth, 'Bearer example-not-a-real-key');
  assert.deepEqual(Object.keys(seen.body).sort(), ['image_base64', 'mime_type', 'model', 'prompt']);
  assert.equal(seen.body.prompt, 'same drawing');
});

test('list finishes a deleting pair after storage delete fails halfway', async () => {
  const {api, rows, files} = service();
  const created = await api.create('Bearer test', body());
  const id = created.artwork.id;
  let outputFailures = 0;
  const remove = files.remove.bind(files);
  files.remove = async path => {
    if (path.includes('/output.') && outputFailures < 2) {
      outputFailures += 1;
      throw new Error('storage blip');
    }
    await remove(path);
  };
  await assert.rejects(api.remove('Bearer test', id));
  assert.equal(rows.rows.get(id).status, 'deleting');
  assert.equal([...files.objects.keys()].filter(path => path.endsWith('output.png')).length, 1);
  const hidden = await api.list('Bearer test');
  assert.equal(hidden.artworks.length, 0);
  assert.equal(rows.rows.get(id).status, 'deleting');
  assert.equal(files.objects.has([...files.objects.keys()][0]), true);
  const recovered = await api.list('Bearer test');
  assert.equal(recovered.artworks.length, 0);
  assert.equal(rows.rows.size, 0);
  assert.equal(files.objects.size, 0);
});

function crc32(bytes) {
  let crc = ~0;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (~crc) >>> 0;
}

function pngChunk(type, data) {
  const body = Buffer.concat([Buffer.from(type), data]);
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  body.copy(out, 4);
  out.writeUInt32BE(crc32(body), 8 + data.length);
  return out;
}

function encodePNG(width, height, paint) {
  const raw = Buffer.alloc(height * (1 + width * 4));
  for (let y = 0; y < height; y++) {
    const row = y * (1 + width * 4);
    raw[row] = 0;
    for (let x = 0; x < width; x++) {
      const [r, g, b] = paint(x, y);
      const at = row + 1 + x * 4;
      raw[at] = r; raw[at + 1] = g; raw[at + 2] = b; raw[at + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

const drawing = encodePNG(32, 32, (x, y) => y === 16 ? [0, 0, 0] : [255, 255, 255]);
const drawingWithBlob = encodePNG(32, 32, (x, y) => (y === 16 || (x >= 2 && x < 10 && y >= 2 && y < 10)) ? [0, 0, 0] : [255, 255, 255]);

test('the line check rejects an added object and the chain falls back to the crop', async () => {
  assert.equal(cartoonStructureAccepts(drawing, drawing), true);
  assert.equal(cartoonStructureAccepts(drawing, drawingWithBlob), false);
  const {api, calls, files} = service({output: () => ({bytes: drawingWithBlob, mime: 'image/png'})});
  const created = await api.create('Bearer test', {image_base64: drawing.toString('base64'), mime_type: 'image/png'});
  assert.equal(calls.length, 2);
  assert.equal(calls[1].prompt.includes('clean up and add texture'), true);
  assert.equal(created.artwork.status, 'fallback_crop');
  assert.equal(created.artwork.model, null);
  const paths = [...files.objects.keys()];
  assert.equal(paths.length, 2);
  assert.deepEqual(files.objects.get(paths[0]), files.objects.get(paths[1]));
});

test('CARTOON_ECOPY_JUDGE_URL decides the attempt, and a dead judge uses the line check', async () => {
  const env = name => ({CARTOON_ECOPY_JUDGE_URL: 'https://judge.example.invalid/check', CARTOON_ECOPY_JUDGE_API_KEY: 'example-judge-key'}[name]);
  const passes = [false, true];
  let seenKey;
  const {api, calls} = service({
    env,
    fetchImpl: async (_url, init) => {
      seenKey = init.headers.Authorization;
      return Response.json({pass: passes.shift()});
    },
  });
  const created = await api.create('Bearer test', body());
  assert.equal(seenKey, 'Bearer example-judge-key');
  assert.equal(calls.length, 2);
  assert.equal(created.artwork.preset, 'light');
  assert.equal(created.artwork.status, 'ready');
  const refused = service({env, fetchImpl: async () => Response.json({pass: false})});
  const fallback = await refused.api.create('Bearer test', body());
  assert.equal(fallback.artwork.status, 'fallback_crop');
  const dead = service({env, fetchImpl: async () => { throw new Error('judge down'); }});
  const kept = await dead.api.create('Bearer test', body());
  assert.equal(kept.artwork.status, 'ready');
  assert.equal(dead.calls.length, 1);
});

test('a slow attempt falls back to the cropped original instead of exceeding the edge budget', async () => {
  assert.ok(CARTOON_ATTEMPT_TIMEOUT_MS * 2 <= CARTOON_CHAIN_BUDGET_MS);
  assert.ok(CARTOON_CHAIN_BUDGET_MS < 150_000);
  let calls = 0;
  const original = {bytes: PNG, mime: 'image/png'};
  const chain = await runCartoonFallback({
    requestedPreset: 'storybook', original,
    edit: async () => {
      calls += 1;
      await new Promise(resolve => setTimeout(resolve, 40));
      return {bytes: PNG, mime: 'image/png'};
    },
    judge: async () => true,
    budgetMs: 50,
    attemptMs: 30,
  });
  assert.equal(calls, 1);
  assert.equal(chain.status, 'fallback_crop');
  assert.equal(chain.image, original);
  const black = decodeCartoonGray(Buffer.from('/9j/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAAIAAgBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/AP8AP/r/2Q==', 'base64'));
  const white = decodeCartoonGray(Buffer.from('/9j/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAAIAAgBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/AP7+KP/Z', 'base64'));
  assert.ok(black && black.gray.every(sample => sample < 0.05));
  assert.ok(white && white.gray.every(sample => sample > 0.95));
});

test('bundled spec matches the prompt pack', () => {
  const docs = readFileSync(new URL('../../../docs/design/prompts/cartoon-ecopy/prompt-pack/spec.json', import.meta.url));
  const bundled = readFileSync(new URL('../supabase/functions/_shared/cartoon-ecopy/spec.json', import.meta.url));
  assert.deepEqual(bundled, docs);
  const entry = readFileSync(new URL('../supabase/functions/cartoon-ecopy/index.ts', import.meta.url), 'utf8');
  assert.match(entry, /_shared\/cartoon-ecopy\/spec\.json/);
  assert.doesNotMatch(entry, /docs\/design/);
});

test('migration keeps the bucket private and scopes rows to the owner', () => {
  const sql = readFileSync(new URL('../supabase/migrations/20261109120000_cartoon_ecopy.sql', import.meta.url), 'utf8');
  assert.match(sql, /'cartoon-ecopies', 'cartoon-ecopies', false/);
  assert.match(sql, /enable row level security/);
  assert.match(sql, /force row level security/);
  assert.match(sql, /\(select auth\.uid\(\)\) = user_id/);
  assert.match(sql, /storage\.foldername\(name\)\)\[1\] = \(select auth\.uid\(\)\)::text/);
  assert.doesNotMatch(sql, /to anon|for all to public|public = true/i);
});
