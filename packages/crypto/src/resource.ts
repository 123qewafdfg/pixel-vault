import { sha256 } from "@noble/hashes/sha2.js";
import { dataAad, metadataAad } from "./aad.ts";
import { decryptAesGcm, encryptAesGcm } from "./aes-gcm.ts";
import { equalBytes, ownedBytes } from "./bytes.ts";
import { DEFAULT_CHUNK_SIZE, MAX_PARTS, RESOURCE_UUID_SIZE } from "./constants.ts";
import { CryptoPipelineError } from "./errors.ts";
import { sha256Bytes } from "./hash.ts";
import { decodeMetadata, encodeMetadata, type FileMetadata } from "./metadata.ts";
import { generateFileKey, generateIv, generateResourceUuid } from "./random.ts";

export interface EncryptedPart {
  resourceUuid: Uint8Array;
  partIndex: number;
  partCount: number;
  iv: Uint8Array;
  ciphertext: Uint8Array;
  cipherSha256: Uint8Array;
}

export interface EncryptedMetadata {
  iv: Uint8Array;
  ciphertext: Uint8Array;
}

export interface EncryptedResource {
  resourceUuid: Uint8Array;
  fileKey: Uint8Array;
  originalSha256: Uint8Array;
  encryptedMetadata: EncryptedMetadata;
  parts: EncryptedPart[];
}

export interface EncryptResourceOptions {
  fileName: string;
  mimeType?: string;
  chunkSize?: number;
  fileKey?: Uint8Array;
  resourceUuid?: Uint8Array;
}

function validateChunkSize(chunkSize: number): void {
  if (!Number.isSafeInteger(chunkSize) || chunkSize < 1) throw new RangeError("chunkSize must be positive");
}

export async function encryptResource(blob: Blob, options: EncryptResourceOptions): Promise<EncryptedResource> {
  const chunkSize = options.chunkSize ?? DEFAULT_CHUNK_SIZE;
  validateChunkSize(chunkSize);
  const partCount = Math.max(1, Math.ceil(blob.size / chunkSize));
  if (partCount > MAX_PARTS) throw new CryptoPipelineError("TOO_MANY_PARTS", `Resource exceeds ${MAX_PARTS} parts`);
  const fileKey = options.fileKey ? Uint8Array.from(options.fileKey) : generateFileKey();
  const resourceUuid = options.resourceUuid ? Uint8Array.from(options.resourceUuid) : generateResourceUuid();
  if (resourceUuid.length !== RESOURCE_UUID_SIZE) throw new CryptoPipelineError("INVALID_RESOURCE", "Resource UUID must be exactly 16 bytes");

  const originalHash = sha256.create();
  const parts: EncryptedPart[] = [];
  for (let partIndex = 0; partIndex < partCount; partIndex += 1) {
    const start = partIndex * chunkSize;
    const plaintext = new Uint8Array(await blob.slice(start, Math.min(blob.size, start + chunkSize)).arrayBuffer());
    originalHash.update(plaintext);
    const iv = generateIv();
    const ciphertext = await encryptAesGcm(plaintext, fileKey, iv, dataAad(resourceUuid, partIndex, partCount));
    parts.push({ resourceUuid: Uint8Array.from(resourceUuid), partIndex, partCount, iv, ciphertext, cipherSha256: sha256Bytes(ciphertext) });
  }
  const originalSha256 = originalHash.digest();
  const metadata: FileMetadata = { fileName: options.fileName, mimeType: options.mimeType ?? blob.type, originalSize: blob.size, originalSha256 };
  const metadataIv = generateIv();
  const metadataCiphertext = await encryptAesGcm(encodeMetadata(metadata), fileKey, metadataIv, metadataAad(resourceUuid));
  return { resourceUuid, fileKey, originalSha256, encryptedMetadata: { iv: metadataIv, ciphertext: metadataCiphertext }, parts };
}

export async function decryptResource(resourceUuid: Uint8Array, fileKey: Uint8Array, partsInput: readonly EncryptedPart[], encryptedMetadata: EncryptedMetadata): Promise<{ file: Blob; metadata: FileMetadata }> {
  if (resourceUuid.length !== RESOURCE_UUID_SIZE || partsInput.length < 1) throw new CryptoPipelineError("INVALID_RESOURCE", "Resource identity or parts are invalid");
  const partCount = partsInput[0]!.partCount;
  if (partCount < 1 || partCount > MAX_PARTS || partsInput.length !== partCount) throw new CryptoPipelineError("MISSING_PART", "Resource has missing parts");
  const seen = new Set<number>();
  for (const part of partsInput) {
    if (!equalBytes(part.resourceUuid, resourceUuid)) throw new CryptoPipelineError("MIXED_RESOURCE", "Parts belong to different resources");
    if (part.partCount !== partCount || part.partIndex < 0 || part.partIndex >= partCount) throw new CryptoPipelineError("INVALID_RESOURCE", "Part numbering is inconsistent");
    if (seen.has(part.partIndex)) throw new CryptoPipelineError("DUPLICATE_PART", `Duplicate part ${part.partIndex}`);
    seen.add(part.partIndex);
  }
  for (let index = 0; index < partCount; index += 1) {
    if (!seen.has(index)) throw new CryptoPipelineError("MISSING_PART", `Missing part ${index}`);
  }

  const metadataPlaintext = await decryptAesGcm(encryptedMetadata.ciphertext, fileKey, encryptedMetadata.iv, metadataAad(resourceUuid));
  const metadata = decodeMetadata(metadataPlaintext);
  const sorted = [...partsInput].sort((left, right) => left.partIndex - right.partIndex);
  const outputParts: Uint8Array<ArrayBuffer>[] = [];
  const originalHash = sha256.create();
  let totalSize = 0;
  for (const part of sorted) {
    if (!equalBytes(sha256Bytes(part.ciphertext), part.cipherSha256)) throw new CryptoPipelineError("HASH_MISMATCH", `Ciphertext hash mismatch for part ${part.partIndex}`);
    const plaintext = await decryptAesGcm(part.ciphertext, fileKey, part.iv, dataAad(resourceUuid, part.partIndex, partCount));
    originalHash.update(plaintext);
    totalSize += plaintext.length;
    outputParts.push(ownedBytes(plaintext));
  }
  if (totalSize !== metadata.originalSize || !equalBytes(originalHash.digest(), metadata.originalSha256)) {
    throw new CryptoPipelineError("HASH_MISMATCH", "Restored file hash or size does not match metadata");
  }
  return { file: new Blob(outputParts, { type: metadata.mimeType }), metadata };
}
