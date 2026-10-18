import { PNG_SIGNATURE } from "./constants.ts";
import { ProtocolError } from "./errors.ts";

const CHUNK_OVERHEAD = 12;
const IEND = "IEND";

function equalAt(input: Uint8Array, expected: Uint8Array, offset: number): boolean {
  if (offset + expected.length > input.length) return false;
  return expected.every((byte, index) => input[offset + index] === byte);
}

function chunkType(input: Uint8Array, offset: number): string {
  return String.fromCharCode(
    input[offset]!,
    input[offset + 1]!,
    input[offset + 2]!,
    input[offset + 3]!,
  );
}

/** Returns the byte immediately after the first structurally valid IEND chunk. */
export function findPngEnd(input: Uint8Array): number {
  if (!equalAt(input, PNG_SIGNATURE, 0)) {
    throw new ProtocolError("INVALID_PNG", "PNG signature is missing or invalid");
  }

  const view = new DataView(input.buffer, input.byteOffset, input.byteLength);
  let offset = PNG_SIGNATURE.length;
  while (offset < input.length) {
    if (input.length - offset < CHUNK_OVERHEAD) {
      throw new ProtocolError("TRUNCATED_PNG", "PNG chunk header is truncated");
    }
    const dataLength = view.getUint32(offset, false);
    const typeOffset = offset + 4;
    const end = offset + CHUNK_OVERHEAD + dataLength;
    if (!Number.isSafeInteger(end) || end > input.length) {
      throw new ProtocolError("TRUNCATED_PNG", "PNG chunk data extends past the input");
    }
    const type = chunkType(input, typeOffset);
    if (type === IEND) {
      if (dataLength !== 0) {
        throw new ProtocolError("INVALID_PNG", "IEND chunk must have zero data length");
      }
      return end;
    }
    offset = end;
  }
  throw new ProtocolError("MISSING_IEND", "PNG does not contain an IEND chunk");
}

export function assertExactPng(input: Uint8Array): void {
  const end = findPngEnd(input);
  if (end !== input.length) {
    throw new ProtocolError("INVALID_PNG", "Carrier PNG already contains trailing bytes");
  }
}
