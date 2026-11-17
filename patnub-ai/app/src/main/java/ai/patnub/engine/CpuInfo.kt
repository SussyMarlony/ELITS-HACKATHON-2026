package ai.patnub.engine

import java.io.File

/** Picks the thread count for inference from the phone's CPU layout. */
object CpuInfo {
    /**
     * Counts only the fast ("big" and "prime") cores. Little cores slow every matrix multiply
     * barrier down to their pace, so leaving them out is faster on heterogeneous ARM SoCs.
     */
    fun performanceCores(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        val capacities = readSysfs(n, "cpu_capacity")
        if (capacities.isNotEmpty()) return pickByCapacity(capacities, n)
        return pickByFreq(readSysfs(n, "cpufreq/cpuinfo_max_freq"), n)
    }

    private fun readSysfs(processors: Int, suffix: String): List<Long> =
        (0 until processors).mapNotNull { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/$suffix").readText().trim().toLong() }.getOrNull()
        }

    /** Capacity values (0..1024) account for core IPC too; e.g. Cortex-A55 is ~439 vs A78 1024. */
    fun pickByCapacity(capacities: List<Long>, processors: Int): Int {
        if (capacities.isEmpty()) return processors.coerceIn(2, 4)
        val top = capacities.max()
        return capacities.count { it >= top * 7 / 10 }.coerceIn(2, 6)
    }

    /** Frequency fallback when cpu_capacity is absent: exclude the slowest cluster if heterogeneous. */
    fun pick(maxFreqs: List<Long>, processors: Int): Int = pickByFreq(maxFreqs, processors)

    fun pickByFreq(maxFreqs: List<Long>, processors: Int): Int {
        if (maxFreqs.isEmpty()) return processors.coerceIn(2, 4)
        val min = maxFreqs.min()
        val top = maxFreqs.max()
        if (min == top) return processors.coerceIn(2, 4)
        return maxFreqs.count { it > min }.coerceIn(2, 6)
    }
}
