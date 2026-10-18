import { AES_KEY_SIZE, GCM_IV_SIZE, RESOURCE_UUID_SIZE } from "./constants.ts";

export function randomBytes(length: number): Uint8Array {
  if (!Number.isSafeInteger(length) || length < 1 || length > 65_536) {
    throw new RangeError("Random byte length must be between 1 and 65536");
  }
  return globalThis.crypto.getRandomValues(new Uint8Array(length));
}

export const generateFileKey = (): Uint8Array => randomBytes(AES_KEY_SIZE);
export const generateIv = (): Uint8Array => randomBytes(GCM_IV_SIZE);
export const generateResourceUuid = (): Uint8Array => randomBytes(RESOURCE_UUID_SIZE);
