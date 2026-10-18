import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { decodeTailIndex, encodeTailIndex, OFFSETS, ProtocolError, TAIL_INDEX_SIZE } from "../src/index.ts";
import { bytes } from "./fixtures.ts";

const hex = (input: Uint8Array): string =>
  Array.from(input, (byte) => byte.toString(16).padStart(2, "0")).join("");

const vector = {
  flags: 0x0102,
  resourceId: bytes(16, 0x10),
  partIndex: 1,
  partCount: 3,
  cipherLength: 0x0102030405060708n,
  metadataLength: 0,
  originalPngLength: 0x1112131415161718n,
  dataIv: bytes(12, 0x20),
  metadataIv: bytes(12, 0x30),
  cipherSha256: bytes(32, 0x40),
};

describe("tail index", () => {
  it("encodes a stable 112-byte big-endian vector", () => {
    const encoded = encodeTailIndex(vector);
    assert.equal(encoded.length, TAIL_INDEX_SIZE);
    assert.equal(hex(encoded.subarray(0, 44)),
      "544d53484152453100010102101112131415161718191a1b1c1d1e1f00010003010203040506070800000000",
    );
    assert.deepEqual(decodeTailIndex(encoded), { version: 1, ...vector });
  });

  it("rejects a one-bit index mutation", () => {
    const encoded = encodeTailIndex(vector);
    encoded[OFFSETS.flags] = encoded[OFFSETS.flags]! ^ 1;
    assert.throws(() => decodeTailIndex(encoded), ProtocolError);
  });

  it("rejects metadata outside part zero", () => {
    assert.throws(() => encodeTailIndex({ ...vector, metadataLength: 1 }), /part zero/);
  });
});
