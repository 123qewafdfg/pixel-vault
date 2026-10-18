import assert from "node:assert/strict";
import test from "node:test";

import { ResourceRepository } from "../src/database.ts";
import { KeyProtector } from "../src/key-protector.ts";
import { resourceInput } from "./helpers.ts";

function writeInput(parts = resourceInput().parts) {
  return {
    protocolVersion: 1,
    encryptedFileKey: new Uint8Array(48),
    keyIv: new Uint8Array(12),
    encryptedMetadata: "AA",
    originalSha256: "ab".repeat(32),
    parts,
  };
}

test("retries a colliding four-byte ID", () => {
  const ids = [Uint8Array.of(1, 1, 1, 1), Uint8Array.of(1, 1, 1, 1), Uint8Array.of(2, 2, 2, 2)];
  const repository = new ResourceRepository(":memory:", () => ids.shift()!);
  try {
    const version = repository.database.prepare("PRAGMA user_version").get() as { user_version: number };
    assert.equal(version.user_version, 1);
    assert.deepEqual(repository.create(writeInput()), Uint8Array.of(1, 1, 1, 1));
    assert.deepEqual(repository.create(writeInput()), Uint8Array.of(2, 2, 2, 2));
  } finally {
    repository.close();
  }
});

test("rolls back the resource when any part insert fails", () => {
  const repository = new ResourceRepository(":memory:", () => Uint8Array.of(9, 9, 9, 9));
  const duplicated = resourceInput().parts;
  duplicated[1]!.partIndex = 0;
  try {
    assert.throws(() => repository.create(writeInput(duplicated)));
    const count = repository.database.prepare("SELECT count(*) AS count FROM resources").get() as { count: number };
    assert.equal(count.count, 0);
  } finally {
    repository.close();
  }
});

test("AES-GCM key protection rejects a different server master key", () => {
  const fileKey = Uint8Array.from({ length: 32 }, (_, index) => index + 10);
  const first = new KeyProtector(new Uint8Array(32));
  const second = new KeyProtector(new Uint8Array(32).fill(1));
  const protectedKey = first.protect(fileKey);
  assert.deepEqual(first.unprotect(protectedKey.encrypted, protectedKey.iv), fileKey);
  assert.throws(() => second.unprotect(protectedKey.encrypted, protectedKey.iv));
});
