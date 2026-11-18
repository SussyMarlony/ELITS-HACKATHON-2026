package ai.patnub.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuFeaturesTest {
    private val dotprod = 1L shl 20
    private val fp16 = 1L shl 10
    private val i8mm = 1L shl 13 // in AT_HWCAP2

    @Test fun picksTheFastestBuildThePhoneCanRun() {
        // Helio P70 (A73 + A53): no dot product, so the ARMv8.0 build instead of no AI at all.
        assertEquals(CpuLevel.BASIC, CpuFeatures.choose(arm64 = true, hwcap = fp16, hwcap2 = 0))
        // Snapdragon 695 (A78 + A55): dot product, no i8mm.
        assertEquals(CpuLevel.STANDARD, CpuFeatures.choose(true, dotprod or fp16, 0))
        // Snapdragon 8 Gen 2 (X3/A715/A510): i8mm.
        assertEquals(CpuLevel.FAST, CpuFeatures.choose(true, dotprod or fp16, i8mm))
        // i8mm needs the fp16 build flags too; without fp16 stay on the standard build.
        assertEquals(CpuLevel.STANDARD, CpuFeatures.choose(true, dotprod, i8mm))
        // auxv unreadable: the build that runs anywhere.
        assertEquals(CpuLevel.BASIC, CpuFeatures.choose(true, 0, 0))
        // x86_64 emulator: only the standard build exists.
        assertEquals(CpuLevel.STANDARD, CpuFeatures.choose(arm64 = false, hwcap = 0, hwcap2 = 0))
    }
}
