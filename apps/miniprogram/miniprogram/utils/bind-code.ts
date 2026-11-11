/** Draft format from the #157 proposal: MIMI-XXXX-XXXX, letters and digits. Final format waits on the user. */
const BIND_CODE = /^MIMI-[A-Z0-9]{4}-[A-Z0-9]{4}$/;

export function normalizeBindCode(raw: string) {
  return raw.replace(/\s+/g, "").toUpperCase();
}

export function isBindCodeFormat(raw: string) {
  return BIND_CODE.test(normalizeBindCode(raw));
}
