package ai.patnub.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stand-in for `libmsr_engine` so the app can be built and demoed before the real library
 * is available. It never reads a model file. Replies come from [reply] and are streamed
 * one word at a time at the measured speed. Stats use Ravin's measured numbers for the
 * default 1 GiB configuration (`ravin/updates.md`, Phase 3 gate), so they are not
 * meaningful for other budgets.
 *
 * @param reply builds the full reply for a prompt. Override it to return, for example, the
 *   JSON a doctor-note extraction would produce.
 * @param loadMs simulated model load time.
 * @param promptTokensPerSecond measured prompt processing speed (15.15 tok/s).
 * @param generateTokensPerSecond measured generation speed (3.00 tok/s). Raise both for
 *   faster UI iteration.
 */
class FakeEngine(
    private val reply: (prompt: String) -> String = { DEFAULT_REPLY },
    private val loadMs: Long = 1_500,
    private val promptTokensPerSecond: Double = 15.15,
    private val generateTokensPerSecond: Double = 3.00,
) : Engine {
    override val isSimulated: Boolean = true

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var config: EngineConfig? = null
    @Volatile private var lastRun = LastRun()
    private val generating = AtomicBoolean(false)
    private val cancelRequested = MutableStateFlow(false)
    private val totalReadBytes = AtomicLong(0)
    private val thermal = FakeThermal()

    override suspend fun load(config: EngineConfig) {
        if (config.modelPath.isBlank() || config.budgetBytes <= 0 || config.contextTokens <= 0 ||
            config.threads <= 0 || config.readThreads <= 0
        ) {
            throw EngineException(EngineError.INVALID_ARGUMENT, "invalid engine config: $config")
        }
        val current = _state.value
        val canLoad = current == EngineState.Unloaded || current is EngineState.Failed
        if (!canLoad || !_state.compareAndSet(current, EngineState.Loading)) {
            throw EngineException(EngineError.ALREADY_LOADED)
        }
        try {
            delay(loadMs)
        } catch (e: CancellationException) {
            _state.value = EngineState.Unloaded
            throw e
        }
        this.config = config
        _state.value = EngineState.Ready
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
    ): Flow<String> = flow {
        val cfg = config
        val current = _state.value
        if (cfg == null || (current != EngineState.Ready && current != EngineState.Generating)) {
            throw EngineException(EngineError.NOT_LOADED)
        }
        if (prompt.isEmpty() || maxTokens <= 0 || temperature < 0f) {
            throw EngineException(EngineError.INVALID_ARGUMENT, "empty prompt, maxTokens <= 0 or temperature < 0")
        }
        val promptTokens = estimateTokens(prompt)
        if (promptTokens + maxTokens > cfg.contextTokens) {
            throw EngineException(
                EngineError.CONTEXT_ERROR,
                "~$promptTokens prompt tokens + $maxTokens new tokens exceed ${cfg.contextTokens}",
            )
        }
        if (!generating.compareAndSet(false, true)) throw EngineException(EngineError.BUSY)

        cancelRequested.value = false
        _state.value = EngineState.Generating
        val startedNanos = System.nanoTime()
        val promptMs = (promptTokens * 1000 / promptTokensPerSecond).toLong()
        val tokenMs = (1000 / generateTokensPerSecond).toLong()
        var generated = 0L
        var ttftMs = 0L
        try {
            totalReadBytes.addAndGet(STREAMED_BYTES_PER_PASS)
            if (!pause(promptMs)) return@flow
            for (piece in splitIntoPieces(reply(prompt)).take(maxTokens)) {
                if (!pause(tokenMs)) break
                if (generated == 0L) ttftMs = elapsedMs(startedNanos)
                emit(piece)
                generated++
                totalReadBytes.addAndGet(STREAMED_BYTES_PER_PASS)
                thermal.onToken()
            }
        } finally {
            lastRun = LastRun(
                promptTokens = promptTokens.toLong(),
                generatedTokens = generated,
                ttftMs = ttftMs,
                promptMs = promptMs,
                generateMs = max(0L, elapsedMs(startedNanos) - promptMs),
            )
            generating.set(false)
            // close() may have unloaded the engine mid-generation; keep that state.
            _state.compareAndSet(EngineState.Generating, EngineState.Ready)
        }
    }

    override fun cancel() {
        if (generating.get()) cancelRequested.value = true
    }

    override suspend fun stats(): EngineStats? {
        val cfg = config ?: return null
        val run = lastRun
        return EngineStats(
            promptTokens = run.promptTokens,
            generatedTokens = run.generatedTokens,
            ttftMs = run.ttftMs,
            promptMs = run.promptMs,
            generateMs = run.generateMs,
            budgetBytes = cfg.budgetBytes,
            residentWeightBytes = RESIDENT_WEIGHT_BYTES,
            peakWeightBytes = PEAK_WEIGHT_BYTES,
            streamedLayers = STREAMED_LAYERS,
            streamSlots = STREAM_SLOTS,
            prefetch = cfg.prefetch,
            readThreads = cfg.readThreads,
            readBytes = totalReadBytes.get(),
            peakVmHwmKb = PEAK_PROCESS_KB,
            peakVmRssKb = PEAK_PROCESS_KB,
            thermalC = thermal.currentC(),
        )
    }

    override fun close() {
        cancel()
        config = null
        _state.value = EngineState.Unloaded
    }

    /** Waits [ms]. Returns false if [cancel] was called during the wait. */
    private suspend fun pause(ms: Long): Boolean =
        withTimeoutOrNull(ms) { cancelRequested.first { it } } == null

    private data class LastRun(
        val promptTokens: Long = 0,
        val generatedTokens: Long = 0,
        val ttftMs: Long = 0,
        val promptMs: Long = 0,
        val generateMs: Long = 0,
    )

    /** Rough phone temperature: warms per generated token, cools toward idle when quiet. */
    private class FakeThermal {
        private var celsius = IDLE_C
        private var lastNanos = System.nanoTime()

        @Synchronized
        fun onToken() {
            coolDown()
            celsius = min(MAX_C, celsius + HEAT_PER_TOKEN_C)
        }

        @Synchronized
        fun currentC(): Int {
            coolDown()
            return celsius.roundToInt()
        }

        private fun coolDown() {
            val now = System.nanoTime()
            val seconds = (now - lastNanos) / 1e9
            lastNanos = now
            celsius = max(IDLE_C, celsius - seconds * COOL_PER_SECOND_C)
        }
    }

    private companion object {
        const val DEFAULT_REPLY =
            "[Simulated reply from FakeEngine, not MedGemma.] The on-device model is not " +
                "connected yet. This text exists to test streaming, cancelling and layout."

        // Measured on the Redmi Turbo 3, 1 GiB budget, 4 readers, prefetch on (ravin/updates.md).
        const val PEAK_WEIGHT_BYTES = 1_020_289_024L
        const val PEAK_PROCESS_KB = 1_285_939L // 1,255.8 MiB VmHWM
        const val STREAMED_LAYERS = 28
        const val STREAM_SLOTS = 2

        // MedGemma Q4_K_M layers are 54.2 MiB each (ravin/README.md).
        const val LAYER_BYTES = 56_832_819L

        // Approximation: peak weight bytes minus the two streaming slots.
        const val RESIDENT_WEIGHT_BYTES = PEAK_WEIGHT_BYTES - STREAM_SLOTS * LAYER_BYTES

        // Every forward pass re-reads all streamed layers from storage.
        const val STREAMED_BYTES_PER_PASS = STREAMED_LAYERS * LAYER_BYTES

        const val IDLE_C = 38.0
        const val MAX_C = 74.0 // highest temperature Ravin observed during A/B runs
        const val HEAT_PER_TOKEN_C = 0.4
        const val COOL_PER_SECOND_C = 0.1

        /** About four characters per token for English text. */
        fun estimateTokens(text: String): Int = max(1, (text.length + 3) / 4)

        /** Splits text into words, each keeping its trailing whitespace. */
        fun splitIntoPieces(text: String): Sequence<String> =
            Regex("""\S+\s*""").findAll(text).map { it.value }

        fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
    }
}
