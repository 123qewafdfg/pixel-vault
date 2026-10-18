import { BCH, BCH_Decode, BCH_Encode } from "./vendor/bch_ecc.js";

export const TRUSTMARK_PACKET_BITS = 100;
export const TRUSTMARK_ID_BYTES = 4;
export const BCH_SUPER_DATA_BITS = 40;
export const BCH_SUPER_ECC_BITS = 56;
export const TRUSTMARK_VERSION_BITS = 4;

const BCH_POLYNOMIAL = 137;
const BCH_CORRECTABLE_BITS = 8;

export class TrustMarkDataError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TrustMarkDataError";
  }
}

export interface DecodedTrustMarkId {
  id: Uint8Array;
  correctedBits: number;
}

interface TrustMarkSchema {
  dataBits: number;
  eccBits: number;
  correctableBits: number;
}

const TRUSTMARK_SCHEMAS: readonly TrustMarkSchema[] = [
  { dataBits: 40, eccBits: 56, correctableBits: 8 },
  { dataBits: 61, eccBits: 35, correctableBits: 5 },
  { dataBits: 68, eccBits: 28, correctableBits: 4 },
  { dataBits: 75, eccBits: 21, correctableBits: 3 },
];

function assertBit(value: number, index: number): asserts value is 0 | 1 {
  if (value !== 0 && value !== 1) {
    throw new TrustMarkDataError(`packet bit ${index} must be 0 or 1`);
  }
}

function bytesToBits(bytes: Uint8Array): boolean[] {
  const bits: boolean[] = [];
  for (const byte of bytes) {
    for (let shift = 7; shift >= 0; shift -= 1) {
      bits.push(((byte >>> shift) & 1) === 1);
    }
  }
  return bits;
}

function eccBytesToBits(bytes: readonly number[]): number[] {
  const bits: number[] = [];
  for (const byte of bytes) {
    for (let shift = 7; shift >= 0; shift -= 1) {
      bits.push((byte >>> shift) & 1);
    }
  }
  return bits;
}

function padBitsToByte(bits: readonly boolean[]): boolean[] {
  const padded = [...bits];
  while (padded.length % 8 !== 0) padded.push(false);
  return padded;
}

export function encodeTrustMarkId(id: Uint8Array): Uint8Array {
  if (id.byteLength !== TRUSTMARK_ID_BYTES) {
    throw new TrustMarkDataError("TrustMark ID must be exactly 4 bytes");
  }

  const payload = new Uint8Array(5);
  payload.set(id);
  const data = bytesToBits(payload);
  const engine = BCH(BCH_CORRECTABLE_BITS, BCH_POLYNOMIAL);
  const ecc = eccBytesToBits(BCH_Encode(engine, data)).slice(0, BCH_SUPER_ECC_BITS);
  const packet = new Uint8Array(TRUSTMARK_PACKET_BITS);
  packet.set(data.map(Number), 0);
  packet.set(ecc, BCH_SUPER_DATA_BITS);
  return packet;
}

export function decodeTrustMarkId(packet: ArrayLike<number>): DecodedTrustMarkId {
  if (packet.length !== TRUSTMARK_PACKET_BITS) {
    throw new TrustMarkDataError("TrustMark packet must contain exactly 100 bits");
  }

  const bits = Array.from(packet);
  bits.forEach(assertBit);
  const schemaVersion = ((bits[98] ?? 0) << 1) | (bits[99] ?? 0);
  const versions = [schemaVersion, ...[...TRUSTMARK_SCHEMAS.keys()].filter((version) => version !== schemaVersion)];
  let corrected: number[] | undefined;
  let correctedBits = -1;
  for (const version of versions) {
    const schema = TRUSTMARK_SCHEMAS[version];
    if (schema === undefined) continue;
    const data = padBitsToByte(bits.slice(0, schema.dataBits).map(Boolean));
    const ecc = padBitsToByte(bits.slice(schema.dataBits, schema.dataBits + schema.eccBits).map(Boolean));
    const decoded = BCH_Decode(BCH(schema.correctableBits, BCH_POLYNOMIAL), data, ecc);
    if (!decoded.valid || decoded.data_binary === undefined || decoded.bitflips < 0) continue;
    if (decoded.data_binary.length < schema.dataBits) continue;

    corrected = Array.from(decoded.data_binary.slice(0, schema.dataBits), (bit, index) => {
      const value = Number(bit);
      assertBit(value, index);
      return value;
    });
    correctedBits = decoded.bitflips;
    break;
  }
  if (corrected === undefined) {
    throw new TrustMarkDataError(`TrustMark schema ${schemaVersion} error correction failed`);
  }
  if (corrected.slice(32).some((bit) => bit !== 0)) {
    throw new TrustMarkDataError("TrustMark payload is not a compatible 4-byte resource ID");
  }

  const id = new Uint8Array(TRUSTMARK_ID_BYTES);
  for (let byteIndex = 0; byteIndex < TRUSTMARK_ID_BYTES; byteIndex += 1) {
    let value = 0;
    for (let bitIndex = 0; bitIndex < 8; bitIndex += 1) {
      value = (value << 1) | (corrected[byteIndex * 8 + bitIndex] ?? 0);
    }
    id[byteIndex] = value;
  }
  return { id, correctedBits };
}
