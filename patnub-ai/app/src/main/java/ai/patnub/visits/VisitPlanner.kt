package ai.patnub.visits

import ai.patnub.data.local.entity.ClinicalRecordEntity
import kotlinx.serialization.Serializable

/** Stored in `visit_items.structured_json` for PREP items. */
@Serializable
data class PrepChecklist(val concern: String, val questions: List<String>)

/** Stored in `visit_items.structured_json` for RECAP items. Always patient-reported (Spec §4.10). */
@Serializable
data class Recap(
    val summary: List<String>,
    val todo: List<String>,
    val followUp: List<String>,
    val clarify: List<String>,
    val conflicts: List<String>,
)

/**
 * Deterministic visit prep and recap (Spec §4.9, §4.10), ported from the alaga-health prototype.
 * Questions help the patient talk to the clinician; they are not medical advice.
 */
object VisitPlanner {
    private val sentenceSplit = Regex("(?<=[.!?])\\s+")
    private fun sentences(text: String) =
        text.replace(Regex("\\s+"), " ").trim().split(sentenceSplit).map { it.trim().removeSuffix(".") }.filter { it.isNotEmpty() }

    fun prepQuestions(concern: String, records: List<ClinicalRecordEntity>): List<String> {
        val t = concern.lowercase()
        val qs = LinkedHashSet<String>()
        fun has(re: String) = Regex(re).containsMatchIn(t)
        if (has("head|dizz|hilo|ulo")) qs += listOf("Could my headaches or dizziness be related to my blood pressure?", "Which warning signs mean I should go to the hospital right away?")
        if (has("side|medicine|gamot|pill|tablet")) qs += listOf("Is what I’m feeling a side effect, and what should I do about it?", "Is there a better time of day to take my medicine?")
        if (has("sleep|tulog|tired|pagod")) qs += "Could my sleep or tiredness be affecting my symptoms?"
        if (has("sugar|diabet|asukal")) qs += "What blood sugar numbers should I aim for?"
        if (has("cost|price|afford|mahal|free|libre")) qs += "Is there a lower-cost or free option at the health center?"
        records.maxByOrNull { it.createdAt }?.meds?.firstOrNull()?.let { qs += "Is ${it.name} ${it.dose} still right for me?" }
        qs += listOf("What might be causing my symptoms?", "Are there tests I should have?", "What changes would help the most?", "When should I come back for a check-up?")
        return qs.toList()
    }

    fun recap(text: String, records: List<ClinicalRecordEntity>): Recap {
        val s = sentences(text)
        val follow = s.filter { Regex("follow|return|come back|balik|next visit", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val todo = s.filter {
            it !in follow && Regex("\\b(take|inom|uminom|drink|avoid|bawal|check|walk|eat|rest|stop|reduce|bring|write|iwasan)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        val summary = s.filter { it !in todo && it !in follow }
        val clarify = mutableListOf<String>()
        if (Regex("something like|i think|not sure|hindi ko sigurado|forgot|nakalimutan", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            clarify += "You weren’t sure about part of the explanation. Ask the clinic to confirm it."
        }
        clarify += "What should I do if I miss a dose?"
        return Recap(summary, todo, follow, clarify, conflicts(s, records))
    }

    /**
     * Patient-written doses that differ from the clinician's record for the same medicine.
     * Shown side by side; the app never decides which one is right (Spec §4.10).
     */
    fun conflicts(sentences: List<String>, records: List<ClinicalRecordEntity>): List<String> {
        val dose = Regex("(\\d+(?:\\.\\d+)?)\\s?(mg|mcg|ml|g)\\b", RegexOption.IGNORE_CASE)
        val out = mutableListOf<String>()
        for (r in records.sortedByDescending { it.createdAt }) for (m in r.meds) {
            val norm = { x: String -> x.replace(Regex("\\s+"), "").lowercase() }
            for (sentence in sentences) {
                if (!sentence.contains(m.name, ignoreCase = true)) continue
                val written = dose.find(sentence)?.value ?: continue
                if (m.dose.isNotBlank() && norm(written) != norm(m.dose)) {
                    out += "You wrote ${m.name} $written, but your clinician’s record from ${r.encounterDate} says ${m.dose}. Confirm with your clinic before changing anything."
                }
            }
        }
        return out.distinct()
    }
}
