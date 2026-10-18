import { decodeBase64Url } from "./encoding.ts";

export interface ServerConfig {
  host: string;
  port: number;
  databasePath: string;
  masterKey: Uint8Array;
  allowedOrigins: string[];
  bodyLimit: number;
  globalRateLimit: number;
  queryRateLimit: number;
}

function positiveInteger(value: string | undefined, fallback: number, name: string): number {
  const parsed = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error(`${name} must be a positive integer`);
  return parsed;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  if (env.TRUSTMARK_MASTER_KEY === undefined) {
    throw new Error("TRUSTMARK_MASTER_KEY is required (32-byte Base64URL)");
  }
  const allowedOrigins = (env.TRUSTMARK_ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);
  if (allowedOrigins.length === 0) throw new Error("TRUSTMARK_ALLOWED_ORIGINS must contain at least one origin");
  for (const origin of allowedOrigins) {
    const url = new URL(origin);
    const localHttp = url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
    if (url.origin !== origin || (url.protocol !== "https:" && !localHttp)) {
      throw new Error(`invalid allowed origin: ${origin}`);
    }
  }
  return {
    host: env.HOST ?? "0.0.0.0",
    port: positiveInteger(env.PORT, 3000, "PORT"),
    databasePath: env.TRUSTMARK_DATABASE_PATH ?? "trustmark-share.sqlite",
    masterKey: decodeBase64Url(env.TRUSTMARK_MASTER_KEY, 32),
    allowedOrigins,
    bodyLimit: positiveInteger(env.TRUSTMARK_BODY_LIMIT, 262_144, "TRUSTMARK_BODY_LIMIT"),
    globalRateLimit: positiveInteger(env.TRUSTMARK_GLOBAL_RATE_LIMIT, 120, "TRUSTMARK_GLOBAL_RATE_LIMIT"),
    queryRateLimit: positiveInteger(env.TRUSTMARK_QUERY_RATE_LIMIT, 30, "TRUSTMARK_QUERY_RATE_LIMIT"),
  };
}
