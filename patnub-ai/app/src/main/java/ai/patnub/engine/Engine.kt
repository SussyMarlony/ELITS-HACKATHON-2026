package ai.patnub.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kotlin view of the on-device LLM runtime. [NativeEngine] implements it over JNI: streamed
 * through `libmsr_engine` (`msr_engine/native/include/msr/engine/engine.h`) on mid-tier phones,
 * or held in memory with llama.cpp + mtmd (images) where the phone has the RAM. One engine per
 * process and one generation at a time (`msr_engine/LIMITATION.md`, B3/B6).
 */
interface Engine : AutoCloseable {
    val state: StateFlow<EngineState>

    /** True once a model with a vision projector is loaded in memory. */
    val supportsImages: Boolean

    /** True when weights stream from storage (slower); callers keep answers shorter. */
    val streamed: Boolean

    /**
     * The loaded model's chat format when callers may send a fully formatted multi-turn prompt
     * (`raw = true` in [generate]); null when the engine formats prompts itself (streamed mode).
     */
    val chatFormat: ChatFormat?

    /**
     * `msr_engine_load`: opens the model and binds its weights to the bounded arena.
     * Load is single-shot per engine.
     *
     * Throws [EngineException] with [EngineError.INVALID_ARGUMENT] (blank path or negative
     * setting) or [EngineError.MODEL_ERROR] (load failed, or a model is already loaded).
     */
    suspend fun load(config: EngineConfig)

    /**
     * Emits text pieces as the model produces them. The prompt is wrapped in the model's chat
     * template by the engine. A [temperature] of 0 or less means greedy decoding; [seed] only
     * matters when sampling. [imagePath] attaches one photo; only when [supportsImages].
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
        imagePath: String? = null,
        raw: Boolean = false,
    ): Flow<String>

    /**
     * Processes [text] into the model's cache without generating, so prompts that start with it
     * (the fixed instructions) skip that work. Also warms the weights up after loading.
     */
    suspend fun prefill(text: String) {}

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
    /** Vision projector (mmproj). Only used by the in-memory runtime. */
    val visionPath: String? = null,
    /** Stream weights through msr_engine's bounded arena instead of holding them in memory. */
    val streamed: Boolean = true,
    val budgetBytes: Long = 1024L shl 20,
    val contextTokens: Int = 2048,
    val threads: Int = 4,
    val readThreads: Int = 4,
    /** Two-slot async layer prefetch. Off means serial refill. */
    val prefetch: Boolean = true,
    /** Allow CPU weight repacking for faster Q4 matmul on phones with plenty of RAM. */
    val repack: Boolean = false,
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
    /** Prompt tokens served from the cache instead of being processed again. */
    val reusedPromptTokens: Long = 0,
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

/** Chat turn markers for the model families we ship. */
enum class ChatFormat {
    /** MedGemma 1.5 (Gemma 3). An empty reasoning block makes it answer directly. */
    GEMMA3,
    GEMMA4;

    private companion object {
        const val GEMMA3_ACK = "Understood. I am patnub. I will reply directly to the patient."
    }

    /** Media marker mtmd replaces with the photo's tokens. */
    val mediaMarker: String get() = "<__media__>"

    /**
     * [system] then alternating turns (first is the patient), ending with an open model turn.
     * [lastHasImage] puts the photo marker at the start of the last patient turn.
     */
    /** The fixed start every prompt shares (instructions); cached once after the model loads. */
    fun prefix(system: String): String =
        if (this == GEMMA4) "<|turn>system\n$system<turn|>\n"
        else "<start_of_turn>user\n$system<end_of_turn>\n<start_of_turn>model\n$GEMMA3_ACK<end_of_turn>\n"

    fun format(system: String, turns: List<Pair<Boolean, String>>, lastHasImage: Boolean): String = buildString {
        val (open, close) = if (this@ChatFormat == GEMMA3) "<start_of_turn>" to "<end_of_turn>" else "<|turn>" to "<turn|>"
        // Gemma 3 has no system role. Merged into the patient's first message, MedGemma restates
        // the rules instead of answering; as an acknowledged opening exchange it follows them.
        append(prefix(system))
        turns.forEachIndexed { i, (fromPatient, text) ->
            val role = if (fromPatient) "user" else "model"
            var body = text
            if (i == turns.lastIndex && lastHasImage) body = "$mediaMarker\n$body"
            append("$open$role\n$body$close\n")
        }
        append("${open}model\n")
        if (this@ChatFormat == GEMMA3) append("<unused94>thought\n<unused95>")
    }
}
