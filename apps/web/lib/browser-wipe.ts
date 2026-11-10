export async function wipeBrowserState() {
  const drop = (storage: Storage) => {
    const keys: string[] = [];
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (key?.startsWith("mimi.web.")) keys.push(key);
    }
    for (const key of keys) storage.removeItem(key);
  };
  drop(window.localStorage);
  drop(window.sessionStorage);
  if (typeof caches !== "undefined") {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => name.startsWith("mimi.web.")).map((name) => caches.delete(name)));
  }
  const known = ["mimi.web.vault.v1"];
  const listed = indexedDB.databases ? await indexedDB.databases() : [];
  for (const db of listed) if (db.name) known.push(db.name);
  await Promise.all([...new Set(known)].filter((name) => name.startsWith("mimi.web.")).map((name) => new Promise<void>((resolve) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve();
    request.onerror = () => resolve();
    request.onblocked = () => resolve();
  })));
}
