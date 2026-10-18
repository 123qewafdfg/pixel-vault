const HEX_32_BYTES = /^[0-9a-f]{64}$/;
const HEX_ID = /^[0-9a-f]{8}$/;
const BASE64URL = /^[A-Za-z0-9_-]*$/;

export function encodeBase64Url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64url");
}

export function decodeBase64Url(value: string, expectedLength?: number): Uint8Array {
  if (!BASE64URL.test(value)) {
    throw new TypeError("invalid Base64URL value");
  }
  const bytes = Buffer.from(value, "base64url");
  if (bytes.toString("base64url") !== value) {
    throw new TypeError("non-canonical Base64URL value");
  }
  if (expectedLength !== undefined && bytes.byteLength !== expectedLength) {
    throw new RangeError(`decoded value must be ${expectedLength} bytes`);
  }
  return new Uint8Array(bytes);
}

export function parseSha256(value: string): string {
  const normalized = value.toLowerCase();
  if (!HEX_32_BYTES.test(normalized)) {
    throw new TypeError("SHA-256 must be 64 hexadecimal characters");
  }
  return normalized;
}

export function encodeResourceId(id: Uint8Array): string {
  if (id.byteLength !== 4) throw new RangeError("resource ID must be 4 bytes");
  return Buffer.from(id).toString("hex");
}

export function decodeResourceId(value: string): Uint8Array {
  const normalized = value.toLowerCase();
  if (!HEX_ID.test(normalized)) throw new TypeError("resource ID must be 8 hexadecimal characters");
  return new Uint8Array(Buffer.from(normalized, "hex"));
}
