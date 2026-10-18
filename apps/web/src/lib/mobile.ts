import { Capacitor, registerPlugin } from "@capacitor/core";

interface NativeTrustMark {
  embed(options: { imageBase64: string; packet: number[] }): Promise<{ pngBase64: string }>;
  extract(options: { imageBase64: string }): Promise<{ packet: number[] }>;
  download(options: { url: string }): Promise<{ base64: string }>;
}
const native = registerPlugin<NativeTrustMark>("TrustMarkNative");

function base64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader(); reader.onerror = () => reject(reader.error); reader.onload = () => resolve(String(reader.result).split(",", 2)[1] ?? ""); reader.readAsDataURL(blob);
  });
}
function bytes(value: string, type: string): Blob {
  const binary = atob(value); const output = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) output[i] = binary.charCodeAt(i);
  return new Blob([output], { type });
}
export function hasNativeTrustMark(): boolean { return Capacitor.isNativePlatform(); }
export async function nativeEmbed(file: File, packet: Uint8Array): Promise<Blob> {
  const result = await native.embed({ imageBase64: await base64(file), packet: Array.from(packet) }); return bytes(result.pngBase64, "image/png");
}
export async function nativeExtract(file: File): Promise<Uint8Array> {
  const result = await native.extract({ imageBase64: await base64(file) }); return Uint8Array.from(result.packet);
}
export async function nativeDownload(url: string): Promise<Uint8Array> {
  const result = await native.download({ url }); const binary = atob(result.base64); const output = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) output[i] = binary.charCodeAt(i);
  return output;
}
