package ai.patnub.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** On-device model over JNI (`app/src/main/cpp/patnub_llm.cpp`). */
class NativeEngine : Engine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var handle = 0L
    @Volatile private var images = false
    private val generating = AtomicBoolean(false)

    override val supportsImages: Boolean get() = images

    @Volatile override var streamed: Boolean = false
        private set

    @Volatile override var chatFormat: ChatFormat? = null
        private set

    override suspend fun load(config: EngineConfig) {
        if (config.modelPath.isBlank()) throw EngineException(EngineError.INVALID_ARGUMENT, "model path is required")
        val current = _state.value
        if (!(current == EngineState.Unloaded || current is EngineState.Failed) || !_state.compareAndSet(current, EngineState.Loading)) {
            throw EngineException(EngineError.MODEL_ERROR, "model already loaded")
        }
        try {
            handle = withContext(Dispatchers.IO) {
                nativeLoad(
                    config.modelPath, config.visionPath.takeUnless { config.streamed }, config.streamed,
                    config.budgetBytes, config.contextTokens, config.threads, config.readThreads, config.prefetch,
                )
            }
            images = nativeSupportsImages(handle)
            streamed = config.streamed
            chatFormat = when (nativeFamily(handle)) {
                3 -> ChatFormat.GEMMA3
                4 -> ChatFormat.GEMMA4
                else -> null
            }
            _state.value = EngineState.Ready
        } catch (e: Throwable) {
            val err = EngineException(EngineError.MODEL_ERROR, e.message ?: "model failed to load")
            _state.value = EngineState.Failed(err)
            throw err
        }
    }

    override fun generate(prompt: String, maxTokens: Int, temperature: Float, seed: Int, imagePath: String?, raw: Boolean): Flow<String> = callbackFlow {
        if (maxTokens < 1) throw EngineException(EngineError.INVALID_ARGUMENT, "maxTokens must be at least 1")
        val h = handle
        if (h == 0L || _state.value !is EngineState.Ready) throw EngineException(EngineError.NOT_LOADED)
        if (!generating.compareAndSet(false, true)) throw EngineException(EngineError.BUSY)
        _state.value = EngineState.Generating

        // Pieces can split a multi-byte UTF-8 character; keep the tail until it completes.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val pending = ByteBuffer.allocate(64 * 1024)
        val sink = object : TokenSink {
            override fun onToken(bytes: ByteArray): Boolean {
                pending.put(bytes).flip()
                val out = CharBuffer.allocate(pending.remaining() + 8)
                decoder.decode(pending, out, false)
                pending.compact()
                out.flip()
                if (out.isNotEmpty()) trySend(out.toString())
                return !isClosedForSend
            }
        }
        val worker = thread(name = "patnub-llm", isDaemon = true) {
            val rc = nativeGenerate(h, prompt, imagePath, raw, maxTokens, temperature, seed, sink)
            generating.set(false)
            if (_state.value == EngineState.Generating) _state.value = EngineState.Ready
            when (rc) {
                0, 7 -> close() // done, or cancelled: the flow completes normally
                1 -> close(EngineException(EngineError.INVALID_ARGUMENT, nativeLastError(h)))
                else -> close(EngineException(EngineError.INTERNAL_ERROR, nativeLastError(h)))
            }
        }
        awaitClose { if (worker.isAlive) nativeCancel(h) }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        handle.takeIf { it != 0L }?.let(::nativeCancel)
    }

    override suspend fun stats(): EngineStats? {
        val h = handle.takeIf { it != 0L } ?: return null
        val v = nativeStats(h)
        return EngineStats(
            promptTokens = v[0], generatedTokens = v[1], ttftMs = v[2], promptMs = v[3], generateMs = v[4],
            budgetBytes = v[5], residentWeightBytes = v[6], peakWeightBytes = v[7], readBytes = v[8],
            streamedLayers = v[9].toInt(), streamSlots = 0, prefetch = v[9] > 0, readThreads = 0,
            peakVmHwmKb = v[10].takeIf { it >= 0 }, vmRssKb = v[11].takeIf { it >= 0 }, thermalC = v[12].toInt().takeIf { it >= 0 },
        )
    }

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeFree(h) // waits for a running generation to stop
        _state.value = EngineState.Unloaded
    }

    /** Called from native code on the generating thread. Return false to stop. */
    interface TokenSink {
        fun onToken(bytes: ByteArray): Boolean
    }

    companion object {
        init {
            System.loadLibrary("patnub_llm")
        }

        @JvmStatic private external fun nativeLoad(
            model: String, vision: String?, streamed: Boolean, budget: Long,
            ctxTokens: Int, threads: Int, readThreads: Int, prefetch: Boolean,
        ): Long
        @JvmStatic private external fun nativeGenerate(
            handle: Long, prompt: String, image: String?, raw: Boolean, maxTokens: Int, temperature: Float, seed: Int, sink: TokenSink,
        ): Int
        @JvmStatic private external fun nativeFamily(handle: Long): Int
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeLastError(handle: Long): String
        @JvmStatic private external fun nativeSupportsImages(handle: Long): Boolean
        @JvmStatic private external fun nativeStats(handle: Long): LongArray
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
