package ai.patnub.medicines

import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.data.local.entity.MedicationReferenceEntity
import ai.patnub.data.local.entity.PrescribedMedication

/** Pure helpers for the Medicines tab (Spec §4.8). */
object MedicineMatcher {
    private fun names(m: MedicationReferenceEntity) = listOfNotNull(m.name, m.alias)
    private fun word(n: String) = Regex("\\b${Regex.escape(n)}\\b", RegexOption.IGNORE_CASE)

    /** Search box: name or alias contains the query. */
    fun search(query: String, meds: List<MedicationReferenceEntity>): List<MedicationReferenceEntity> {
        val q = query.trim()
        return if (q.isEmpty()) meds else meds.filter { m -> names(m).any { it.contains(q, ignoreCase = true) } }
    }

    /** Medicines whose name or alias appears as a word in packaging text read by OCR. */
    fun findInText(text: String, meds: List<MedicationReferenceEntity>): List<MedicationReferenceEntity> =
        meds.filter { m -> names(m).any { word(it).containsMatchIn(text) } }

    /** Strengths of [med] printed in [text], e.g. "500 mg" also matches "500mg". */
    fun strengthsIn(text: String, med: MedicationReferenceEntity): List<String> {
        val compact = text.replace(Regex("\\s+"), "").lowercase()
        return med.strengths.filter { it.replace(Regex("\\s+"), "").lowercase() in compact }
    }

    /** This patient's prescriptions for [med], newest record first, with who prescribed them. */
    fun prescriptionsFor(med: MedicationReferenceEntity, records: List<ClinicalRecordEntity>): List<Pair<ClinicalRecordEntity, PrescribedMedication>> =
        records.sortedByDescending { it.createdAt }.flatMap { r ->
            r.meds.filter { p -> names(med).any { it.equals(p.name.trim(), ignoreCase = true) } }.map { r to it }
        }

    /**
     * True when the confirmed package strength differs from the prescribed dose. The app shows
     * the conflict and points to the prescriber or pharmacist; it never picks one (Spec §4.8).
     */
    fun strengthConflicts(confirmedStrength: String, prescribed: PrescribedMedication): Boolean {
        val norm = { s: String -> s.replace(Regex("\\s+"), "").lowercase() }
        val dose = norm(prescribed.dose)
        return dose.isNotEmpty() && dose != norm(confirmedStrength)
    }
}
