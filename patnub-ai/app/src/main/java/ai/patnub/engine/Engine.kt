package ai.patnub.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kotlin view of Ravin's on-device LLM runtime, `libmsr_engine`. Each member maps to one
 * call of its C API (see `ravin/sdk/tools/msr_cli.cpp`). The app holds one engine per
 * process, and the engine runs one generation at a time.
 *
 * [FakeEngine] implements this until the real library is available. The real
 * implementation will call the C API through JNI.
 */
interface Engine : AutoCloseable {
    /** True for [FakeEngine]. The UI must label simulated text and stats as simulated. */
    val isSimulated: Boolean

    val state: StateFlow<EngineState>

    /**
     * `msr_engine_load`: opens the model and sets up the bounded weight arena.
     * Throws [EngineException] with [EngineError.ALREADY_LOADED], [EngineError.INVALID_ARGUMENT],
     * [EngineError.IO_ERROR] or [EngineError.MODEL_ERROR].
     */
    suspend fun load(config: EngineConfig)

    /**
     * `msr_engine_generate`: emits text pieces as the model produces them.
     *
     * The flow completes normally when the reply ends, when [maxTokens] is reached, or
     * after [cancel]. Cancelling the collecting coroutine also stops generation.
     * Throws [EngineException] with [EngineError.NOT_LOADED], [EngineError.BUSY],
     * [EngineError.INVALID_ARGUMENT] or [EngineError.CONTEXT_ERROR].
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

    /** `msr_engine_destroy`: unloads the model and frees the arena. */
    override fun close()
}

/** Mirrors `msr_engine_config`. Defaults are the settings verified on the Redmi Turbo 3. */
data class EngineConfig(
    val modelPath: String,
    val budgetBytes: Long = 1024L shl 20,
    val contextTokens: Int = 2048,
    val threads: Int = 4,
    val readThreads: Int = 4,
    /** Two-slot async layer prefetch. */
    val prefetch: Boolean = true,
)

/**
 * Mirrors `msr_engine_stats`. Token counts and timings describe the most recent
 * generation; `readBytes` is cumulative. Confirm both assumptions against the C header.
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
    val streamedLayers: Int,
    val streamSlots: Int,
    val prefetch: Boolean,
    val readThreads: Int,
    val readBytes: Long,
    val peakVmHwmKb: Long,
    val peakVmRssKb: Long,
    val thermalC: Int,
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
 * Mirrors `msr_result`, minus `MSR_OK` and `MSR_ABORTED` (a cancelled generation
 * completes its flow normally). [BUSY] is added on the Kotlin side.
 */
enum class EngineError {
    INVALID_ARGUMENT,
    NOT_LOADED,
    ALREADY_LOADED,
    IO_ERROR,
    MODEL_ERROR,
    CONTEXT_ERROR,
    INTERNAL_ERROR,

    /** A generation is already running. Callers should fall back to deterministic logic. */
    BUSY,
}

class EngineException(
    val error: EngineError,
    message: String? = null,
) : Exception(message ?: error.name)
