import {
  GCM_IV_SIZE,
  MAGIC,
  OFFSETS,
  PROTOCOL_VERSION,
  RESOURCE_ID_SIZE,
  SHA256_SIZE,
  TAIL_INDEX_SIZE,
} from "./constants.ts";
import { crc32 } from "./crc32.ts";
import { ProtocolError } from "./errors.ts";

export interface TailIndex {
  version: number;
  flags: number;
  resourceId: Uint8Array;
  partIndex: number;
  partCount: number;
  cipherLength: bigint;
  metadataLength: number;
  originalPngLength: bigint;
  dataIv: Uint8Array;
  metadataIv: Uint8Array;
  cipherSha256: Uint8Array;
}

export type TailIndexInput = Omit<TailIndex, "version"> & { version?: number };

function assertBytes(name: string, value: Uint8Array, expected: number): void {
  if (value.length !== expected) {
    throw new ProtocolError("INVALID_INDEX", `${name} must be exactly ${expected} bytes`);
  }
}

function copy(input: Uint8Array, start: number, length: number): Uint8Array {
  return input.slice(start, start + length);
}

function magicMatches(input: Uint8Array): boolean {
  return MAGIC.every((byte, index) => input[index] === byte);
}

function validateSemanticFields(index: TailIndex): void {
  if (index.version !== PROTOCOL_VERSION) {
    throw new ProtocolError("UNSUPPORTED_VERSION", `Unsupported protocol version ${index.version}`);
  }
  if (index.partCount < 1 || index.partIndex >= index.partCount) {
    throw new ProtocolError("INVALID_INDEX", "Part index must be less than a non-zero part count");
  }
  if (index.cipherLength < 1n || index.originalPngLength < 1n) {
    throw new ProtocolError("INVALID_INDEX", "Cipher and PNG lengths must be positive");
  }
  if (index.partIndex !== 0 && index.metadataLength !== 0) {
    throw new ProtocolError("INVALID_INDEX", "Only part zero may carry encrypted metadata");
  }
}

export function encodeTailIndex(input: TailIndexInput): Uint8Array {
  const index: TailIndex = { ...input, version: input.version ?? PROTOCOL_VERSION };
  assertBytes("resourceId", index.resourceId, RESOURCE_ID_SIZE);
  assertBytes("dataIv", index.dataIv, GCM_IV_SIZE);
  assertBytes("metadataIv", index.metadataIv, GCM_IV_SIZE);
  assertBytes("cipherSha256", index.cipherSha256, SHA256_SIZE);
  validateSemanticFields(index);

  const output = new Uint8Array(TAIL_INDEX_SIZE);
  output.set(MAGIC, OFFSETS.magic);
  output.set(index.resourceId, OFFSETS.resourceId);
  output.set(index.dataIv, OFFSETS.dataIv);
  output.set(index.metadataIv, OFFSETS.metadataIv);
  output.set(index.cipherSha256, OFFSETS.cipherSha256);
  const view = new DataView(output.buffer);
  view.setUint16(OFFSETS.version, index.version, false);
  view.setUint16(OFFSETS.flags, index.flags, false);
  view.setUint16(OFFSETS.partIndex, index.partIndex, false);
  view.setUint16(OFFSETS.partCount, index.partCount, false);
  view.setBigUint64(OFFSETS.cipherLength, index.cipherLength, false);
  view.setUint32(OFFSETS.metadataLength, index.metadataLength, false);
  view.setBigUint64(OFFSETS.originalPngLength, index.originalPngLength, false);
  view.setUint32(OFFSETS.crc32, crc32(output.subarray(0, OFFSETS.crc32)), false);
  return output;
}

export function decodeTailIndex(input: Uint8Array): TailIndex {
  if (input.length !== TAIL_INDEX_SIZE) {
    throw new ProtocolError("INVALID_INDEX", `Tail index must be exactly ${TAIL_INDEX_SIZE} bytes`);
  }
  if (!magicMatches(input)) {
    throw new ProtocolError("INVALID_INDEX", "Tail index magic is invalid");
  }
  const view = new DataView(input.buffer, input.byteOffset, input.byteLength);
  const storedCrc = view.getUint32(OFFSETS.crc32, false);
  const actualCrc = crc32(input.subarray(0, OFFSETS.crc32));
  if (storedCrc !== actualCrc) {
    throw new ProtocolError("INVALID_INDEX", "Tail index CRC32 mismatch");
  }
  const result: TailIndex = {
    version: view.getUint16(OFFSETS.version, false),
    flags: view.getUint16(OFFSETS.flags, false),
    resourceId: copy(input, OFFSETS.resourceId, RESOURCE_ID_SIZE),
    partIndex: view.getUint16(OFFSETS.partIndex, false),
    partCount: view.getUint16(OFFSETS.partCount, false),
    cipherLength: view.getBigUint64(OFFSETS.cipherLength, false),
    metadataLength: view.getUint32(OFFSETS.metadataLength, false),
    originalPngLength: view.getBigUint64(OFFSETS.originalPngLength, false),
    dataIv: copy(input, OFFSETS.dataIv, GCM_IV_SIZE),
    metadataIv: copy(input, OFFSETS.metadataIv, GCM_IV_SIZE),
    cipherSha256: copy(input, OFFSETS.cipherSha256, SHA256_SIZE),
  };
  validateSemanticFields(result);
  return result;
}
