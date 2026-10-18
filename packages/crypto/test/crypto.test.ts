import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { dataAad, decryptAesGcm, encryptAesGcm, sha256Bytes, toHex } from "../src/index.ts";
import { bytes } from "./fixtures.ts";

describe("cryptographic primitives", () => {
  it("matches the SHA-256 abc test vector", () => {
    assert.equal(toHex(sha256Bytes(new TextEncoder().encode("abc"))), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });

  it("round-trips AES-256-GCM with bound AAD", async () => {
    const key = bytes(32, 1); const iv = bytes(12, 40); const uuid = bytes(16, 70);
    const plaintext = new TextEncoder().encode("authenticated payload");
    const aad = dataAad(uuid, 0, 1);
    const ciphertext = await encryptAesGcm(plaintext, key, iv, aad);
    assert.deepEqual(await decryptAesGcm(ciphertext, key, iv, aad), plaintext);
    await assert.rejects(() => decryptAesGcm(ciphertext, bytes(32, 2), iv, aad), /authentication failed/);
    await assert.rejects(() => decryptAesGcm(ciphertext, key, bytes(12, 41), aad), /authentication failed/);
    await assert.rejects(() => decryptAesGcm(ciphertext, key, iv, dataAad(uuid, 0, 2)), /authentication failed/);
  });
});
