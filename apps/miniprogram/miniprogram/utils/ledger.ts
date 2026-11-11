/** Parenting ledger writes are out of scope until family_v1. The shell can show the add-child dialog but must not persist a child. */

export type ChildRecord = { nickname: string };

export class LedgerWriteError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LedgerWriteError";
  }
}

export function createLocalLedger() {
  const children: ChildRecord[] = [];
  return {
    list() {
      return children.slice();
    },
    addChild(_nickname: string): never {
      throw new LedgerWriteError("添加孩子会把昵称写进这台设备上的育儿账本。这一批不保存、不同步任何孩子资料。");
    },
  };
}
