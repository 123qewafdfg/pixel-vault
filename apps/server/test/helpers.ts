import type { ServerConfig } from "../src/config.ts";
import type { CreateResourceInput } from "../src/types.ts";

export function testConfig(overrides: Partial<ServerConfig> = {}): ServerConfig {
  return {
    host: "127.0.0.1",
    port: 3000,
    databasePath: ":memory:",
    masterKey: Uint8Array.from({ length: 32 }, (_, index) => index),
    allowedOrigins: ["https://app.example"],
    bodyLimit: 262_144,
    globalRateLimit: 100,
    queryRateLimit: 30,
    ...overrides,
  };
}

export function resourceInput(parts = 2): CreateResourceInput {
  const hash = "ab".repeat(32);
  return {
    protocolVersion: 1,
    fileKey: Buffer.alloc(32, 7).toString("base64url"),
    encryptedMetadata: Buffer.from("encrypted metadata").toString("base64url"),
    originalSha256: "cd".repeat(32),
    parts: Array.from({ length: parts }, (_, partIndex) => ({
      partIndex,
      downloadUrl: `https://cdn.example/resource-${partIndex}.png`,
      remoteSha256: hash,
      cipherSha256: "ef".repeat(32),
      cipherSize: 1024 + partIndex,
    })),
  };
}
