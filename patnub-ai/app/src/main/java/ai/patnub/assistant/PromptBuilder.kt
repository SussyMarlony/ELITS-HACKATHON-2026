package ai.patnub.assistant

import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.engine.ChatFormat

/** One earlier message in the conversation. */
data class Turn(val fromPatient: Boolean, val text: String)

/**
 * Builds the prompt for the on-device model.
 *
 * Behaviour asked of the model: talk like a careful clinic nurse. Ask a few short questions when
 * the complaint is vague; once it knows enough, give possible common causes (never a diagnosis),
 * self-care, and clear signs to get care. Prefer the background notes; never change a medicine.
 *
 * Two shapes: [chat] sends real alternating turns in the model's own format (in-memory runtime);
 * [build] packs the conversation into one message for runtimes that format prompts themselves
 * (msr_engine streamed mode).
 */
object PromptBuilder {
    /** Reference text budget. Prompt speed is ~15 tok/s streamed, far faster in memory. */
    const val MAX_CONTEXT_CHARS = 1_400
    private const val MAX_HISTORY_CHARS = 900

    fun instructions(
        general: List<ScoredChunk>,
        records: List<ClinicalRecordEntity>,
        withImage: Boolean,
        tagalog: Boolean = false,
    ): String = buildString {
        appendLine("You are patnub, a kind health assistant for patients in the rural Philippines. You are not a doctor and you cannot diagnose.")
        appendLine("How to reply:")
        appendLine(
            if (tagalog) "- The patient writes in Tagalog or Taglish. Reply in simple Tagalog or Taglish, speaking directly to them."
            else "- The patient writes in English. Reply in simple English, speaking directly to them.",
        )
        appendLine("- If you do not yet know enough (how long, how bad, where exactly, injury or swelling, fever, other symptoms), ask 2 or 3 short questions first and stop.")
        appendLine("- When you know enough: give the most likely common causes, saying they are possibilities, then simple safe self-care, then the warning signs that mean they should see a clinician or go to the hospital.")
        appendLine("- Never tell them to start, stop or change a prescribed medicine or dose; send them to their clinician for that.")
        appendLine("- Do not say \"context\", \"notes\" or \"sources\". Keep it under 120 words. Only help with health questions.")
        appendLine("- Write only your own reply. Never write the patient's next message.")
        if (withImage) {
            appendLine("- The patient attached a photo. Say plainly what you can see. If it is a medicine package, read the name, strength and form exactly as printed. If unsure, say so. Do not diagnose from a photo.")
        }
        val ctx = StringBuilder()
        // The patient's own clinician record goes first so the length cap can never cut it off.
        records.forEach { r ->
            ctx.append("Their clinician's record (${r.encounterDate}): ")
            r.assessment?.let { ctx.append("Assessment: $it. ") }
            if (r.meds.isNotEmpty()) ctx.append("Medicines: ${r.meds.joinToString { "${it.name} ${it.dose} ${it.frequency}" }}. ")
            if (r.instructions.isNotEmpty()) ctx.append("Instructions: ${r.instructions.joinToString("; ")}. ")
            r.followUp?.let { ctx.append("Follow-up: $it.") }
            ctx.appendLine()
        }
        if (general.isNotEmpty()) {
            val perPassage = ((MAX_CONTEXT_CHARS - ctx.length) / general.size).coerceAtLeast(200)
            general.forEach { ctx.appendLine("Reference (${it.chunk.sourceTitle}): ${it.chunk.contentText.replace("\n\n", " ").take(perPassage)}") }
        }
        if (ctx.isNotEmpty()) {
            appendLine()
            appendLine("Background to use when it fits:")
            append(ctx.toString().take(MAX_CONTEXT_CHARS + 600))
        }
    }.trimEnd()

    /** Real multi-turn prompt in [format], ending with an open model turn. */
    fun chat(
        format: ChatFormat,
        question: String,
        general: List<ScoredChunk>,
        records: List<ClinicalRecordEntity>,
        history: List<Turn> = emptyList(),
        withImage: Boolean = false,
    ): String {
        val turns = (history.fitting() + Turn(true, question)).dropWhile { !it.fromPatient } // must start with the patient
        return format.format(instructions(general, records, withImage, Language.isTagalog(question)), turns.map { it.fromPatient to it.text }, withImage)
    }

    /** Whole conversation in one message, for runtimes that apply the chat template themselves. */
    fun build(
        question: String,
        general: List<ScoredChunk>,
        records: List<ClinicalRecordEntity>,
        history: List<Turn> = emptyList(),
        withImage: Boolean = false,
    ): String = buildString {
        appendLine(instructions(general, records, withImage, Language.isTagalog(question)))
        appendLine()
        appendLine("Conversation:")
        history.fitting().forEach { appendLine((if (it.fromPatient) "Patient: " else "patnub: ") + it.text) }
        appendLine("Patient: $question")
    }

    private fun List<Turn>.fitting(): List<Turn> {
        var used = 0
        return asReversed().takeWhile { used += it.text.length + 10; used <= MAX_HISTORY_CHARS }.asReversed()
    }
}
