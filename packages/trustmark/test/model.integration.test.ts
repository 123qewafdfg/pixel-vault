import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { TrustMarkRuntime } from "../src/runtime.ts";

const modelDirectory = process.env.TRUSTMARK_MODEL_DIR;

test("official Q models embed and recover a four-byte ID", { skip: modelDirectory === undefined, timeout: 120_000 }, async () => {
  const [encoderBytes, decoderBytes] = await Promise.all([
    readFile(path.join(modelDirectory!, "encoder_Q.onnx")),
    readFile(path.join(modelDirectory!, "decoder_Q.onnx")),
  ]);
  const runtime = await TrustMarkRuntime.load({
    encoderBytes,
    decoderBytes,
    executionProviders: ["wasm"],
  });
  try {
    const width = 512;
    const height = 384;
    const data = new Uint8ClampedArray(width * height * 4);
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const offset = (y * width + x) * 4;
        data[offset] = (x * 255) / width;
        data[offset + 1] = (y * 255) / height;
        data[offset + 2] = ((x + y) * 255) / (width + height);
        data[offset + 3] = 255;
      }
    }
    const id = Uint8Array.of(0x12, 0x34, 0x56, 0x78);
    const encoded = await runtime.embedTrustMarkId({ width, height, data }, id);
    assert.equal(encoded.width, width);
    assert.equal(encoded.height, height);
    assert.deepEqual((await runtime.extractTrustMarkId(encoded)).id, id);
  } finally {
    await runtime.release();
  }
});
