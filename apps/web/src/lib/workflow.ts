import {
  createResourceCarriers, decryptResource, encryptResource, extractResourceCarriers,
  fromHex, sha256Bytes, toHex,
} from "@trustmark-share/crypto";
import { decodeTrustMarkId, encodeTrustMarkId, TrustMarkRuntime, type RgbaImage } from "@trustmark-share/trustmark";
import { hasNativeTrustMark, nativeDownload, nativeEmbed, nativeExtract } from "./mobile";

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:3000").replace(/\/$/, "");

export interface GeneratedCarrier {
  name: string; blob: Blob; remoteSha256: string; cipherSha256: string;
  cipherSize: number; partIndex: number;
}
export interface ResourceRecord {
  id: string; protocolVersion: number; fileKey: string; encryptedMetadata: string;
  originalSha256: string; partCount: number;
  parts: Array<{ partIndex: number; downloadUrl: string; remoteSha256: string; cipherSha256: string; cipherSize: number }>;
}
let encoderRuntimePromise: Promise<TrustMarkRuntime> | undefined;
let decoderRuntimePromise: Promise<TrustMarkRuntime> | undefined;

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(normalized + "=".repeat((4 - normalized.length % 4) % 4));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}
function apiError(status: number): Error {
  if (status === 404) return new Error("资源 ID 不存在或已经失效");
  if (status === 429) return new Error("请求过于频繁，请稍后再试");
  return new Error(`服务器请求失败（HTTP ${status}）`);
}
export function errorMessage(error: unknown): string {
  if (error instanceof TypeError && /fetch/i.test(error.message)) return "网络请求被浏览器拦截。若图床未开放 CORS，请先下载图片，再选择本地文件。";
  return error instanceof Error ? error.message : "发生未知错误";
}

export async function generateCarriers(file: File, carrierFiles: File[]) {
  if (carrierFiles.length === 0) throw new Error("请至少选择一张 PNG 承载图");
  const pngs = await Promise.all(carrierFiles.map(async (carrier) => {
    const bytes = new Uint8Array(await carrier.arrayBuffer());
    if (bytes[0] !== 0x89 || bytes[1] !== 0x50 || bytes[2] !== 0x4e || bytes[3] !== 0x47) throw new Error(`${carrier.name} 不是有效的 PNG 文件`);
    return bytes;
  }));
  const encrypted = await encryptResource(file, { fileName: file.name, mimeType: file.type });
  if (pngs.length !== 1 && pngs.length !== encrypted.parts.length) throw new Error(`该文件分为 ${encrypted.parts.length} 片：请选择 1 张重复使用，或恰好 ${encrypted.parts.length} 张 PNG`);
  const output = createResourceCarriers(encrypted, pngs);
  const carriers: GeneratedCarrier[] = output.map((bytes, index) => ({
    name: `hidden-part-${String(index + 1).padStart(2, "0")}.png`,
    blob: new Blob([Uint8Array.from(bytes)], { type: "image/png" }),
    remoteSha256: toHex(sha256Bytes(bytes)),
    cipherSha256: toHex(encrypted.parts[index]!.cipherSha256),
    cipherSize: encrypted.parts[index]!.ciphertext.byteLength, partIndex: index,
  }));
  return { encrypted, carriers };
}

export function downloadBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob); const anchor = document.createElement("a");
  anchor.href = url; anchor.download = name; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
export async function verifyUrls(carriers: GeneratedCarrier[], urls: string[]): Promise<void> {
  if (urls.length !== carriers.length || urls.some((url) => !url.trim())) throw new Error("请填写每一张承载图的公开直链");
  await Promise.all(urls.map(async (rawUrl, index) => {
    const url = new URL(rawUrl);
    if (url.protocol !== "https:" || url.username || url.password) throw new Error(`第 ${index + 1} 个链接必须是无账号信息的 HTTPS 地址`);
    const bytes = await downloadBytes(url.toString(), `第 ${index + 1} 个链接`);
    if (toHex(sha256Bytes(bytes)) !== carriers[index]!.remoteSha256) throw new Error(`第 ${index + 1} 张远程图片与生成文件不一致`);
  }));
}
async function downloadBytes(url: string, label: string): Promise<Uint8Array> {
  if (hasNativeTrustMark()) return nativeDownload(url);
  const response = await fetch(url, { credentials: "omit", cache: "no-store" });
  if (!response.ok) throw new Error(`${label}下载失败（HTTP ${response.status}）`);
  return new Uint8Array(await response.arrayBuffer());
}
export async function createRecord(encrypted: Awaited<ReturnType<typeof encryptResource>>, carriers: GeneratedCarrier[], urls: string[]): Promise<string> {
  const response = await fetch(`${API_BASE_URL}/api/v1/resources`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ protocolVersion: 1, fileKey: base64Url(encrypted.fileKey), encryptedMetadata: base64Url(encrypted.encryptedMetadata.ciphertext), originalSha256: toHex(encrypted.originalSha256), parts: carriers.map((carrier, index) => ({ partIndex: carrier.partIndex, downloadUrl: urls[index]!.trim(), remoteSha256: carrier.remoteSha256, cipherSha256: carrier.cipherSha256, cipherSize: carrier.cipherSize })) }),
  });
  if (!response.ok) throw apiError(response.status);
  const body = await response.json() as { id?: string };
  if (!body.id || !/^[0-9a-f]{8}$/i.test(body.id)) throw new Error("服务器返回了无效的资源 ID");
  return body.id.toLowerCase();
}

async function imageData(file: Blob): Promise<RgbaImage> {
  const bitmap = await createImageBitmap(file);
  try {
    const canvas = document.createElement("canvas"); canvas.width = bitmap.width; canvas.height = bitmap.height;
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) throw new Error("浏览器无法创建图片画布");
    context.drawImage(bitmap, 0, 0); const pixels = context.getImageData(0, 0, bitmap.width, bitmap.height);
    return { width: bitmap.width, height: bitmap.height, data: pixels.data };
  } finally { bitmap.close(); }
}
function rgbaPng(image: RgbaImage): Promise<Blob> {
  const canvas = document.createElement("canvas"); canvas.width = image.width; canvas.height = image.height;
  const context = canvas.getContext("2d"); if (!context) throw new Error("浏览器无法创建图片画布");
  context.putImageData(new ImageData(new Uint8ClampedArray(image.data), image.width, image.height), 0, 0);
  return new Promise((resolve, reject) => canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error("PNG 编码失败")), "image/png"));
}
async function encoderTrustMark(): Promise<TrustMarkRuntime> {
  encoderRuntimePromise ??= TrustMarkRuntime.load({ mode: "encoder" });
  try { return await encoderRuntimePromise; } catch (error) { encoderRuntimePromise = undefined; throw error; }
}
async function decoderTrustMark(): Promise<TrustMarkRuntime> {
  decoderRuntimePromise ??= TrustMarkRuntime.load({ mode: "decoder" });
  try { return await decoderRuntimePromise; } catch (error) { decoderRuntimePromise = undefined; throw error; }
}
export async function embedId(file: File, id: string): Promise<Blob> {
  const bytes = fromHex(id);
  if (hasNativeTrustMark()) return nativeEmbed(file, encodeTrustMarkId(bytes));
  return rgbaPng(await (await encoderTrustMark()).embedTrustMarkId(await imageData(file), bytes));
}
export async function extractId(file: File): Promise<string> {
  if (hasNativeTrustMark()) return toHex(decodeTrustMarkId(await nativeExtract(file)).id);
  return toHex((await (await decoderTrustMark()).extractTrustMarkId(await imageData(file))).id);
}
export async function getRecord(id: string): Promise<ResourceRecord> {
  const response = await fetch(`${API_BASE_URL}/api/v1/resources/${id}`, { credentials: "omit", cache: "no-store" });
  if (!response.ok) throw apiError(response.status); return response.json() as Promise<ResourceRecord>;
}
export async function restoreFromRemote(record: ResourceRecord): Promise<{ file: Blob; name: string }> {
  const bytes = await Promise.all(record.parts.map(async (part) => {
    const value = await downloadBytes(part.downloadUrl, `分片 ${part.partIndex + 1}`);
    if (toHex(sha256Bytes(value)) !== part.remoteSha256) throw new Error(`分片 ${part.partIndex + 1} 完整性校验失败`);
    return value;
  }));
  return restoreFromBytes(record, bytes);
}
export async function restoreFromLocal(record: ResourceRecord, files: File[]): Promise<{ file: Blob; name: string }> {
  return restoreFromBytes(record, await Promise.all(files.map(async (file) => new Uint8Array(await file.arrayBuffer()))));
}
async function restoreFromBytes(record: ResourceRecord, bytes: Uint8Array[]): Promise<{ file: Blob; name: string }> {
  const extracted = extractResourceCarriers(bytes);
  const result = await decryptResource(extracted.resourceUuid, decodeBase64Url(record.fileKey), extracted.parts, extracted.encryptedMetadata);
  if (toHex(result.metadata.originalSha256) !== record.originalSha256) throw new Error("服务器记录与恢复文件的 SHA-256 不一致");
  return { file: result.file, name: result.metadata.fileName };
}
