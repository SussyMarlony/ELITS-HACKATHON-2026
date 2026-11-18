package ai.patnub.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The CPU levels the native library is built for (app/src/main/cpp/CMakeLists.txt), slowest first. */
enum class CpuLevel(val library: String) {
    /** ARMv8.0: phones without dot product (Cortex-A53-only, A57/A72/A73). Works, slower. */
    BASIC("patnub_llm_armv8"),

    /** ARMv8.2 + dot product: most phones since ~2019. Also the only build for x86_64. */
    STANDARD("patnub_llm"),

    /** ARMv8.2 + dot product + fp16 + i8mm: 2021+ phones; faster matrix maths. */
    FAST("patnub_llm_i8mm"),
}

/** Reads the CPU feature bits the kernel reports, the same ones `getauxval(AT_HWCAP)` returns. */
object CpuFeatures {
    private const val AT_HWCAP = 16L
    private const val AT_HWCAP2 = 26L
    private const val HWCAP_ASIMDHP = 1L shl 10
    private const val HWCAP_ASIMDDP = 1L shl 20
    private const val HWCAP2_I8MM = 1L shl 13

    val level: CpuLevel by lazy {
        val arm64 = System.getProperty("os.arch") == "aarch64"
        val aux = if (arm64) readAuxv() else emptyMap()
        choose(arm64, aux[AT_HWCAP] ?: 0, aux[AT_HWCAP2] ?: 0)
    }

    /** Best level for these feature bits. Unknown bits (auxv unreadable) give BASIC, which runs anywhere. */
    fun choose(arm64: Boolean, hwcap: Long, hwcap2: Long): CpuLevel = when {
        !arm64 -> CpuLevel.STANDARD
        hwcap and HWCAP_ASIMDDP == 0L -> CpuLevel.BASIC
        hwcap and HWCAP_ASIMDHP != 0L && hwcap2 and HWCAP2_I8MM != 0L -> CpuLevel.FAST
        else -> CpuLevel.STANDARD
    }

    /** /proc/self/auxv on arm64: little-endian (key, value) pairs of 64-bit words. */
    private fun readAuxv(): Map<Long, Long> = runCatching {
        val buf = ByteBuffer.wrap(File("/proc/self/auxv").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        buildMap { while (buf.remaining() >= 16) put(buf.long, buf.long) }
    }.getOrDefault(emptyMap())
}
