import assert from "node:assert/strict";
import { test } from "node:test";
import { createLocalLedger, LedgerWriteError } from "../miniprogram/utils/ledger.ts";

test("adding a child does not write a record", () => {
  const ledger = createLocalLedger();
  assert.deepEqual(ledger.list(), []);
  assert.throws(() => ledger.addChild("豆豆"), LedgerWriteError);
  assert.deepEqual(ledger.list(), []);
});
