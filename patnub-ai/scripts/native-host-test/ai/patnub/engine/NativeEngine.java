package ai.patnub.engine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Desktop stand-in for the app's NativeEngine with the same JNI signatures, so the real
 * bridge (app/src/main/cpp/patnub_llm.cpp) can run real models on a PC.
 * Usage: java ai.patnub.engine.NativeEngine <model.gguf> <mmproj.gguf|-> <streamed 0|1> <prompt> [image|-] [maxTokens]
 */
public class NativeEngine {
    public interface TokenSink { boolean onToken(byte[] bytes); }

    static native long nativeLoad(String model, String vision, boolean streamed, long budget, int ctxTokens, int threads, int readThreads, boolean prefetch);
    static native int nativeGenerate(long handle, String prompt, String image, boolean raw, int maxTokens, float temperature, int seed, TokenSink sink);
    static native int nativeFamily(long handle);
    static native void nativeCancel(long handle);
    static native String nativeLastError(long handle);
    static native boolean nativeSupportsImages(long handle);
    static native long[] nativeStats(long handle);
    static native void nativeFree(long handle);

    public static void main(String[] a) {
        System.loadLibrary("patnub_llm");
        String vision = a[1].equals("-") ? null : a[1];
        boolean streamed = a[2].equals("1");
        String image = a.length > 4 && !a[4].equals("-") ? a[4] : null;
        int max = a.length > 5 ? Integer.parseInt(a[5]) : 96;
        boolean raw = a.length > 6 && a[6].equals("raw");
        float temp = a.length > 7 ? Float.parseFloat(a[7]) : 0f;

        long t0 = System.nanoTime();
        long h = nativeLoad(a[0], vision, streamed, streamed ? 1L << 30 : 0, 2048, 4, 4, true);
        System.err.printf("loaded in %.1f s, images=%s%n", (System.nanoTime() - t0) / 1e9, nativeSupportsImages(h));

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        System.err.println("family=" + nativeFamily(h));
        int rc = nativeGenerate(h, a[3], image, raw, max, temp, 42, bytes -> {
            all.writeBytes(bytes);
            return true;
        });
        System.out.println("ANSWER: " + all.toString(StandardCharsets.UTF_8).replace("\n", " / "));
        long[] s = nativeStats(h);
        System.err.printf("rc=%d err='%s' prompt=%d tok gen=%d tok ttft=%d ms gen=%d ms (%.2f tok/s) peakRssMiB=%d%n",
                rc, nativeLastError(h), s[0], s[1], s[2], s[4], s[4] > 0 ? s[1] * 1000.0 / s[4] : 0, s[10] / 1024);
        // Model weights are memory-mapped: RssFile pages can be dropped by the kernel under pressure,
        // RssAnon cannot. That split decides whether a small phone copes.
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("RssAnon") || line.startsWith("RssFile")) System.err.println(line.replaceAll("\\s+", " "));
            }
        } catch (java.io.IOException ignored) { }
        nativeFree(h);
        if (rc != 0) System.exit(rc);
    }
}
