import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

const KEY_BYTES = 32;
const IV_BYTES = 12;
const TAG_BYTES = 16;
const AAD = Buffer.from("trustmark-share:file-key:v1", "ascii");

export interface ProtectedKey {
  encrypted: Uint8Array;
  iv: Uint8Array;
}

export class KeyProtector {
  private readonly masterKey: Buffer;

  constructor(masterKey: Uint8Array) {
    if (masterKey.byteLength !== KEY_BYTES) throw new RangeError("server master key must be exactly 32 bytes");
    this.masterKey = Buffer.from(masterKey);
  }

  protect(fileKey: Uint8Array): ProtectedKey {
    if (fileKey.byteLength !== KEY_BYTES) throw new RangeError("file key must be exactly 32 bytes");
    const iv = randomBytes(IV_BYTES);
    const cipher = createCipheriv("aes-256-gcm", this.masterKey, iv);
    cipher.setAAD(AAD);
    const ciphertext = Buffer.concat([cipher.update(fileKey), cipher.final()]);
    const encrypted = Buffer.concat([ciphertext, cipher.getAuthTag()]);
    return { encrypted: new Uint8Array(encrypted), iv: new Uint8Array(iv) };
  }

  unprotect(encrypted: Uint8Array, iv: Uint8Array): Uint8Array {
    if (iv.byteLength !== IV_BYTES || encrypted.byteLength !== KEY_BYTES + TAG_BYTES) {
      throw new Error("protected file key has an invalid length");
    }
    const body = Buffer.from(encrypted.subarray(0, -TAG_BYTES));
    const tag = Buffer.from(encrypted.subarray(-TAG_BYTES));
    const decipher = createDecipheriv("aes-256-gcm", this.masterKey, iv);
    decipher.setAAD(AAD);
    decipher.setAuthTag(tag);
    return new Uint8Array(Buffer.concat([decipher.update(body), decipher.final()]));
  }
}
