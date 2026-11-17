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
 * Speed: [RULES] never change, and earlier turns are sent exactly as before, so the native cache
 * reuses everything up to the newest message. Per-question material (reference passages, the
 * reply language) rides in the newest message only.
 *
 * Two shapes: [chat] sends real alternating turns in the model's own format; [build] packs the
 * conversation into one message for models without a known chat format.
 */
object PromptBuilder {
    /** Reference text budget per question (~100 tokens); every token here costs time on the phone. */
    const val MAX_CONTEXT_CHARS = 450
    private const val MAX_HISTORY_CHARS = 900

    /** Fixed instructions. Changing this text invalidates every cached prompt. */
    val RULES = """
        You are patnub, a kind health assistant for rural Philippine patients. You are not a doctor and cannot diagnose.
        - Ask 2 or 3 short clarifying questions first if details are missing (duration, severity, location, injury/swelling, fever).
        - Once clear: list possible common causes, simple safe self-care, and warning signs to see a clinician or hospital.
        - Never tell patients to start, stop, or change prescribed medicines; refer to their clinician.
        - Keep under 120 words, in simple words. Only help with health questions. Never mention "notes" or "sources". Write only your reply.
    """.trimIndent()

    private const val PHOTO_RULE =
        "The patient attached a photo. Say plainly what you see. For a medicine pack, read the name, strength and form exactly as printed. Do not diagnose from a photo."

    /** Reference passages and the patient's own record for this question, or "" when none matched. */
    fun background(general: List<ScoredChunk>, records: List<ClinicalRecordEntity>): String {
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
        // Send one passage when the top score is clearly ahead; a second only on a near tie.
        val passages = general.take(1) + general.drop(1).filter { it.score >= general.first().score * 0.95 }
        if (passages.isNotEmpty()) {
            val perPassage = ((MAX_CONTEXT_CHARS - ctx.length) / passages.size).coerceAtLeast(200)
            passages.forEach { ctx.appendLine("Reference (${it.chunk.sourceTitle}): ${it.chunk.contentText.replace("\n\n", " ").take(perPassage)}") }
        }
        return ctx.toString().trim().take(MAX_CONTEXT_CHARS + 400)
    }

    /** The newest patient message followed by compact per-question context (keeps models from echoing notes). */
    fun newestMessage(question: String, background: String, withImage: Boolean): String = buildString {
        append(question)
        append("\n\n[")
        if (background.isNotEmpty()) append(background).append('\n')
        if (withImage) append(PHOTO_RULE).append('\n')
        append(
            if (Language.isTagalog(question)) "Sagutin sa simpleng Tagalog o Taglish lamang."
            else "Reply in simple English.",
        )
        append(']')
    }

    /** Text to cache right after the model loads: every prompt starts with it. */
    fun warmup(format: ChatFormat): String = format.prefix(RULES)

    /** Real multi-turn prompt in [format], ending with an open model turn. */
    fun chat(
        format: ChatFormat,
        question: String,
        general: List<ScoredChunk>,
        records: List<ClinicalRecordEntity>,
        history: List<Turn> = emptyList(),
        withImage: Boolean = false,
    ): String {
        val newest = Turn(true, newestMessage(question, background(general, records), withImage))
        val turns = (history.fitting() + newest).dropWhile { !it.fromPatient } // must start with the patient
        return format.format(RULES, turns.map { it.fromPatient to it.text }, withImage)
    }

    /** Whole conversation in one message, for models without a known chat format. */
    fun build(
        question: String,
        general: List<ScoredChunk>,
        records: List<ClinicalRecordEntity>,
        history: List<Turn> = emptyList(),
        withImage: Boolean = false,
    ): String = buildString {
        appendLine(RULES)
        appendLine()
        background(general, records).takeIf { it.isNotEmpty() }?.let { appendLine("Background to use when it fits:\n$it\n") }
        appendLine("Conversation:")
        history.fitting().forEach { appendLine((if (it.fromPatient) "Patient: " else "patnub: ") + it.text) }
        appendLine("Patient: " + newestMessage(question, "", withImage))
    }

    private fun List<Turn>.fitting(): List<Turn> {
        var used = 0
        return asReversed().takeWhile { used += it.text.length + 10; used <= MAX_HISTORY_CHARS }.asReversed()
    }
}
