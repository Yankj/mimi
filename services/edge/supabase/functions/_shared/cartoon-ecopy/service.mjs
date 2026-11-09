import {Buffer} from 'node:buffer';
import {ApiError,check,UUID} from '../core/facade-r2/src/common.mjs';
import {assertCartoonRequest,cartoonChoices,renderInstructEditPrompt} from './prompt.mjs';

export const CARTOON_BUCKET = 'cartoon-ecopies';
const MAX_IMAGE = 5 * 1024 * 1024;
const MESSAGES = {
  not_configured: '卡通电子稿图像模型未配置。请设置 CARTOON_ECOPY_PROVIDER、CARTOON_ECOPY_API_KEY 和 CARTOON_ECOPY_MODEL。',
  privacy_field: '请求里不能包含孩子姓名、年龄、地点或家人姓名。',
  session_required: '需要登录。',
  invalid_session: '登录已失效。',
  storage_not_configured: '卡通电子稿存储未配置。',
  storage_unavailable: '存储暂时不可用。',
  image_too_large: '图片太大。',
  image_mime_unsupported: '只接受 JPEG 或 PNG。',
  schema_invalid: '请求格式不正确。',
  not_found: '没有这组成对画稿。',
  method_not_allowed: '不支持这个操作。',
  auth_key_service_unavailable: '登录校验暂时不可用。',
};

export function readCartoonProviderConfig(env) {
  const provider = (env('CARTOON_ECOPY_PROVIDER') ?? '').trim().toLowerCase();
  const apiKey = (env('CARTOON_ECOPY_API_KEY') ?? '').trim();
  const model = (env('CARTOON_ECOPY_MODEL') ?? '').trim();
  const baseURL = (env('CARTOON_ECOPY_BASE_URL') ?? '').trim();
  const allowed = provider === 'openai' || provider === 'gemini' || provider === 'generic';
  const baseOk = !baseURL || baseURL.startsWith('https://');
  if (!allowed || !apiKey || !model || !baseOk || (provider === 'generic' && !baseURL.startsWith('https://'))) {
    return {configured: false};
  }
  return {configured: true, provider, apiKey, model, baseURL};
}

// acceptance.md items 1–6 and 8–9 (counts, new objects, SSIM, line quality, expression,
// animation, handwriting, cropped edges) are not computed here.
// TODO: when CARTOON_ECOPY_JUDGE_URL is unset, a passing provider image is accepted on
// magic bytes only. The retry-light-then-crop chain still runs for provider errors and
// for a judge that returns pass:false.
export function defaultCartoonJudge(image) {
  const bytes = image?.bytes;
  if (!bytes || bytes.length < 8 || bytes.length > 8 * 1024 * 1024) return false;
  const png = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  const jpg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  return png || jpg;
}

export async function runCartoonFallback({requestedPreset, original, edit, judge}) {
  const attempts = requestedPreset === 'light' ? ['light', 'light'] : [requestedPreset, 'light'];
  for (const preset of attempts) {
    try {
      const image = await edit(preset);
      if (await judge(image)) return {status: 'ready', preset, image};
    } catch { /* try the next step of the chain */ }
  }
  return {status: 'fallback_crop', preset: requestedPreset, image: original};
}

export function createCartoonImageProvider({env, fetchImpl = globalThis.fetch}) {
  const cfg = readCartoonProviderConfig(env);
  return {
    configured: cfg.configured,
    model: cfg.model ?? null,
    async edit({prompt, image, mime}) {
      if (!cfg.configured) throw new ApiError(503, 'not_configured');
      if (cfg.provider === 'openai') return editOpenAI(cfg, prompt, image, mime, fetchImpl);
      if (cfg.provider === 'gemini') return editGemini(cfg, prompt, image, mime, fetchImpl);
      return editGeneric(cfg, prompt, image, mime, fetchImpl);
    },
  };
}

function ext(mime) { return mime === 'image/png' ? 'png' : 'jpg'; }

function decodeImage(body) {
  check(body.mime_type === 'image/jpeg' || body.mime_type === 'image/png', 422, 'image_mime_unsupported');
  const bytes = Buffer.from(body.image_base64, 'base64');
  check(bytes.length > 32 && bytes.length <= MAX_IMAGE, 413, 'image_too_large');
  const png = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  const jpg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  check((body.mime_type === 'image/png' && png) || (body.mime_type === 'image/jpeg' && jpg), 422, 'image_mime_unsupported');
  return {bytes, mime: body.mime_type};
}

async function readImageResponse(response) {
  check(response.ok, 502, 'provider_failed');
  const payload = await response.json();
  const raw = payload?.image_base64 ?? payload?.data?.[0]?.b64_json ?? payload?.candidates?.[0]?.content?.parts?.find(p => p.inlineData || p.inline_data)?.inlineData?.data ?? payload?.candidates?.[0]?.content?.parts?.find(p => p.inline_data)?.inline_data?.data;
  check(typeof raw === 'string', 502, 'provider_failed');
  const bytes = Buffer.from(raw, 'base64');
  const mime = payload?.mime_type || (bytes[0] === 0x89 ? 'image/png' : 'image/jpeg');
  check(defaultCartoonJudge({bytes}), 502, 'provider_failed');
  return {bytes, mime};
}

async function editGeneric(cfg, prompt, image, mime, fetchImpl) {
  const response = await fetchImpl(cfg.baseURL, {
    method: 'POST',
    headers: {Authorization: `Bearer ${cfg.apiKey}`, 'Content-Type': 'application/json'},
    body: JSON.stringify({prompt, image_base64: Buffer.from(image).toString('base64'), mime_type: mime, model: cfg.model}),
  });
  return readImageResponse(response);
}

async function editOpenAI(cfg, prompt, image, mime, fetchImpl) {
  const endpoint = cfg.baseURL || 'https://api.openai.com/v1/images/edits';
  const boundary = `cartoon-${crypto.randomUUID()}`;
  const filename = mime === 'image/png' ? 'drawing.png' : 'drawing.jpg';
  const head = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="model"\r\n\r\n${cfg.model}\r\n--${boundary}\r\nContent-Disposition: form-data; name="prompt"\r\n\r\n${prompt}\r\n--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="${filename}"\r\nContent-Type: ${mime}\r\n\r\n`);
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
  const body = Buffer.concat([head, Buffer.from(image), tail]);
  const response = await fetchImpl(endpoint, {method: 'POST', headers: {Authorization: `Bearer ${cfg.apiKey}`, 'Content-Type': `multipart/form-data; boundary=${boundary}`}, body});
  return readImageResponse(response);
}

async function editGemini(cfg, prompt, image, mime, fetchImpl) {
  const endpoint = cfg.baseURL || `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(cfg.model)}:generateContent`;
  const response = await fetchImpl(endpoint, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'x-goog-api-key': cfg.apiKey},
    body: JSON.stringify({
      contents: [{role: 'user', parts: [{text: prompt}, {inline_data: {mime_type: mime, data: Buffer.from(image).toString('base64')}}]}],
      generationConfig: {responseModalities: ['TEXT', 'IMAGE']},
    }),
  });
  return readImageResponse(response);
}

export function createCartoonStorage({url, serviceKey, fetchImpl = globalThis.fetch, bucket = CARTOON_BUCKET}) {
  check(typeof url === 'string' && url.startsWith('https://') && typeof serviceKey === 'string' && serviceKey.length > 20, 503, 'storage_not_configured');
  const base = url.replace(/\/$/, '') + '/storage/v1';
  const headers = extra => ({Authorization: `Bearer ${serviceKey}`, apikey: serviceKey, ...extra});
  const enc = path => path.split('/').map(encodeURIComponent).join('/');
  return {
    bucket,
    async put(path, bytes, mime) {
      const response = await fetchImpl(`${base}/object/${bucket}/${enc(path)}`, {method: 'POST', headers: headers({'Content-Type': mime, 'x-upsert': 'true'}), body: bytes});
      await response.body?.cancel?.();
      check(response.ok, 503, 'storage_unavailable');
    },
    async remove(path) {
      const response = await fetchImpl(`${base}/object/${bucket}`, {method: 'DELETE', headers: headers({'Content-Type': 'application/json'}), body: JSON.stringify({prefixes: [path]})});
      await response.body?.cancel?.();
      check(response.ok || response.status === 404, 503, 'storage_unavailable');
    },
    async sign(path) {
      const response = await fetchImpl(`${base}/object/sign/${bucket}/${enc(path)}`, {method: 'POST', headers: headers({'Content-Type': 'application/json'}), body: JSON.stringify({expiresIn: 600})});
      check(response.ok, 503, 'storage_unavailable');
      const payload = await response.json();
      const signed = payload.signedURL || payload.signedUrl;
      check(typeof signed === 'string' && !signed.includes('/object/public/'), 503, 'storage_unavailable');
      const absolute = signed.startsWith('https://') ? signed : `${base}${signed.startsWith('/') ? '' : '/'}${signed}`;
      check(absolute.startsWith('https://') && !absolute.includes('/object/public/'), 503, 'storage_unavailable');
      return absolute;
    },
  };
}

export function memoryCartoonStore() {
  const rows = new Map();
  return {
    rows,
    async insert(row) { rows.set(row.id, {...row}); return rows.get(row.id); },
    async list(userId) { return [...rows.values()].filter(row => row.user_id === userId && row.status !== 'deleting' && row.status !== 'pending').sort((a, b) => b.created_at.localeCompare(a.created_at)); },
    async get(userId, id) { const row = rows.get(id); return row && row.user_id === userId ? row : null; },
    async update(userId, id, patch) {
      const row = rows.get(id);
      if (!row || row.user_id !== userId) return null;
      Object.assign(row, patch, {updated_at: new Date().toISOString()});
      return row;
    },
    async remove(userId, id) { const row = rows.get(id); if (!row || row.user_id !== userId) return false; rows.delete(id); return true; },
  };
}

export function memoryCartoonStorage() {
  const objects = new Map();
  return {
    objects,
    async put(path, bytes) { objects.set(path, Buffer.from(bytes)); },
    async remove(path) { objects.delete(path); },
    async sign(path) {
      check(objects.has(path), 404, 'not_found');
      return `https://storage.example.invalid/storage/v1/object/sign/cartoon-ecopies/${path}?token=synthetic`;
    },
  };
}

export function createCartoonECopyService(opts) {
  check(opts?.spec?.version && typeof opts.verifyUser === 'function', 503, 'not_configured');
  const env = opts.env ?? (() => undefined);
  const fetchImpl = opts.fetchImpl ?? globalThis.fetch;
  const provider = opts.provider ?? createCartoonImageProvider({env, fetchImpl});
  const judge = opts.judge ?? (async image => defaultCartoonJudge(image));
  let store = opts.store ?? null;
  let storage = opts.storage ?? null;
  const now = opts.now ?? (() => new Date().toISOString());
  const newId = opts.newId ?? (() => crypto.randomUUID());

  function backend() {
    if (store && storage) return {store, storage};
    store = createPostgrestCartoonStore({url: env('SUPABASE_URL'), serviceKey: env('SUPABASE_SERVICE_ROLE_KEY'), fetchImpl});
    storage = createCartoonStorage({url: env('SUPABASE_URL'), serviceKey: env('SUPABASE_SERVICE_ROLE_KEY'), fetchImpl});
    return {store, storage};
  }

  async function userId(authorization) {
    const payload = await opts.verifyUser(authorization);
    check(UUID.test(payload?.sub), 401, 'invalid_session');
    return payload.sub;
  }

  async function present(row) {
    const {storage: files} = backend();
    const artwork = {
      id: row.id, preset: row.preset, style_preset: row.style_preset, decoration_level: row.decoration_level,
      model: row.model ?? null, spec_version: row.spec_version, status: row.status, created_at: row.created_at, updated_at: row.updated_at,
      original_url: await files.sign(row.original_path), output_url: row.output_path ? await files.sign(row.output_path) : null,
    };
    check(!artwork.original_url.includes('/object/public/') && (!artwork.output_url || !artwork.output_url.includes('/object/public/')), 503, 'storage_unavailable');
    return artwork;
  }

  return {
    provider,
    async list(authorization) {
      const id = await userId(authorization);
      const {store: rows} = backend();
      const artworks = [];
      for (const row of await rows.list(id)) artworks.push(await present(row));
      return {artworks};
    },
    async read(authorization, id) {
      const owner = await userId(authorization);
      check(UUID.test(id), 422, 'schema_invalid');
      const {store: rows} = backend();
      const row = await rows.get(owner, id);
      check(row && row.status !== 'deleting' && row.status !== 'pending', 404, 'not_found');
      return {artwork: await present(row)};
    },
    async remove(authorization, id) {
      const owner = await userId(authorization);
      check(UUID.test(id), 422, 'schema_invalid');
      const {store: rows, storage: files} = backend();
      const row = await rows.get(owner, id);
      check(row, 404, 'not_found');
      await rows.update(owner, id, {status: 'deleting'});
      await files.remove(row.original_path);
      if (row.output_path) await files.remove(row.output_path);
      await rows.remove(owner, id);
      return {deleted: true, id};
    },
    async create(authorization, body) {
      const owner = await userId(authorization);
      assertCartoonRequest(body);
      if (!provider.configured) throw new ApiError(503, 'not_configured');
      const choice = cartoonChoices(opts.spec, body);
      const image = decodeImage(body);
      const {store: rows, storage: files} = backend();
      const id = newId();
      check(UUID.test(id), 500, 'schema_invalid');
      const originalPath = `${owner}/${id}/original.${ext(image.mime)}`;
      const stamp = now();
      await rows.insert({
        id, user_id: owner, original_path: originalPath, output_path: null, preset: choice.preset,
        style_preset: choice.style_preset, decoration_level: choice.decoration_level, model: null,
        spec_version: opts.spec.version, status: 'pending', created_at: stamp, updated_at: stamp,
      });
      try {
        await files.put(originalPath, image.bytes, image.mime);
        const outcome = await runCartoonFallback({
          requestedPreset: choice.preset,
          original: image,
          edit: async preset => provider.edit({prompt: renderInstructEditPrompt(opts.spec, {...choice, preset}), image: image.bytes, mime: image.mime}),
          judge,
        });
        const outputPath = `${owner}/${id}/output.${ext(outcome.image.mime)}`;
        await files.put(outputPath, outcome.image.bytes, outcome.image.mime);
        const saved = await rows.update(owner, id, {
          output_path: outputPath,
          status: outcome.status,
          preset: outcome.preset,
          model: outcome.status === 'ready' ? provider.model : null,
        });
        return {artwork: await present(saved)};
      } catch (error) {
        try { await files.remove(originalPath); } catch { /* cleanup is retried by remove() */ }
        try { await files.remove(`${owner}/${id}/output.jpg`); } catch { /* same */ }
        try { await files.remove(`${owner}/${id}/output.png`); } catch { /* same */ }
        try { await rows.remove(owner, id); } catch { await rows.update(owner, id, {status: 'deleting'}); }
        throw error;
      }
    },
  };
}

export function createPostgrestCartoonStore({url, serviceKey, fetchImpl = globalThis.fetch}) {
  check(typeof url === 'string' && url.startsWith('https://') && typeof serviceKey === 'string' && serviceKey.length > 20, 503, 'storage_not_configured');
  const base = url.replace(/\/$/, '') + '/rest/v1/cartoon_artworks';
  const headers = extra => ({apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, 'Content-Type': 'application/json', ...extra});
  async function send(method, query, body, prefer) {
    const response = await fetchImpl(`${base}${query}`, {method, headers: headers(prefer ? {Prefer: prefer} : {}), body: body === undefined ? undefined : JSON.stringify(body)});
    if (!response.ok) { await response.body?.cancel?.(); throw new ApiError(503, 'storage_unavailable'); }
    if (method === 'DELETE') { await response.body?.cancel?.(); return null; }
    return response.json();
  }
  const eq = value => encodeURIComponent(value);
  return {
    async insert(row) { return (await send('POST', '', row, 'return=representation'))[0]; },
    async list(userId) { return send('GET', `?user_id=eq.${eq(userId)}&status=not.in.(pending,deleting)&order=created_at.desc`); },
    async get(userId, id) { return (await send('GET', `?id=eq.${eq(id)}&user_id=eq.${eq(userId)}&select=*`))[0] ?? null; },
    async update(userId, id, patch) { return (await send('PATCH', `?id=eq.${eq(id)}&user_id=eq.${eq(userId)}`, {...patch, updated_at: new Date().toISOString()}, 'return=representation'))[0] ?? null; },
    async remove(userId, id) { await send('DELETE', `?id=eq.${eq(id)}&user_id=eq.${eq(userId)}`); return true; },
  };
}

export async function handleCartoonECopy(service, request) {
  try {
    const path = cartoonPath(new URL(request.url).pathname);
    const authorization = request.headers.get('authorization') ?? '';
    if (request.method === 'GET' && path === '/artworks') return json(200, await service.list(authorization));
    if (request.method === 'POST' && path === '/artworks') return json(201, await service.create(authorization, await readBody(request)));
    const match = path.match(/^\/artworks\/([0-9a-f-]{36})$/i);
    if (match && request.method === 'GET') return json(200, await service.read(authorization, match[1]));
    if (match && request.method === 'DELETE') return json(200, await service.remove(authorization, match[1]));
    return json(request.method === 'GET' || request.method === 'POST' || request.method === 'DELETE' ? 404 : 405, {error: {code: 'method_not_allowed', message: MESSAGES.method_not_allowed}});
  } catch (error) {
    if (error instanceof ApiError) {
      const message = MESSAGES[error.code] ?? '请求没有完成。';
      return json(error.status, {error: {code: error.code, message}});
    }
    return json(500, {error: {code: 'internal', message: '卡通电子稿没有完成。'}});
  }
}

function cartoonPath(pathname) {
  const marker = '/cartoon-ecopy';
  const index = pathname.indexOf(marker);
  let path = index >= 0 ? pathname.slice(index + marker.length) : pathname;
  if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);
  return path || '/';
}

async function readBody(request) {
  const text = await request.text();
  check(text.length <= 8 * 1024 * 1024, 413, 'image_too_large');
  try { return JSON.parse(text); } catch { throw new ApiError(422, 'schema_invalid'); }
}

function json(status, body) {
  return new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json; charset=utf-8'}});
}
