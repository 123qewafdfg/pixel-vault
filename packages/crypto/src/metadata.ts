import { fromHex, toHex } from "./bytes.ts";
import { CryptoPipelineError } from "./errors.ts";

export interface FileMetadata {
  fileName: string;
  mimeType: string;
  originalSize: number;
  originalSha256: Uint8Array;
}

interface SerializedMetadata {
  version: 1;
  fileName: string;
  mimeType: string;
  originalSize: number;
  originalSha256: string;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });

export function encodeMetadata(metadata: FileMetadata): Uint8Array {
  if (!metadata.fileName || !Number.isSafeInteger(metadata.originalSize) || metadata.originalSize < 0 || metadata.originalSha256.length !== 32) {
    throw new CryptoPipelineError("METADATA_INVALID", "File metadata fields are invalid");
  }
  const serialized: SerializedMetadata = {
    version: 1,
    fileName: metadata.fileName,
    mimeType: metadata.mimeType,
    originalSize: metadata.originalSize,
    originalSha256: toHex(metadata.originalSha256),
  };
  return encoder.encode(JSON.stringify(serialized));
}

export function decodeMetadata(input: Uint8Array): FileMetadata {
  try {
    const parsed = JSON.parse(decoder.decode(input)) as Partial<SerializedMetadata>;
    if (parsed.version !== 1 || typeof parsed.fileName !== "string" || !parsed.fileName || typeof parsed.mimeType !== "string" || !Number.isSafeInteger(parsed.originalSize) || (parsed.originalSize ?? -1) < 0 || typeof parsed.originalSha256 !== "string" || !/^[0-9a-f]{64}$/i.test(parsed.originalSha256)) {
      throw new Error("Invalid metadata schema");
    }
    return { fileName: parsed.fileName, mimeType: parsed.mimeType, originalSize: parsed.originalSize!, originalSha256: fromHex(parsed.originalSha256) };
  } catch (cause) {
    throw new CryptoPipelineError("METADATA_INVALID", "Encrypted metadata is not valid protocol metadata", { cause });
  }
}
