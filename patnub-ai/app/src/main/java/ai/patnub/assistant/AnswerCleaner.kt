package ai.patnub.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Small models sometimes add things the patient should not see: a leftover reasoning block
 * ("thought ..."), a speaker label ("**patnub:**"), or an invented next patient turn
 * ("Patient: ..."). This keeps only the reply itself. Pure, so it is unit-tested.
 */
object AnswerCleaner {
    private val nextTurn = Regex("""(?m)^\s*\**\s*(patient|pasyente|user)\s*\**\s*:""", RegexOption.IGNORE_CASE)
    private val speaker = Regex("""^\s*\**\s*patnub\s*\**\s*:\s*\**\s*""", RegexOption.IGNORE_CASE)
    private val thoughtBlock = Regex("""^\s*thought\b[\s\S]*?(\n\s*\n)(?=\S)""")

    /** Reasoning blocks by marker (MedGemma 1.5, Gemma 4). The native bridge passes the markers through. */
    private val reasoning = listOf("<unused94>" to "<unused95>", "<|channel>" to "<channel|>")
    private val specialTokens =
        Regex("""<unused\d+>|<\|?(turn|channel|tool_call|tool_response|tool)\|?>|<(start|end)_of_turn>|<eos>|<bos>""")

    /** Cleaned reply text and whether the model started writing another turn (generation can stop). */
    fun clean(raw: String): Pair<String, Boolean> {
        var t = raw
        for ((open, close) in reasoning) {
            while (true) {
                val i = t.indexOf(open)
                if (i < 0) break
                val j = t.indexOf(close, i + open.length)
                // Still reasoning: show what came before it, nothing after, until the block closes.
                t = if (j < 0) t.substring(0, i) else t.removeRange(i, j + close.length)
            }
        }
        t = t.replace(specialTokens, "")
        if (t.trimStart().startsWith("thought", ignoreCase = true)) {
            // Reasoning leaked: show nothing until it ends with a blank line, then drop it.
            t = thoughtBlock.find(t)?.let { t.substring(it.range.last + 1) } ?: return "" to false
        }
        val cut = nextTurn.find(t)
        val stop = cut != null
        if (cut != null) t = t.substring(0, cut.range.first)
        t = t.replace(speaker, "")
        return t.trimEnd() to stop
    }

    /** Applies [clean] to a token stream, emitting only new text and ending at an invented next turn. */
    fun Flow<String>.cleaned(): Flow<String> = flow {
        val raw = StringBuilder()
        var shown = 0
        try {
            collect { piece ->
                raw.append(piece)
                val (text, stop) = clean(raw.toString())
                if (text.length > shown) {
                    emit(text.substring(shown))
                    shown = text.length
                }
                if (stop) throw StopReply()
            }
        } catch (e: StopReply) {
            // Ending collection cancels the native generation.
        }
    }

    private class StopReply : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}
