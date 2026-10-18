import assert from "node:assert/strict";
import test from "node:test";

import { loadConfig } from "../src/config.ts";

const validKey = Buffer.alloc(32, 42).toString("base64url");

test("loads a canonical 32-byte master key and origin allowlist", () => {
  const config = loadConfig({
    TRUSTMARK_MASTER_KEY: validKey,
    TRUSTMARK_ALLOWED_ORIGINS: "https://app.example,https://mobile.example",
    TRUSTMARK_DATABASE_PATH: "data/test.sqlite",
  });
  assert.equal(config.masterKey.byteLength, 32);
  assert.deepEqual(config.allowedOrigins, ["https://app.example", "https://mobile.example"]);
  assert.equal(config.databasePath, "data/test.sqlite");
});

test("rejects missing or malformed security configuration", () => {
  assert.throws(() => loadConfig({ TRUSTMARK_ALLOWED_ORIGINS: "https://app.example" }), /MASTER_KEY/);
  assert.throws(() => loadConfig({ TRUSTMARK_MASTER_KEY: "AA", TRUSTMARK_ALLOWED_ORIGINS: "https://app.example" }));
  assert.throws(() => loadConfig({ TRUSTMARK_MASTER_KEY: validKey, TRUSTMARK_ALLOWED_ORIGINS: "" }), /ALLOWED_ORIGINS/);
  assert.throws(() => loadConfig({ TRUSTMARK_MASTER_KEY: validKey, TRUSTMARK_ALLOWED_ORIGINS: "https://app.example/path" }), /invalid allowed origin/);
  assert.throws(() => loadConfig({ TRUSTMARK_MASTER_KEY: validKey, TRUSTMARK_ALLOWED_ORIGINS: "http://app.example" }), /invalid allowed origin/);
  assert.doesNotThrow(() => loadConfig({ TRUSTMARK_MASTER_KEY: validKey, TRUSTMARK_ALLOWED_ORIGINS: "http://localhost:5173" }));
});
