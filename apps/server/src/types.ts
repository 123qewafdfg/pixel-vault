export interface ResourcePartInput {
  partIndex: number;
  downloadUrl: string;
  remoteSha256: string;
  cipherSha256: string;
  cipherSize: number;
}

export interface CreateResourceInput {
  protocolVersion: number;
  fileKey: string;
  encryptedMetadata: string;
  originalSha256: string;
  parts: ResourcePartInput[];
}

export interface StoredResource {
  id: Uint8Array;
  protocolVersion: number;
  encryptedFileKey: Uint8Array;
  keyIv: Uint8Array;
  encryptedMetadata: string;
  originalSha256: string;
  partCount: number;
  status: "active" | "disabled";
  createdAt: string;
  parts: ResourcePartInput[];
}

export interface ResourceRecoveryResponse {
  id: string;
  protocolVersion: number;
  fileKey: string;
  encryptedMetadata: string;
  originalSha256: string;
  partCount: number;
  parts: ResourcePartInput[];
}
