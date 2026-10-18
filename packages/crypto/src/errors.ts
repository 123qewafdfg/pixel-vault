export type CryptoErrorCode =
  | "INVALID_KEY"
  | "INVALID_IV"
  | "INVALID_RESOURCE"
  | "TOO_MANY_PARTS"
  | "DUPLICATE_PART"
  | "MISSING_PART"
  | "MIXED_RESOURCE"
  | "HASH_MISMATCH"
  | "AUTHENTICATION_FAILED"
  | "METADATA_INVALID";

export class CryptoPipelineError extends Error {
  readonly code: CryptoErrorCode;
  constructor(code: CryptoErrorCode, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "CryptoPipelineError";
    this.code = code;
  }
}
