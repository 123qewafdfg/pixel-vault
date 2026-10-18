package com.trustmark.share;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Pure Android JNI bridge; deliberately has no Capacitor/WebView dependency. */
public final class TrustMarkPlugin {
    private static volatile boolean libraryLoaded;
    private static volatile Throwable libraryError;
    private final Context context;
    private final boolean decoderOnly;
    private volatile boolean initialized;
    private native void nativeInit(byte[] encoder, byte[] decoder);
    private native byte[] nativeEmbed(byte[] rgba, int width, int height, float[] packet);
    private native float[] nativeExtract(byte[] rgba, int width, int height);

    public TrustMarkPlugin(Context context) { this(context, false); }

    public TrustMarkPlugin(Context context, boolean decoderOnly) {
        this.context = context.getApplicationContext();
        this.decoderOnly = decoderOnly;
    }

    private static synchronized void ensureLibrary() {
        if (libraryLoaded) return;
        if (libraryError != null) {
            throw new IllegalStateException("TrustMark 原生引擎加载失败", libraryError);
        }
        try {
            System.loadLibrary("trustmark_native");
            libraryLoaded = true;
        } catch (Throwable error) {
            libraryError = error;
            throw new IllegalStateException("TrustMark 原生引擎加载失败", error);
        }
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream input = context.getAssets().open("models/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private synchronized void initialize() throws IOException {
        if (!initialized) {
            ensureLibrary();
            nativeInit(decoderOnly ? new byte[0] : asset("encoder_Q.onnx"), asset("decoder_Q.onnx"));
            initialized = true;
        }
    }

    private static byte[] rgba(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] rgba = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgba[i * 4] = (byte) (p >> 16);
            rgba[i * 4 + 1] = (byte) (p >> 8);
            rgba[i * 4 + 2] = (byte) p;
            rgba[i * 4 + 3] = (byte) (p >> 24);
        }
        return rgba;
    }

    private static Bitmap bitmap(byte[] rgba, int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = ((rgba[i * 4 + 3] & 255) << 24) | ((rgba[i * 4] & 255) << 16)
                | ((rgba[i * 4 + 1] & 255) << 8) | (rgba[i * 4 + 2] & 255);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    public Bitmap embed(Bitmap source, float[] packet) throws IOException {
        if (packet.length != 100) throw new IllegalArgumentException("TrustMark packet必须为100位");
        initialize();
        Bitmap input = source.copy(Bitmap.Config.ARGB_8888, false);
        return bitmap(nativeEmbed(rgba(input), input.getWidth(), input.getHeight(), packet), input.getWidth(), input.getHeight());
    }

    public float[] extract(Bitmap source) throws IOException {
        initialize();
        Bitmap input = source.copy(Bitmap.Config.ARGB_8888, false);
        return nativeExtract(rgba(input), input.getWidth(), input.getHeight());
    }
}
