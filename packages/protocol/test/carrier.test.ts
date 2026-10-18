import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createCarrier, extractCarrier, findPngEnd, ProtocolError } from "../src/index.ts";
import { bytes, TEST_PNG } from "./fixtures.ts";

const fields = {
  flags: 0,
  resourceId: bytes(16, 1),
  partIndex: 0,
  partCount: 2,
  dataIv: bytes(12, 20),
  metadataIv: bytes(12, 40),
  cipherSha256: bytes(32, 60),
};

describe("PNG carrier", () => {
  it("locates the exact IEND boundary", () => {
    assert.equal(findPngEnd(TEST_PNG), TEST_PNG.length);
  });

  it("round-trips ciphertext, metadata and index", () => {
    const ciphertext = bytes(513, 9);
    const metadata = bytes(47, 100);
    const carrier = createCarrier(TEST_PNG, ciphertext, metadata, fields);
    const parsed = extractCarrier(carrier);
    assert.deepEqual(parsed.png, TEST_PNG);
    assert.deepEqual(parsed.ciphertext, ciphertext);
    assert.deepEqual(parsed.encryptedMetadata, metadata);
    assert.equal(parsed.index.partIndex, 0);
    assert.equal(parsed.index.partCount, 2);
  });

  it("rejects a truncated carrier", () => {
    const carrier = createCarrier(TEST_PNG, bytes(20), bytes(3), fields);
    assert.throws(() => extractCarrier(carrier.slice(0, -1)), ProtocolError);
  });

  it("rejects a PNG with pre-existing trailing data", () => {
    const dirtyPng = new Uint8Array(TEST_PNG.length + 1);
    dirtyPng.set(TEST_PNG);
    assert.throws(() => createCarrier(dirtyPng, bytes(20), bytes(3), fields), /trailing bytes/);
  });

  it("rejects metadata on non-zero parts", () => {
    assert.throws(() => createCarrier(TEST_PNG, bytes(20), bytes(3), { ...fields, partIndex: 1 }), /part zero/);
  });
});
