export function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left[index]! ^ right[index]!;
  }
  return difference === 0;
}

export function toHex(input: Uint8Array): string {
  return Array.from(input, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function fromHex(input: string): Uint8Array {
  if (!/^[0-9a-f]*$/i.test(input) || input.length % 2 !== 0) {
    throw new TypeError("Invalid hexadecimal string");
  }
  return Uint8Array.from(input.match(/.{2}/g) ?? [], (pair) => Number.parseInt(pair, 16));
}

export function ownedBytes(input: Uint8Array): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(input);
}
