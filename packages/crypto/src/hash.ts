import { sha256 } from "@noble/hashes/sha2.js";

export function sha256Bytes(input: Uint8Array): Uint8Array {
  return sha256(input);
}

export async function sha256Blob(blob: Blob, readSize = 4 * 1024 * 1024): Promise<Uint8Array> {
  if (!Number.isSafeInteger(readSize) || readSize < 1) throw new RangeError("readSize must be positive");
  const hash = sha256.create();
  for (let offset = 0; offset < blob.size; offset += readSize) {
    hash.update(new Uint8Array(await blob.slice(offset, offset + readSize).arrayBuffer()));
  }
  return hash.digest();
}
