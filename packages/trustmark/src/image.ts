export const TRUSTMARK_IMAGE_SIZE = 256;

export interface RgbaImage {
  readonly width: number;
  readonly height: number;
  readonly data: Uint8ClampedArray;
}

export function assertRgbaImage(image: RgbaImage): void {
  if (!Number.isInteger(image.width) || !Number.isInteger(image.height) || image.width <= 0 || image.height <= 0) {
    throw new RangeError("image dimensions must be positive integers");
  }
  if (image.data.length !== image.width * image.height * 4) {
    throw new RangeError("RGBA data length does not match image dimensions");
  }
}

function sampleChannel(
  data: ArrayLike<number>,
  width: number,
  height: number,
  x: number,
  y: number,
  channel: number,
  stride: number,
): number {
  const x0 = Math.max(0, Math.min(width - 1, Math.floor(x)));
  const y0 = Math.max(0, Math.min(height - 1, Math.floor(y)));
  const x1 = Math.min(width - 1, x0 + 1);
  const y1 = Math.min(height - 1, y0 + 1);
  const dx = x - Math.floor(x);
  const dy = y - Math.floor(y);
  const at = (px: number, py: number): number => data[(py * width + px) * stride + channel] ?? 0;
  const top = at(x0, y0) * (1 - dx) + at(x1, y0) * dx;
  const bottom = at(x0, y1) * (1 - dx) + at(x1, y1) * dx;
  return top * (1 - dy) + bottom * dy;
}

export function rgbaToNormalizedChw(
  image: RgbaImage,
  targetWidth = TRUSTMARK_IMAGE_SIZE,
  targetHeight = TRUSTMARK_IMAGE_SIZE,
): Float32Array {
  assertRgbaImage(image);
  const plane = targetWidth * targetHeight;
  const result = new Float32Array(plane * 3);
  for (let y = 0; y < targetHeight; y += 1) {
    const sourceY = ((y + 0.5) * image.height) / targetHeight - 0.5;
    for (let x = 0; x < targetWidth; x += 1) {
      const sourceX = ((x + 0.5) * image.width) / targetWidth - 0.5;
      const offset = y * targetWidth + x;
      for (let channel = 0; channel < 3; channel += 1) {
        const value = sampleChannel(image.data, image.width, image.height, sourceX, sourceY, channel, 4);
        result[channel * plane + offset] = value / 127.5 - 1;
      }
    }
  }
  return result;
}

export function rgbaToUnitChw(
  image: RgbaImage,
  targetWidth = TRUSTMARK_IMAGE_SIZE,
  targetHeight = TRUSTMARK_IMAGE_SIZE,
): Float32Array {
  assertRgbaImage(image);
  const plane = targetWidth * targetHeight;
  const result = new Float32Array(plane * 3);
  for (let y = 0; y < targetHeight; y += 1) {
    const sourceY = ((y + 0.5) * image.height) / targetHeight - 0.5;
    for (let x = 0; x < targetWidth; x += 1) {
      const sourceX = ((x + 0.5) * image.width) / targetWidth - 0.5;
      const offset = y * targetWidth + x;
      for (let channel = 0; channel < 3; channel += 1) {
        const value = sampleChannel(image.data, image.width, image.height, sourceX, sourceY, channel, 4);
        result[channel * plane + offset] = value / 255;
      }
    }
  }
  return result;
}

export function applyEncoderResidual(
  original: RgbaImage,
  coverChw: Float32Array,
  encodedChw: Float32Array,
): RgbaImage {
  assertRgbaImage(original);
  const modelPlane = TRUSTMARK_IMAGE_SIZE * TRUSTMARK_IMAGE_SIZE;
  if (coverChw.length !== modelPlane * 3 || encodedChw.length !== modelPlane * 3) {
    throw new RangeError("encoder tensors must have shape [1, 3, 256, 256]");
  }

  const residual = new Float32Array(encodedChw.length);
  for (let channel = 0; channel < 3; channel += 1) {
    let sum = 0;
    const channelOffset = channel * modelPlane;
    for (let index = 0; index < modelPlane; index += 1) {
      const encoded = Math.max(-1, Math.min(1, encodedChw[channelOffset + index] ?? 0));
      const value = encoded - (coverChw[channelOffset + index] ?? 0);
      residual[channelOffset + index] = value;
      sum += value;
    }
    const mean = sum / modelPlane;
    for (let index = 0; index < modelPlane; index += 1) {
      residual[channelOffset + index] = (residual[channelOffset + index] ?? 0) - mean;
    }
  }

  const output = new Uint8ClampedArray(original.data);
  for (let y = 0; y < original.height; y += 1) {
    const sourceY = ((y + 0.5) * TRUSTMARK_IMAGE_SIZE) / original.height - 0.5;
    for (let x = 0; x < original.width; x += 1) {
      const sourceX = ((x + 0.5) * TRUSTMARK_IMAGE_SIZE) / original.width - 0.5;
      const pixelOffset = (y * original.width + x) * 4;
      for (let channel = 0; channel < 3; channel += 1) {
        const delta = sampleChannel(
          residual.subarray(channel * modelPlane, (channel + 1) * modelPlane),
          TRUSTMARK_IMAGE_SIZE,
          TRUSTMARK_IMAGE_SIZE,
          sourceX,
          sourceY,
          0,
          1,
        );
        const normalized = (original.data[pixelOffset + channel] ?? 0) / 127.5 - 1;
        output[pixelOffset + channel] = Math.round((Math.max(-1, Math.min(1, normalized + delta)) + 1) * 127.5);
      }
    }
  }
  return { width: original.width, height: original.height, data: output };
}
