package ai.patnub.assistant

import ai.patnub.data.local.entity.ClinicalRecordEntity

/**
 * Builds the grounded prompt for MedGemma (build plan Phase 2 step 4). Kept short: about
 * 300 tokens of context at the engine's measured 15 tok/s prompt speed is ~20 s on the phone.
 */
object PromptBuilder {
    const val MAX_CONTEXT_CHARS = 1_200 // ~300 tokens

    fun build(question: String, general: List<ScoredChunk>, records: List<ClinicalRecordEntity>, withImage: Boolean = false): String = buildString {
        appendLine("You are a health information assistant for patients in the Philippines.")
        appendLine("Rules: use plain language. Use ONLY the context below. Do not diagnose. Never change, start or stop a medicine or dose.")
        if (withImage) {
            appendLine("The patient attached a photo. Say plainly what you can see. If it is a medicine package, read the name, strength and form exactly as printed. If you are unsure, say so. Do not guess a diagnosis from a photo.")
        }
        appendLine("Keep general information separate from the patient's own doctor record. If the context does not answer, say so and suggest seeing their clinician.")
        val context = StringBuilder()
        // The patient's own clinician record goes first so the length cap can never cut it off.
        records.forEach { r ->
            context.append("[Your doctor's record · ${r.encounterDate}] ")
            r.assessment?.let { context.append("Assessment: $it. ") }
            if (r.meds.isNotEmpty()) context.append("Medicines: ${r.meds.joinToString { "${it.name} ${it.dose} ${it.frequency}" }}. ")
            if (r.instructions.isNotEmpty()) context.append("Instructions: ${r.instructions.joinToString("; ")}. ")
            r.followUp?.let { context.append("Follow-up: $it.") }
            context.appendLine()
        }
        val perPassage = ((MAX_CONTEXT_CHARS - context.length) / general.size.coerceAtLeast(1)).coerceAtLeast(200)
        general.forEach { context.appendLine("[General · ${it.chunk.publisher}] ${it.chunk.contentText.replace("\n\n", " ").take(perPassage)}") }
        appendLine("Context:")
        appendLine(context.toString().take(MAX_CONTEXT_CHARS))
        appendLine("Question: $question")
    }
}
