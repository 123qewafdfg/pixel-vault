const POLYNOMIAL = 0xedb88320;

const TABLE = (() => {
  const table = new Uint32Array(256);
  for (let value = 0; value < 256; value += 1) {
    let crc = value;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc & 1) === 1 ? (crc >>> 1) ^ POLYNOMIAL : crc >>> 1;
    }
    table[value] = crc >>> 0;
  }
  return table;
})();

/** CRC-32/ISO-HDLC (the same reflected polynomial used by PNG and zlib). */
export function crc32(input: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of input) {
    crc = (crc >>> 8) ^ TABLE[(crc ^ byte) & 0xff]!;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
