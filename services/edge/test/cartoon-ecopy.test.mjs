import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {ApiError} from '../supabase/functions/_shared/core/facade-r2/src/common.mjs';
import {renderInstructEditPrompt} from '../supabase/functions/_shared/cartoon-ecopy/prompt.mjs';
import {
  createCartoonECopyService, createCartoonImageProvider, defaultCartoonJudge, handleCartoonECopy,
  memoryCartoonStorage, memoryCartoonStore, readCartoonProviderConfig, runCartoonFallback,
} from '../supabase/functions/_shared/cartoon-ecopy/service.mjs';

globalThis.fetch = () => { throw new Error('external_network_forbidden'); };
const spec = JSON.parse(readFileSync(new URL('../../../docs/design/prompts/cartoon-ecopy/prompt-pack/spec.json', import.meta.url), 'utf8'));
const USER = '11111111-1111-4111-8111-111111111111';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const OTHER = Buffer.from(PNG);
OTHER[20] = 9;

function service(extra = {}) {
  const calls = [];
  const provider = extra.provider ?? {
    configured: extra.configured !== false,
    model: 'test-model',
    async edit(input) {
      calls.push(input);
      if (extra.fail) throw new Error('provider down');
      return {bytes: OTHER, mime: 'image/png'};
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
    provider, store: rows, storage: files, judge: extra.judge, now: () => '2026-11-09T00:00:00.000Z',
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

test('delete keeps the row marked deleting until both objects are gone', async () => {
  const {api, rows, files} = service();
  const created = await api.create('Bearer test', body());
  let attempts = 0;
  const remove = files.remove.bind(files);
  files.remove = async path => { attempts += 1; if (attempts === 1) throw new Error('storage blip'); await remove(path); };
  await assert.rejects(api.remove('Bearer test', created.artwork.id));
  assert.equal(rows.rows.get(created.artwork.id).status, 'deleting');
  assert.equal((await api.list('Bearer test')).artworks.length, 0);
  await api.remove('Bearer test', created.artwork.id);
  assert.equal(rows.rows.size, 0);
  assert.equal(files.objects.size, 0);
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
