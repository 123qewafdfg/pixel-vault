#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>
#include "onnxruntime_cxx_api.h"

namespace {
constexpr int kModelSize = 256;
constexpr int kPlane = kModelSize * kModelSize;
constexpr int kPacketBits = 100;
std::mutex g_mutex;
std::unique_ptr<Ort::Env> g_env;
std::unique_ptr<Ort::Session> g_encoder;
std::unique_ptr<Ort::Session> g_decoder;

void throwJava(JNIEnv* env, const char* message) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  env->ThrowNew(cls, message);
}

std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray input) {
  const auto size = env->GetArrayLength(input);
  std::vector<uint8_t> output(static_cast<size_t>(size));
  env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(output.data()));
  return output;
}

float sample(const float* data, int width, int height, float x, float y) {
  const int x0 = std::clamp(static_cast<int>(std::floor(x)), 0, width - 1);
  const int y0 = std::clamp(static_cast<int>(std::floor(y)), 0, height - 1);
  const int x1 = std::min(width - 1, x0 + 1);
  const int y1 = std::min(height - 1, y0 + 1);
  const float dx = x - std::floor(x), dy = y - std::floor(y);
  const float top = data[y0 * width + x0] * (1 - dx) + data[y0 * width + x1] * dx;
  const float bottom = data[y1 * width + x0] * (1 - dx) + data[y1 * width + x1] * dx;
  return top * (1 - dy) + bottom * dy;
}

std::vector<float> normalize(const std::vector<uint8_t>& rgba, int width, int height) {
  if (width <= 0 || height <= 0 || rgba.size() != static_cast<size_t>(width * height * 4)) throw std::runtime_error("Invalid RGBA image");
  std::vector<float> chw(kPlane * 3);
  for (int y = 0; y < kModelSize; ++y) {
    const float sy = ((y + .5f) * height) / kModelSize - .5f;
    const int y0 = std::clamp(static_cast<int>(std::floor(sy)), 0, height - 1), y1 = std::min(height - 1, y0 + 1);
    const float dy = sy - std::floor(sy);
    for (int x = 0; x < kModelSize; ++x) {
      const float sx = ((x + .5f) * width) / kModelSize - .5f;
      const int x0 = std::clamp(static_cast<int>(std::floor(sx)), 0, width - 1), x1 = std::min(width - 1, x0 + 1);
      const float dx = sx - std::floor(sx);
      for (int c = 0; c < 3; ++c) {
        const auto at = [&](int px, int py) { return static_cast<float>(rgba[(py * width + px) * 4 + c]); };
        const float top = at(x0, y0) * (1 - dx) + at(x1, y0) * dx;
        const float bottom = at(x0, y1) * (1 - dx) + at(x1, y1) * dx;
        chw[c * kPlane + y * kModelSize + x] = (top * (1 - dy) + bottom * dy) / 127.5f - 1.f;
      }
    }
  }
  return chw;
}

std::vector<uint8_t> applyResidual(const std::vector<uint8_t>& rgba, int width, int height, const std::vector<float>& cover, const float* encoded) {
  std::vector<float> residual(kPlane * 3);
  for (int c = 0; c < 3; ++c) {
    float sum = 0;
    for (int i = 0; i < kPlane; ++i) { const float value = std::clamp(encoded[c * kPlane + i], -1.f, 1.f) - cover[c * kPlane + i]; residual[c * kPlane + i] = value; sum += value; }
    const float mean = sum / kPlane;
    for (int i = 0; i < kPlane; ++i) residual[c * kPlane + i] -= mean;
  }
  auto output = rgba;
  for (int y = 0; y < height; ++y) {
    const float sy = ((y + .5f) * kModelSize) / height - .5f;
    for (int x = 0; x < width; ++x) {
      const float sx = ((x + .5f) * kModelSize) / width - .5f;
      for (int c = 0; c < 3; ++c) {
        const float delta = sample(residual.data() + c * kPlane, kModelSize, kModelSize, sx, sy);
        const float original = rgba[(y * width + x) * 4 + c] / 127.5f - 1.f;
        output[(y * width + x) * 4 + c] = static_cast<uint8_t>(std::round((std::clamp(original + delta, -1.f, 1.f) + 1.f) * 127.5f));
      }
    }
  }
  return output;
}
}

extern "C" JNIEXPORT void JNICALL Java_com_trustmark_share_TrustMarkPlugin_nativeInit(JNIEnv* env, jobject, jbyteArray encoder, jbyteArray decoder) {
  try {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto encoderBytes = bytes(env, encoder), decoderBytes = bytes(env, decoder);
    if (g_decoder && (encoderBytes.empty() || g_encoder)) return;
    if (!g_env) g_env = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "TrustMark");
    Ort::SessionOptions options; options.SetIntraOpNumThreads(4); options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    if (!encoderBytes.empty() && !g_encoder) g_encoder = std::make_unique<Ort::Session>(*g_env, encoderBytes.data(), encoderBytes.size(), options);
    if (!g_decoder) g_decoder = std::make_unique<Ort::Session>(*g_env, decoderBytes.data(), decoderBytes.size(), options);
  } catch (const std::exception& error) { throwJava(env, error.what()); }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_trustmark_share_TrustMarkPlugin_nativeEmbed(JNIEnv* env, jobject, jbyteArray rgbaArray, jint width, jint height, jfloatArray packetArray) {
  try {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_encoder) throw std::runtime_error("TrustMark encoder is not initialized");
    auto rgba = bytes(env, rgbaArray); auto cover = normalize(rgba, width, height);
    if (env->GetArrayLength(packetArray) != kPacketBits) throw std::runtime_error("TrustMark packet must contain 100 values");
    std::vector<float> packet(kPacketBits); env->GetFloatArrayRegion(packetArray, 0, kPacketBits, packet.data());
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::array<int64_t, 4> imageShape{1, 3, kModelSize, kModelSize}; std::array<int64_t, 2> packetShape{1, kPacketBits};
    std::array<Ort::Value, 2> inputs{Ort::Value::CreateTensor<float>(memory, cover.data(), cover.size(), imageShape.data(), imageShape.size()), Ort::Value::CreateTensor<float>(memory, packet.data(), packet.size(), packetShape.data(), packetShape.size())};
    const char* inputNames[]{"onnx::Concat_0", "onnx::Gemm_1"}; const char* outputNames[]{"image"};
    auto outputs = g_encoder->Run(Ort::RunOptions{nullptr}, inputNames, inputs.data(), inputs.size(), outputNames, 1);
    auto result = applyResidual(rgba, width, height, cover, outputs[0].GetTensorData<float>());
    jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size())); env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte*>(result.data())); return output;
  } catch (const std::exception& error) { throwJava(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_trustmark_share_TrustMarkPlugin_nativeExtract(JNIEnv* env, jobject, jbyteArray rgbaArray, jint width, jint height) {
  try {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_decoder) throw std::runtime_error("TrustMark decoder is not initialized");
    auto input = normalize(bytes(env, rgbaArray), width, height);
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault); std::array<int64_t, 4> shape{1, 3, kModelSize, kModelSize};
    auto tensor = Ort::Value::CreateTensor<float>(memory, input.data(), input.size(), shape.data(), shape.size()); const char* inputNames[]{"image"}; const char* outputNames[]{"output"};
    auto outputs = g_decoder->Run(Ort::RunOptions{nullptr}, inputNames, &tensor, 1, outputNames, 1); const float* data = outputs[0].GetTensorData<float>();
    jfloatArray output = env->NewFloatArray(kPacketBits); env->SetFloatArrayRegion(output, 0, kPacketBits, data); return output;
  } catch (const std::exception& error) { throwJava(env, error.what()); return nullptr; }
}
