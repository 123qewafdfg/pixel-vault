import { RESOURCE_UUID_SIZE } from "./constants.ts";
import { CryptoPipelineError } from "./errors.ts";

const encoder = new TextEncoder();
const DATA_PREFIX = encoder.encode("TMSHARE1:DATA");
const METADATA_PREFIX = encoder.encode("TMSHARE1:META");

function assertUuid(resourceUuid: Uint8Array): void {
  if (resourceUuid.length !== RESOURCE_UUID_SIZE) {
    throw new CryptoPipelineError("INVALID_RESOURCE", "Resource UUID must be exactly 16 bytes");
  }
}

export function dataAad(resourceUuid: Uint8Array, partIndex: number, partCount: number): Uint8Array {
  assertUuid(resourceUuid);
  if (!Number.isInteger(partIndex) || !Number.isInteger(partCount) || partCount < 1 || partIndex < 0 || partIndex >= partCount || partCount > 0xffff) {
    throw new CryptoPipelineError("INVALID_RESOURCE", "Invalid part index or count");
  }
  const output = new Uint8Array(DATA_PREFIX.length + RESOURCE_UUID_SIZE + 4);
  output.set(DATA_PREFIX);
  output.set(resourceUuid, DATA_PREFIX.length);
  const view = new DataView(output.buffer);
  view.setUint16(DATA_PREFIX.length + RESOURCE_UUID_SIZE, partIndex, false);
  view.setUint16(DATA_PREFIX.length + RESOURCE_UUID_SIZE + 2, partCount, false);
  return output;
}

export function metadataAad(resourceUuid: Uint8Array): Uint8Array {
  assertUuid(resourceUuid);
  const output = new Uint8Array(METADATA_PREFIX.length + RESOURCE_UUID_SIZE);
  output.set(METADATA_PREFIX);
  output.set(resourceUuid, METADATA_PREFIX.length);
  return output;
}
