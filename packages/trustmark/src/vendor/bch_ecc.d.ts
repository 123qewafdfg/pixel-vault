export interface BchState {
  ECCstate: {
    t: number;
    ecc_bits: number;
    ecc_bytes: number;
  };
}

export interface BchDecodeResult {
  bitflips: number;
  data_ascii?: string;
  data_binary?: string;
  valid: boolean;
}

export function BCH(t: number, polynomial: number): BchState;
export function BCH_Encode(state: BchState, data: readonly boolean[]): number[];
export function BCH_Decode(
  state: BchState,
  data: readonly boolean[],
  ecc: readonly boolean[],
): BchDecodeResult;
