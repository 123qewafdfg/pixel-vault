export interface TrustMarkModelDescriptor {
  readonly url: string;
  readonly byteLength: number;
  readonly sha256: string;
}

const MODEL_BASE_URL = "https://cai-watermark.adobe.net/watermarking/trustmark-models";

export const TRUSTMARK_Q_MODELS = {
  encoder: {
    url: `${MODEL_BASE_URL}/encoder_Q.onnx`,
    byteLength: 17_312_208,
    sha256: "19b3d1b25836130ffd78775a8f61539f993375d1823ef0e59ba5b8dffb4f892d",
  },
  decoder: {
    url: `${MODEL_BASE_URL}/decoder_Q.onnx`,
    byteLength: 47_401_222,
    sha256: "ee3268f057c9dabef680e169302f5973d0589feea86189ed229a896cc3aa88df",
  },
} as const satisfies Record<"encoder" | "decoder", TrustMarkModelDescriptor>;
