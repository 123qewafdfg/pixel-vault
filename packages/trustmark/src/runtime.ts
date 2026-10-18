import type * as Ort from "onnxruntime-web";

import { decodeTrustMarkId, encodeTrustMarkId, type DecodedTrustMarkId } from "./data-layer.ts";
import { applyEncoderResidual, rgbaToNormalizedChw, rgbaToUnitChw, type RgbaImage, TRUSTMARK_IMAGE_SIZE } from "./image.ts";
import { TRUSTMARK_Q_MODELS, type TrustMarkModelDescriptor } from "./model-manifest.ts";

type OrtModule = typeof Ort;

export interface TrustMarkLoadOptions {
  readonly fetch?: typeof globalThis.fetch;
  readonly executionProviders?: readonly ("webgpu" | "wasm")[];
  readonly mode?: "both" | "encoder" | "decoder";
  readonly encoderBytes?: Uint8Array;
  readonly decoderBytes?: Uint8Array;
}

export class TrustMarkModelError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TrustMarkModelError";
  }
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

async function verifyModel(bytes: Uint8Array, descriptor: TrustMarkModelDescriptor): Promise<void> {
  if (bytes.byteLength !== descriptor.byteLength) {
    throw new TrustMarkModelError(`model size mismatch: expected ${descriptor.byteLength}, received ${bytes.byteLength}`);
  }
  const digestInput = new Uint8Array(bytes.byteLength);
  digestInput.set(bytes);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", digestInput.buffer));
  if (hex(digest) !== descriptor.sha256) {
    throw new TrustMarkModelError("model SHA-256 mismatch");
  }
}

async function loadModel(
  descriptor: TrustMarkModelDescriptor,
  supplied: Uint8Array | undefined,
  fetcher: typeof globalThis.fetch,
): Promise<Uint8Array> {
  if (!descriptor.url.startsWith("https://")) {
    throw new TrustMarkModelError("model URL must use HTTPS");
  }
  let bytes = supplied;
  if (bytes === undefined) {
    const response = await fetcher(descriptor.url, { cache: "force-cache", credentials: "omit" });
    if (!response.ok) {
      throw new TrustMarkModelError(`model download failed with HTTP ${response.status}`);
    }
    bytes = new Uint8Array(await response.arrayBuffer());
  }
  await verifyModel(bytes, descriptor);
  return bytes;
}

async function loadOrt(preferWebGpu: boolean): Promise<OrtModule> {
  if (preferWebGpu && typeof navigator !== "undefined" && "gpu" in navigator) {
    return import("onnxruntime-web/webgpu");
  }
  return import("onnxruntime-web");
}

export class TrustMarkRuntime {
  private readonly ort: OrtModule;
  private readonly encoder?: Ort.InferenceSession;
  private readonly decoder?: Ort.InferenceSession;

  private constructor(ort: OrtModule, encoder?: Ort.InferenceSession, decoder?: Ort.InferenceSession) {
    this.ort = ort;
    this.encoder = encoder;
    this.decoder = decoder;
  }

  static async load(options: TrustMarkLoadOptions = {}): Promise<TrustMarkRuntime> {
    const providers = [...(options.executionProviders ?? ["webgpu", "wasm"])] as Ort.InferenceSession.ExecutionProviderConfig[];
    const ort = await loadOrt(providers.includes("webgpu"));
    const fetcher = options.fetch ?? globalThis.fetch;
    const mode = options.mode ?? "both";
    const needsEncoder = mode !== "decoder";
    const needsDecoder = mode !== "encoder";
    if (fetcher === undefined && (
      (needsEncoder && options.encoderBytes === undefined)
      || (needsDecoder && options.decoderBytes === undefined)
    )) {
      throw new TrustMarkModelError("fetch is unavailable and model bytes were not supplied");
    }
    const createSession = async (bytes: Uint8Array): Promise<Ort.InferenceSession> => {
      if (providers.includes("webgpu")) {
        try {
          return await ort.InferenceSession.create(bytes, { executionProviders: ["webgpu"] });
        } catch (error) {
          if (!providers.includes("wasm")) throw error;
        }
      }
      return ort.InferenceSession.create(bytes, { executionProviders: ["wasm"] });
    };
    let encoder: Ort.InferenceSession | undefined;
    let decoder: Ort.InferenceSession | undefined;
    if (needsEncoder) {
      const bytes = await loadModel(TRUSTMARK_Q_MODELS.encoder, options.encoderBytes, fetcher!);
      encoder = await createSession(bytes);
    }
    if (needsDecoder) {
      const bytes = await loadModel(TRUSTMARK_Q_MODELS.decoder, options.decoderBytes, fetcher!);
      decoder = await createSession(bytes);
    }
    return new TrustMarkRuntime(ort, encoder, decoder);
  }

  async embedTrustMarkId(image: RgbaImage, id: Uint8Array): Promise<RgbaImage> {
    if (this.encoder === undefined) throw new TrustMarkModelError("encoder model is not loaded");
    const cover = rgbaToNormalizedChw(image);
    const packet = encodeTrustMarkId(id);
    const feeds = {
      "onnx::Concat_0": new this.ort.Tensor("float32", cover, [1, 3, TRUSTMARK_IMAGE_SIZE, TRUSTMARK_IMAGE_SIZE]),
      "onnx::Gemm_1": new this.ort.Tensor("float32", Float32Array.from(packet), [1, 100]),
    };
    const result = await this.encoder.run(feeds);
    const tensor = result.image;
    if (tensor === undefined || !(tensor.data instanceof Float32Array)) {
      throw new TrustMarkModelError("encoder returned no float32 image tensor");
    }
    return applyEncoderResidual(image, cover, tensor.data);
  }

  async extractTrustMarkId(image: RgbaImage): Promise<DecodedTrustMarkId> {
    if (this.decoder === undefined) throw new TrustMarkModelError("decoder model is not loaded");
    const input = rgbaToUnitChw(image);
    const result = await this.decoder.run({
      image: new this.ort.Tensor("float32", input, [1, 3, TRUSTMARK_IMAGE_SIZE, TRUSTMARK_IMAGE_SIZE]),
    });
    const tensor = result.output;
    if (tensor === undefined || !(tensor.data instanceof Float32Array) || tensor.data.length !== 100) {
      throw new TrustMarkModelError("decoder returned an invalid output tensor");
    }
    return decodeTrustMarkId(Uint8Array.from(tensor.data, (value) => (value >= 0 ? 1 : 0)));
  }

  async release(): Promise<void> {
    await Promise.all([this.encoder?.release(), this.decoder?.release()]);
  }
}
