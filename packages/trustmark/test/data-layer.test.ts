import assert from "node:assert/strict";
import test from "node:test";

import {
  TrustMarkDataError,
  decodeTrustMarkId,
  encodeTrustMarkId,
} from "../src/index.ts";
import { BCH, BCH_Encode } from "../src/vendor/bch_ecc.js";

function bits(value: string): Uint8Array {
  return Uint8Array.from(value, Number);
}

const vectors = [
  {
    id: Uint8Array.of(0, 0, 0, 0),
    packet: "0".repeat(100),
  },
  {
    id: Uint8Array.of(0x12, 0x34, 0x56, 0x78),
    packet:
      "0001001000110100010101100111100000000000010101011001101111010110111010100100110000101100010001110000",
  },
  {
    id: Uint8Array.of(0xff, 0xff, 0xff, 0xff),
    packet:
      "1111111111111111111111111111111100000000011100001010110000110001100011010010110111010101111011100000",
  },
];

test("matches Adobe BCH_SUPER reference vectors", () => {
  for (const vector of vectors) {
    assert.deepEqual(encodeTrustMarkId(vector.id), bits(vector.packet));
    assert.deepEqual(decodeTrustMarkId(bits(vector.packet)).id, vector.id);
  }
});

test("corrects up to eight data/ECC bit errors", () => {
  const vector = vectors[1]!;
  for (const errorCount of [1, 4, 8]) {
    const damaged = bits(vector.packet);
    const positions = [0, 7, 19, 31, 40, 55, 73, 95];
    for (const position of positions.slice(0, errorCount)) {
      damaged[position] = (damaged[position] ?? 0) ^ 1;
    }
    const decoded = decodeTrustMarkId(damaged);
    assert.deepEqual(decoded.id, vector.id);
    assert.equal(decoded.correctedBits, errorCount);
  }
});

test("rejects malformed packets and unsupported schema", () => {
  assert.throws(() => encodeTrustMarkId(Uint8Array.of(1, 2, 3)), TrustMarkDataError);
  assert.throws(() => decodeTrustMarkId(new Uint8Array(99)), TrustMarkDataError);
  const nonBit = new Uint8Array(100);
  nonBit[3] = 2;
  assert.throws(() => decodeTrustMarkId(nonBit), /must be 0 or 1/);
  const wrongVersion = new Uint8Array(100);
  wrongVersion[99] = 1;
  assert.throws(() => decodeTrustMarkId(wrongVersion), /schema version/);
});

test("rejects a valid BCH payload whose reserved ID padding is nonzero", () => {
  const data = bytesToBooleanBits(Uint8Array.of(1, 2, 3, 4, 0x80));
  const eccBytes = BCH_Encode(BCH(8, 137), data);
  const packet = new Uint8Array(100);
  packet.set(data.map(Number));
  packet.set(bytesToBooleanBits(Uint8Array.from(eccBytes)).slice(0, 56).map(Number), 40);
  assert.throws(() => decodeTrustMarkId(packet), TrustMarkDataError);
});

function bytesToBooleanBits(bytes: Uint8Array): boolean[] {
  const result: boolean[] = [];
  for (const byte of bytes) {
    for (let shift = 7; shift >= 0; shift -= 1) {
      result.push(((byte >>> shift) & 1) === 1);
    }
  }
  return result;
}
