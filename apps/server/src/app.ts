import cors from "@fastify/cors";
import rateLimit from "@fastify/rate-limit";
import Fastify, { LogController, type FastifyInstance } from "fastify";

import type { ServerConfig } from "./config.ts";
import { ResourceRepository } from "./database.ts";
import { decodeBase64Url, decodeResourceId, encodeBase64Url, encodeResourceId, parseSha256 } from "./encoding.ts";
import { KeyProtector } from "./key-protector.ts";
import { createResourceSchema, resourceIdSchema } from "./schemas.ts";
import type { CreateResourceInput, ResourceRecoveryResponse } from "./types.ts";

export interface BuildServerOptions {
  config: ServerConfig;
  repository?: ResourceRepository;
  logger?: boolean;
}

class InvalidRequestError extends Error {
  readonly statusCode = 400;
}

function validateParts(input: CreateResourceInput): void {
  if (input.protocolVersion === 2) {
    if (input.parts.length !== 0) throw new InvalidRequestError("inline text resources must not contain parts");
    const inlinePayload = decodeBase64Url(input.encryptedMetadata);
    if (inlinePayload.byteLength <= 28 || inlinePayload.byteLength > 1028) {
      throw new InvalidRequestError("inline text ciphertext has an invalid length");
    }
    return;
  }
  if (input.protocolVersion !== 1 || input.parts.length === 0) {
    throw new InvalidRequestError("file resources require protocol version 1 and at least one part");
  }
  const indices = input.parts.map((part) => part.partIndex).sort((a, b) => a - b);
  if (indices.some((value, index) => value !== index)) {
    throw new InvalidRequestError("part indices must be unique and contiguous from zero");
  }
  for (const part of input.parts) {
    const url = new URL(part.downloadUrl);
    if (url.protocol !== "https:" || url.username !== "" || url.password !== "") {
      throw new InvalidRequestError("download URLs must be HTTPS and contain no credentials");
    }
  }
}

export async function buildServer(options: BuildServerOptions): Promise<FastifyInstance> {
  const { config } = options;
  const repository = options.repository ?? new ResourceRepository(config.databasePath);
  const ownsRepository = options.repository === undefined;
  const protector = new KeyProtector(config.masterKey);
  const app = Fastify({
    logger: options.logger === false ? false : {
      redact: {
        paths: ["req.headers.authorization", "req.body.fileKey", "req.body.parts[*].downloadUrl", "res.body"],
        censor: "[REDACTED]",
      },
    },
    bodyLimit: config.bodyLimit,
    logController: new LogController({ disableRequestLogging: true }),
  });

  await app.register(cors, {
    origin(origin, callback) {
      if (origin === undefined || config.allowedOrigins.includes(origin)) callback(null, true);
      else callback(Object.assign(new Error("origin is not allowed"), { statusCode: 403 }), false);
    },
    methods: ["GET", "POST", "OPTIONS"],
  });
  await app.register(rateLimit, {
    global: true,
    max: config.globalRateLimit,
    timeWindow: "1 minute",
    keyGenerator: (request) => request.ip,
  });

  app.get("/api/v1/health", async () => ({ status: "ok" }));

  app.post<{ Body: CreateResourceInput }>("/api/v1/resources", { schema: createResourceSchema }, async (request, reply) => {
    const body = request.body;
    validateParts(body);
    const fileKey = decodeBase64Url(body.fileKey, 32);
    decodeBase64Url(body.encryptedMetadata);
    const protectedKey = protector.protect(fileKey);
    const id = repository.create({
      protocolVersion: body.protocolVersion,
      encryptedFileKey: protectedKey.encrypted,
      keyIv: protectedKey.iv,
      encryptedMetadata: body.encryptedMetadata,
      originalSha256: parseSha256(body.originalSha256),
      parts: body.parts.map((part) => ({
        ...part,
        remoteSha256: parseSha256(part.remoteSha256),
        cipherSha256: parseSha256(part.cipherSha256),
      })),
    });
    return reply.code(201).send({ id: encodeResourceId(id) });
  });

  app.get<{ Params: { id: string } }>("/api/v1/resources/:id", {
    schema: resourceIdSchema,
    config: { rateLimit: { max: config.queryRateLimit, timeWindow: "1 minute" } },
  }, async (request, reply) => {
    const id = decodeResourceId(request.params.id);
    const stored = repository.findActive(id);
    if (stored === null) {
      return reply.code(404).send({ error: "resource_unavailable", message: "Resource is unavailable" });
    }
    let fileKey: Uint8Array;
    try {
      fileKey = protector.unprotect(stored.encryptedFileKey, stored.keyIv);
    } catch {
      request.log.error({ code: "KEY_UNPROTECT_FAILED", resourceId: encodeResourceId(id) }, "resource key recovery failed");
      return reply.code(500).send({ error: "internal_error", message: "Internal server error" });
    }
    const response: ResourceRecoveryResponse = {
      id: encodeResourceId(stored.id),
      protocolVersion: stored.protocolVersion,
      fileKey: encodeBase64Url(fileKey),
      encryptedMetadata: stored.encryptedMetadata,
      originalSha256: stored.originalSha256,
      partCount: stored.partCount,
      parts: stored.parts,
    };
    return response;
  });

  app.setErrorHandler((error, request, reply) => {
    const candidate = typeof error === "object" && error !== null && "statusCode" in error
      ? Number(error.statusCode)
      : 500;
    const statusCode = candidate >= 400 && candidate < 500 ? candidate : 500;
    if (statusCode >= 500) request.log.error({ code: "REQUEST_FAILED" }, "request failed");
    const validation = statusCode >= 400 && statusCode < 500 && statusCode !== 429;
    void reply.code(statusCode).send({
      error: validation ? "invalid_request" : statusCode === 429 ? "rate_limited" : "internal_error",
      message: validation ? "Request validation failed" : statusCode === 429 ? "Too many requests" : "Internal server error",
    });
  });

  if (ownsRepository) app.addHook("onClose", async () => repository.close());
  return app;
}
