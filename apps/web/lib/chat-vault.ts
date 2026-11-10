export const VAULT_DB_NAME = "mimi.web.vault.v1";

export async function createChatKey() {
  return crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

export async function sealJson(key: CryptoKey, value: unknown) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = new TextEncoder().encode(JSON.stringify(value));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plaintext));
  return { iv, ciphertext };
}

export async function openJson(key: CryptoKey, iv: BufferSource, ciphertext: BufferSource) {
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ciphertext);
  return JSON.parse(new TextDecoder().decode(plain)) as unknown;
}

function openVault() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(VAULT_DB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("keys")) db.createObjectStore("keys");
      if (!db.objectStoreNames.contains("seals")) db.createObjectStore("seals");
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function idbGet<T>(db: IDBDatabase, store: string, key: string) {
  return new Promise<T | undefined>((resolve, reject) => {
    const request = db.transaction(store, "readonly").objectStore(store).get(key);
    request.onsuccess = () => resolve(request.result as T | undefined);
    request.onerror = () => reject(request.error);
  });
}

function idbPut(db: IDBDatabase, store: string, key: string, value: unknown) {
  return new Promise<void>((resolve, reject) => {
    const request = db.transaction(store, "readwrite").objectStore(store).put(value, key);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
  });
}

async function withVault<T>(fn: (db: IDBDatabase) => Promise<T>) {
  const db = await openVault();
  try {
    return await fn(db);
  } finally {
    db.close();
  }
}

async function loadOrCreateKey(userId: string) {
  return withVault(async (db) => {
    const existing = await idbGet<CryptoKey>(db, "keys", userId);
    if (existing) return existing;
    const created = await createChatKey();
    await idbPut(db, "keys", userId, created);
    return created;
  });
}

export async function saveVault(userId: string, value: unknown) {
  const key = await loadOrCreateKey(userId);
  const sealed = await sealJson(key, value);
  await withVault((db) => idbPut(db, "seals", userId, { iv: sealed.iv, ciphertext: sealed.ciphertext }));
}

export async function loadVault(userId: string) {
  const sealed = await withVault((db) => idbGet<{ iv: BufferSource; ciphertext: BufferSource }>(db, "seals", userId));
  if (!sealed?.iv || !sealed.ciphertext) return null;
  const key = await withVault((db) => idbGet<CryptoKey>(db, "keys", userId));
  if (!key) return null;
  try {
    return await openJson(key, sealed.iv, sealed.ciphertext);
  } catch {
    return null;
  }
}
