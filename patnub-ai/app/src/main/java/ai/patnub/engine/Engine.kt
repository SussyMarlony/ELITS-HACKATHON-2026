package ai.patnub.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kotlin view of the on-device LLM runtime, `libmsr_engine`. Each member maps to one call
 * of its C API in `msr_engine/native/include/msr/engine/engine.h`. The runtime supports one
 * engine per process and one generation at a time (`msr_engine/LIMITATION.md`, B3/B6).
 *
 * [FakeEngine] implements this without a model, for emulators and UI work. The real
 * implementation calls the C API through JNI.
 */
interface Engine : AutoCloseable {
    /** True for [FakeEngine]. The UI must label simulated text and stats as simulated. */
    val isSimulated: Boolean

    val state: StateFlow<EngineState>

    /**
     * `msr_engine_load`: opens the model and binds its weights to the bounded arena.
     * Load is single-shot per engine.
     *
     * Throws [EngineException] with [EngineError.INVALID_ARGUMENT] (blank path or negative
     * setting) or [EngineError.MODEL_ERROR] (load failed, or a model is already loaded).
     */
    suspend fun load(config: EngineConfig)

    /**
     * `msr_engine_generate`: emits text pieces as the model produces them. The prompt is
     * wrapped in the model's chat template by the engine. A [temperature] of 0 or less
     * means greedy decoding; [seed] only matters when sampling.
     *
     * The flow completes normally when the reply ends, when [maxTokens] is reached, or
     * after [cancel] (`MSR_ABORTED`). Cancelling the collecting coroutine also stops
     * generation. Cancellation takes effect at the next token or prompt-chunk boundary.
     *
     * Throws [EngineException] with [EngineError.INVALID_ARGUMENT] (`maxTokens` < 1),
     * [EngineError.NOT_LOADED], [EngineError.BUSY], or [EngineError.INTERNAL_ERROR]
     * (including prompt + `maxTokens` exceeding the context window).
     */
    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float = 0f,
        seed: Int = 42,
    ): Flow<String>

    /** `msr_engine_cancel`: stops the running generation. Safe from any thread; no-op when idle. */
    fun cancel()

    /** `msr_engine_get_stats`. Null when no model is loaded. */
    suspend fun stats(): EngineStats?

    /**
     * `msr_engine_destroy`: unloads the model and frees the arena. Cancels any running
     * generation and waits for it to stop first, because the C engine must not be
     * destroyed during `generate`.
     */
    override fun close()
}

/**
 * Mirrors `msr_engine_config`. Defaults are the settings verified on the Redmi Turbo 3.
 * A value of 0 means "engine default": 2 GiB budget, 2048-token context, 4 compute
 * threads, 1 read thread.
 */
data class EngineConfig(
    val modelPath: String,
    val budgetBytes: Long = 1024L shl 20,
    val contextTokens: Int = 2048,
    val threads: Int = 4,
    val readThreads: Int = 4,
    /** Two-slot async layer prefetch. Off means serial refill. */
    val prefetch: Boolean = true,
)

/**
 * Mirrors `msr_engine_stats`. Token counts and timings describe the most recent
 * generation and reset when the next one starts. Weight and read fields come from the
 * streaming provider; [readBytes] is a running total since load.
 */
data class EngineStats(
    val promptTokens: Long,
    val generatedTokens: Long,
    val ttftMs: Long,
    val promptMs: Long,
    val generateMs: Long,
    val budgetBytes: Long,
    val residentWeightBytes: Long,
    val peakWeightBytes: Long,
    val readBytes: Long,
    val streamedLayers: Int,
    val streamSlots: Int,
    val prefetch: Boolean,
    val readThreads: Int,
    /** Whole-process peak memory (`VmHWM`). Null if the engine couldn't read it. */
    val peakVmHwmKb: Long?,
    /**
     * Whole-process current memory (`VmRSS`). The C field is named `peak_vm_rss_kb`,
     * but the engine reads the current value. Null if the engine couldn't read it.
     */
    val vmRssKb: Long?,
    /**
     * Hottest `/sys/class/thermal` zone in °C. Null if unreadable, which is common for
     * ordinary Android apps; fall back to Android's PowerManager thermal API there.
     */
    val thermalC: Int?,
) {
    /** Same formula as `msr-cli`. */
    val generateTokensPerSecond: Double
        get() = if (generateMs > 0) generatedTokens * 1000.0 / generateMs else 0.0
}

sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data object Ready : EngineState
    data object Generating : EngineState
    data class Failed(val error: EngineException) : EngineState
}

/**
 * Mirrors `msr_result`, minus `MSR_OK` and `MSR_ABORTED` (a cancelled generation completes
 * its flow normally). The engine currently never returns [IO_ERROR], [ALREADY_LOADED] or
 * [CONTEXT_ERROR]; those cases surface as [MODEL_ERROR] or [INTERNAL_ERROR] with a message.
 */
enum class EngineError {
    INVALID_ARGUMENT,
    NOT_LOADED,
    ALREADY_LOADED,
    IO_ERROR,
    MODEL_ERROR,
    CONTEXT_ERROR,
    INTERNAL_ERROR,

    /**
     * Kotlin-side: a generation is already running. The C engine would block instead.
     * Callers should fall back to deterministic logic (build plan Phase 2).
     */
    BUSY,
}

class EngineException(
    val error: EngineError,
    message: String? = null,
) : Exception(message ?: error.name)
