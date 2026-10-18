export type ProtocolErrorCode =
  | "INVALID_PNG"
  | "TRUNCATED_PNG"
  | "MISSING_IEND"
  | "INVALID_INDEX"
  | "UNSUPPORTED_VERSION"
  | "INVALID_LAYOUT"
  | "UNSAFE_LENGTH";

export class ProtocolError extends Error {
  readonly code: ProtocolErrorCode;

  constructor(code: ProtocolErrorCode, message: string) {
    super(message);
    this.name = "ProtocolError";
    this.code = code;
  }
}
