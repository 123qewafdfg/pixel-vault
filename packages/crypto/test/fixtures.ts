import { crc32, PNG_SIGNATURE } from "@trustmark-share/protocol";

export const bytes = (length: number, start = 0): Uint8Array => Uint8Array.from({ length }, (_, index) => (start + index) & 0xff);

function chunk(type: string): Uint8Array {
  const output = new Uint8Array(12);
  const typeBytes = new TextEncoder().encode(type);
  output.set(typeBytes, 4);
  new DataView(output.buffer).setUint32(8, crc32(typeBytes), false);
  return output;
}

export const TEST_PNG = (() => {
  const ihdr = chunk("IHDR");
  const iend = chunk("IEND");
  const output = new Uint8Array(PNG_SIGNATURE.length + ihdr.length + iend.length);
  output.set(PNG_SIGNATURE);
  output.set(ihdr, PNG_SIGNATURE.length);
  output.set(iend, PNG_SIGNATURE.length + ihdr.length);
  return output;
})();
