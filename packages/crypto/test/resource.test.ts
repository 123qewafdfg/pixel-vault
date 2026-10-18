import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createResourceCarriers, CryptoPipelineError, decryptResource, encryptResource, extractResourceCarriers, ownedBytes } from "../src/index.ts";
import { bytes, TEST_PNG } from "./fixtures.ts";

const plaintext = bytes(37, 9);
const options = { fileName: "测试.bin", mimeType: "application/octet-stream", chunkSize: 10, fileKey: bytes(32, 1), resourceUuid: bytes(16, 90) };

describe("encrypted resources", () => {
  it("encrypts, accepts shuffled parts and restores the original file", async () => {
    const encrypted = await encryptResource(new Blob([ownedBytes(plaintext)]), options);
    assert.equal(encrypted.parts.length, 4);
    const restored = await decryptResource(encrypted.resourceUuid, encrypted.fileKey, [...encrypted.parts].reverse(), encrypted.encryptedMetadata);
    assert.deepEqual(new Uint8Array(await restored.file.arrayBuffer()), plaintext);
    assert.equal(restored.metadata.fileName, "测试.bin");
  });

  it("rejects missing, duplicate and mixed-resource parts", async () => {
    const encrypted = await encryptResource(new Blob([ownedBytes(plaintext)]), options);
    await assert.rejects(() => decryptResource(encrypted.resourceUuid, encrypted.fileKey, encrypted.parts.slice(1), encrypted.encryptedMetadata), (error: unknown) => error instanceof CryptoPipelineError && error.code === "MISSING_PART");
    const duplicate = [encrypted.parts[0]!, encrypted.parts[0]!, ...encrypted.parts.slice(2)];
    await assert.rejects(() => decryptResource(encrypted.resourceUuid, encrypted.fileKey, duplicate, encrypted.encryptedMetadata), (error: unknown) => error instanceof CryptoPipelineError && error.code === "DUPLICATE_PART");
    const mixed = encrypted.parts.map((part, index) => index === 1 ? { ...part, resourceUuid: bytes(16, 3) } : part);
    await assert.rejects(() => decryptResource(encrypted.resourceUuid, encrypted.fileKey, mixed, encrypted.encryptedMetadata), (error: unknown) => error instanceof CryptoPipelineError && error.code === "MIXED_RESOURCE");
  });

  it("rejects ciphertext mutation before decryption", async () => {
    const encrypted = await encryptResource(new Blob([ownedBytes(plaintext)]), options);
    encrypted.parts[0]!.ciphertext[0] = encrypted.parts[0]!.ciphertext[0]! ^ 1;
    await assert.rejects(() => decryptResource(encrypted.resourceUuid, encrypted.fileKey, encrypted.parts, encrypted.encryptedMetadata), (error: unknown) => error instanceof CryptoPipelineError && error.code === "HASH_MISMATCH");
  });

  it("round-trips through PNG carriers", async () => {
    const encrypted = await encryptResource(new Blob([ownedBytes(plaintext)]), options);
    const carriers = createResourceCarriers(encrypted, [TEST_PNG]);
    const extracted = extractResourceCarriers(carriers.reverse());
    const restored = await decryptResource(extracted.resourceUuid, encrypted.fileKey, extracted.parts, extracted.encryptedMetadata);
    assert.deepEqual(new Uint8Array(await restored.file.arrayBuffer()), plaintext);
  });

  it("supports an authenticated empty file", async () => {
    const encrypted = await encryptResource(new Blob([]), { ...options, fileName: "empty.bin" });
    assert.equal(encrypted.parts.length, 1);
    const restored = await decryptResource(encrypted.resourceUuid, encrypted.fileKey, encrypted.parts, encrypted.encryptedMetadata);
    assert.equal(restored.file.size, 0);
  });

  it("accepts 25 parts and rejects a 26th part", async () => {
    const atLimit = await encryptResource(new Blob([ownedBytes(bytes(25))]), { ...options, chunkSize: 1 });
    assert.equal(atLimit.parts.length, 25);
    await assert.rejects(
      () => encryptResource(new Blob([ownedBytes(bytes(26))]), { ...options, chunkSize: 1 }),
      (error: unknown) => error instanceof CryptoPipelineError && error.code === "TOO_MANY_PARTS",
    );
  });
});
