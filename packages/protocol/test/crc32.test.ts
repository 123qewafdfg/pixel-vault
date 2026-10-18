import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { crc32 } from "../src/index.ts";

describe("crc32", () => {
  it("matches the canonical ASCII test vector", () => {
    assert.equal(crc32(new TextEncoder().encode("123456789")), 0xcbf43926);
  });
});
