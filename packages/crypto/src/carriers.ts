import { createCarrier, extractCarrier } from "@trustmark-share/protocol";
import { equalBytes } from "./bytes.ts";
import { CryptoPipelineError } from "./errors.ts";
import type { EncryptedMetadata, EncryptedPart, EncryptedResource } from "./resource.ts";

export function createResourceCarriers(resource: EncryptedResource, carrierPngs: readonly Uint8Array[]): Uint8Array[] {
  if (carrierPngs.length !== 1 && carrierPngs.length !== resource.parts.length) {
    throw new CryptoPipelineError("INVALID_RESOURCE", "Provide one shared carrier PNG or one PNG per part");
  }
  return resource.parts.map((part) => createCarrier(
    carrierPngs.length === 1 ? carrierPngs[0]! : carrierPngs[part.partIndex]!,
    part.ciphertext,
    part.partIndex === 0 ? resource.encryptedMetadata.ciphertext : new Uint8Array(),
    {
      flags: 0,
      resourceId: resource.resourceUuid,
      partIndex: part.partIndex,
      partCount: part.partCount,
      dataIv: part.iv,
      metadataIv: resource.encryptedMetadata.iv,
      cipherSha256: part.cipherSha256,
    },
  ));
}

export function extractResourceCarriers(carriers: readonly Uint8Array[]): { resourceUuid: Uint8Array; parts: EncryptedPart[]; encryptedMetadata: EncryptedMetadata } {
  if (carriers.length < 1) throw new CryptoPipelineError("MISSING_PART", "No carriers were provided");
  const parsed = carriers.map(extractCarrier);
  const resourceUuid = parsed[0]!.index.resourceId;
  if (parsed.some((item) => !equalBytes(item.index.resourceId, resourceUuid))) throw new CryptoPipelineError("MIXED_RESOURCE", "Carriers belong to different resources");
  const metadataCarrier = parsed.find((item) => item.index.partIndex === 0);
  if (!metadataCarrier || metadataCarrier.encryptedMetadata.length === 0) throw new CryptoPipelineError("METADATA_INVALID", "Part zero metadata is missing");
  return {
    resourceUuid,
    encryptedMetadata: { iv: metadataCarrier.index.metadataIv, ciphertext: metadataCarrier.encryptedMetadata },
    parts: parsed.map((item) => ({
      resourceUuid: item.index.resourceId,
      partIndex: item.index.partIndex,
      partCount: item.index.partCount,
      iv: item.index.dataIv,
      ciphertext: item.ciphertext,
      cipherSha256: item.index.cipherSha256,
    })),
  };
}
