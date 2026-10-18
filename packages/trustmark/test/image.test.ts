import assert from "node:assert/strict";
import test from "node:test";

import { applyEncoderResidual, rgbaToNormalizedChw } from "../src/image.ts";

test("converts RGBA to normalized RGB CHW while ignoring alpha", () => {
  const tensor = rgbaToNormalizedChw({
    width: 1,
    height: 1,
    data: Uint8ClampedArray.of(0, 128, 255, 7),
  }, 1, 1);
  assert.equal(tensor.length, 3);
  assert.equal(tensor[0], -1);
  assert.ok(Math.abs((tensor[1] ?? 0) - (128 / 127.5 - 1)) < 1e-6);
  assert.equal(tensor[2], 1);
});

test("zero encoder residual preserves pixels and alpha", () => {
  const image = {
    width: 2,
    height: 1,
    data: Uint8ClampedArray.of(10, 20, 30, 40, 200, 210, 220, 230),
  };
  const cover = rgbaToNormalizedChw(image);
  const output = applyEncoderResidual(image, cover, cover.slice());
  assert.deepEqual(output, image);
});

test("rejects malformed RGBA buffers", () => {
  assert.throws(() => rgbaToNormalizedChw({ width: 2, height: 2, data: new Uint8ClampedArray(3) }));
});
