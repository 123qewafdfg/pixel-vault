import { TAIL_INDEX_SIZE } from "./constants.ts";
import { ProtocolError } from "./errors.ts";
import { assertExactPng, findPngEnd } from "./png.ts";
import { decodeTailIndex, encodeTailIndex, type TailIndex, type TailIndexInput } from "./tail-index.ts";

export interface CarrierParts {
  png: Uint8Array;
  ciphertext: Uint8Array;
  encryptedMetadata: Uint8Array;
  index: TailIndex;
}

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const output = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function safeNumber(value: bigint, field: string): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new ProtocolError("UNSAFE_LENGTH", `${field} exceeds Number.MAX_SAFE_INTEGER`);
  }
  return Number(value);
}

export function createCarrier(
  png: Uint8Array,
  ciphertext: Uint8Array,
  encryptedMetadata: Uint8Array,
  fields: Omit<TailIndexInput, "cipherLength" | "metadataLength" | "originalPngLength">,
): Uint8Array {
  assertExactPng(png);
  if (ciphertext.length === 0) {
    throw new ProtocolError("INVALID_LAYOUT", "Ciphertext must not be empty");
  }
  if (fields.partIndex !== 0 && encryptedMetadata.length !== 0) {
    throw new ProtocolError("INVALID_LAYOUT", "Only part zero may carry encrypted metadata");
  }
  const index = encodeTailIndex({
    ...fields,
    cipherLength: BigInt(ciphertext.length),
    metadataLength: encryptedMetadata.length,
    originalPngLength: BigInt(png.length),
  });
  return concat([png, ciphertext, encryptedMetadata, index]);
}

export function extractCarrier(carrier: Uint8Array): CarrierParts {
  if (carrier.length < TAIL_INDEX_SIZE) {
    throw new ProtocolError("INVALID_LAYOUT", "Carrier is shorter than the tail index");
  }
  const indexOffset = carrier.length - TAIL_INDEX_SIZE;
  const index = decodeTailIndex(carrier.subarray(indexOffset));
  const pngLength = safeNumber(index.originalPngLength, "originalPngLength");
  const cipherLength = safeNumber(index.cipherLength, "cipherLength");
  const metadataOffset = pngLength + cipherLength;
  const expectedIndexOffset = metadataOffset + index.metadataLength;
  if (expectedIndexOffset !== indexOffset) {
    throw new ProtocolError("INVALID_LAYOUT", "Carrier lengths do not match the file size");
  }
  if (pngLength > indexOffset || findPngEnd(carrier.subarray(0, pngLength)) !== pngLength) {
    throw new ProtocolError("INVALID_LAYOUT", "Original PNG boundary is invalid");
  }
  return {
    png: carrier.slice(0, pngLength),
    ciphertext: carrier.slice(pngLength, metadataOffset),
    encryptedMetadata: carrier.slice(metadataOffset, indexOffset),
    index,
  };
}
