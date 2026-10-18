export const PROTOCOL_VERSION = 1;
export const TAIL_INDEX_SIZE = 112;
export const MAGIC_TEXT = "TMSHARE1";
export const MAGIC = new TextEncoder().encode(MAGIC_TEXT);
export const PNG_SIGNATURE = Uint8Array.of(137, 80, 78, 71, 13, 10, 26, 10);

export const OFFSETS = Object.freeze({
  magic: 0,
  version: 8,
  flags: 10,
  resourceId: 12,
  partIndex: 28,
  partCount: 30,
  cipherLength: 32,
  metadataLength: 40,
  originalPngLength: 44,
  dataIv: 52,
  metadataIv: 64,
  cipherSha256: 76,
  crc32: 108,
});

export const RESOURCE_ID_SIZE = 16;
export const GCM_IV_SIZE = 12;
export const SHA256_SIZE = 32;
