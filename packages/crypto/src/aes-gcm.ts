import { AES_KEY_SIZE, GCM_IV_SIZE, GCM_TAG_LENGTH } from "./constants.ts";
import { ownedBytes } from "./bytes.ts";
import { CryptoPipelineError } from "./errors.ts";

async function importAesKey(rawKey: Uint8Array, usage: KeyUsage): Promise<CryptoKey> {
  if (rawKey.length !== AES_KEY_SIZE) {
    throw new CryptoPipelineError("INVALID_KEY", "AES-256 key must be exactly 32 bytes");
  }
  return globalThis.crypto.subtle.importKey("raw", ownedBytes(rawKey), "AES-GCM", false, [usage]);
}

function algorithm(iv: Uint8Array, additionalData: Uint8Array): AesGcmParams {
  if (iv.length !== GCM_IV_SIZE) {
    throw new CryptoPipelineError("INVALID_IV", "AES-GCM IV must be exactly 12 bytes");
  }
  return { name: "AES-GCM", iv: ownedBytes(iv), additionalData: ownedBytes(additionalData), tagLength: GCM_TAG_LENGTH };
}

export async function encryptAesGcm(plaintext: Uint8Array, rawKey: Uint8Array, iv: Uint8Array, additionalData: Uint8Array): Promise<Uint8Array> {
  const key = await importAesKey(rawKey, "encrypt");
  const result = await globalThis.crypto.subtle.encrypt(algorithm(iv, additionalData), key, ownedBytes(plaintext));
  return new Uint8Array(result);
}

export async function decryptAesGcm(ciphertext: Uint8Array, rawKey: Uint8Array, iv: Uint8Array, additionalData: Uint8Array): Promise<Uint8Array> {
  const key = await importAesKey(rawKey, "decrypt");
  try {
    const result = await globalThis.crypto.subtle.decrypt(algorithm(iv, additionalData), key, ownedBytes(ciphertext));
    return new Uint8Array(result);
  } catch (cause) {
    throw new CryptoPipelineError("AUTHENTICATION_FAILED", "AES-GCM authentication failed", { cause });
  }
}
