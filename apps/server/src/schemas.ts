export const createResourceSchema = {
  body: {
    type: "object",
    additionalProperties: false,
    required: ["protocolVersion", "fileKey", "encryptedMetadata", "originalSha256", "parts"],
    properties: {
      protocolVersion: { type: "integer", minimum: 0, maximum: 255 },
      fileKey: { type: "string", minLength: 43, maxLength: 43, pattern: "^[A-Za-z0-9_-]+$" },
      encryptedMetadata: { type: "string", minLength: 1, maxLength: 65_536, pattern: "^[A-Za-z0-9_-]+$" },
      originalSha256: { type: "string", pattern: "^[0-9a-fA-F]{64}$" },
      parts: {
        type: "array",
        minItems: 0,
        maxItems: 25,
        items: {
          type: "object",
          additionalProperties: false,
          required: ["partIndex", "downloadUrl", "remoteSha256", "cipherSha256", "cipherSize"],
          properties: {
            partIndex: { type: "integer", minimum: 0, maximum: 24 },
            downloadUrl: { type: "string", minLength: 9, maxLength: 4096, pattern: "^https://" },
            remoteSha256: { type: "string", pattern: "^[0-9a-fA-F]{64}$" },
            cipherSha256: { type: "string", pattern: "^[0-9a-fA-F]{64}$" },
            cipherSize: { type: "integer", minimum: 0, maximum: 20 * 1024 * 1024 + 16 },
          },
        },
      },
    },
  },
  response: {
    201: {
      type: "object",
      additionalProperties: false,
      required: ["id"],
      properties: { id: { type: "string", pattern: "^[0-9a-f]{8}$" } },
    },
  },
} as const;

export const resourceIdSchema = {
  params: {
    type: "object",
    additionalProperties: false,
    required: ["id"],
    properties: { id: { type: "string", pattern: "^[0-9a-fA-F]{8}$" } },
  },
} as const;
