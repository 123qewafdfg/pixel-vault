import assert from "node:assert/strict";
import test from "node:test";

import { buildServer } from "../src/app.ts";
import { ResourceRepository } from "../src/database.ts";
import { decodeResourceId } from "../src/encoding.ts";
import { resourceInput, testConfig } from "./helpers.ts";

test("health check does not expose configuration", async () => {
  const repository = new ResourceRepository(":memory:");
  const app = await buildServer({ config: testConfig(), repository, logger: false });
  try {
    const response = await app.inject({ method: "GET", url: "/api/v1/health" });
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { status: "ok" });
  } finally {
    await app.close();
    repository.close();
  }
});

test("creates and recovers a resource without storing its plaintext file key", async () => {
  const repository = new ResourceRepository(":memory:", () => Uint8Array.of(0x12, 0x34, 0x56, 0x78));
  const app = await buildServer({ config: testConfig(), repository, logger: false });
  const input = resourceInput();
  try {
    const created = await app.inject({
      method: "POST", url: "/api/v1/resources", headers: { origin: "https://app.example" }, payload: input,
    });
    assert.equal(created.statusCode, 201);
    assert.deepEqual(created.json(), { id: "12345678" });
    const row = repository.database.prepare("SELECT encrypted_file_key FROM resources").get() as { encrypted_file_key: Uint8Array };
    assert.notDeepEqual(Buffer.from(row.encrypted_file_key), Buffer.from(input.fileKey, "base64url"));

    const recovered = await app.inject({ method: "GET", url: "/api/v1/resources/12345678" });
    assert.equal(recovered.statusCode, 200);
    assert.equal(recovered.json().fileKey, input.fileKey);
    assert.deepEqual(recovered.json().parts, input.parts);
  } finally {
    await app.close();
    repository.close();
  }
});

test("creates and recovers encrypted inline text without remote parts", async () => {
  const repository = new ResourceRepository(":memory:", () => Uint8Array.of(0xca, 0xfe, 0xba, 0xbe));
  const app = await buildServer({ config: testConfig(), repository, logger: false });
  const fileKey = Buffer.alloc(32, 9).toString("base64url");
  const encryptedMetadata = Buffer.alloc(12 + 1 + 16, 5).toString("base64url");
  const input = {
    protocolVersion: 2,
    fileKey,
    encryptedMetadata,
    originalSha256: "12".repeat(32),
    parts: [],
  };
  try {
    const created = await app.inject({ method: "POST", url: "/api/v1/resources", payload: input });
    assert.equal(created.statusCode, 201);
    assert.deepEqual(created.json(), { id: "cafebabe" });
    const row = repository.database.prepare("SELECT encrypted_file_key, part_count FROM resources").get() as {
      encrypted_file_key: Uint8Array; part_count: number;
    };
    assert.notDeepEqual(Buffer.from(row.encrypted_file_key), Buffer.from(fileKey, "base64url"));
    assert.equal(row.part_count, 1);

    const recovered = await app.inject({ method: "GET", url: "/api/v1/resources/cafebabe" });
    assert.equal(recovered.statusCode, 200);
    assert.equal(recovered.json().protocolVersion, 2);
    assert.equal(recovered.json().encryptedMetadata, encryptedMetadata);
    assert.deepEqual(recovered.json().parts, []);
  } finally {
    await app.close();
    repository.close();
  }
});

test("rejects invalid parts and disallowed CORS origins", async () => {
  const repository = new ResourceRepository(":memory:");
  const app = await buildServer({ config: testConfig(), repository, logger: false });
  try {
    const invalid = resourceInput();
    invalid.parts[1]!.partIndex = 0;
    const badRequest = await app.inject({ method: "POST", url: "/api/v1/resources", payload: invalid });
    assert.equal(badRequest.statusCode, 400);
    const cors = await app.inject({ method: "GET", url: "/api/v1/health", headers: { origin: "https://evil.example" } });
    assert.equal(cors.statusCode, 403);
    assert.equal(cors.headers["access-control-allow-origin"], undefined);
  } finally {
    await app.close();
    repository.close();
  }
});

test("enforces query rate limits and request body limits", async () => {
  const repository = new ResourceRepository(":memory:");
  const app = await buildServer({
    config: testConfig({ queryRateLimit: 1, globalRateLimit: 100, bodyLimit: 128 }),
    repository,
    logger: false,
  });
  try {
    const first = await app.inject({ method: "GET", url: "/api/v1/resources/ffffffff" });
    const second = await app.inject({ method: "GET", url: "/api/v1/resources/ffffffff" });
    assert.equal(first.statusCode, 404);
    assert.equal(second.statusCode, 429);
    assert.equal(second.json().error, "rate_limited");

    const oversized = await app.inject({ method: "POST", url: "/api/v1/resources", payload: resourceInput() });
    assert.equal(oversized.statusCode, 413);
    assert.equal(oversized.json().error, "invalid_request");
  } finally {
    await app.close();
    repository.close();
  }
});

test("returns the same response for missing and disabled resources", async () => {
  const repository = new ResourceRepository(":memory:", () => Uint8Array.of(1, 2, 3, 4));
  const app = await buildServer({ config: testConfig(), repository, logger: false });
  try {
    await app.inject({ method: "POST", url: "/api/v1/resources", payload: resourceInput(1) });
    repository.setStatus(decodeResourceId("01020304"), "disabled");
    const disabled = await app.inject({ method: "GET", url: "/api/v1/resources/01020304" });
    const missing = await app.inject({ method: "GET", url: "/api/v1/resources/ffffffff" });
    assert.equal(disabled.statusCode, 404);
    assert.equal(missing.statusCode, 404);
    assert.deepEqual(disabled.json(), missing.json());
  } finally {
    await app.close();
    repository.close();
  }
});
