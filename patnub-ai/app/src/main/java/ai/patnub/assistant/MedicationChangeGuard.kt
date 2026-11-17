package ai.patnub.assistant

/**
 * Spec §4.2: the assistant never changes a prescribed dose or schedule. Questions that ask to
 * start, stop, skip, double or otherwise change a medicine get a fixed answer that sends the
 * patient to the prescribing clinician, before any model call. English, Tagalog and Taglish.
 */
object MedicationChangeGuard {
    private val change = Regex(
        "\\b(double|triple|increase|decrease|reduce|lower|raise|skip|stop|quit|halve|split|extra|more|another|" +
            "doblehin|dagdagan|bawasan|itigil|ihinto|laktawan|tigil)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val medicine = Regex(
        "\\b(dose|dosage|doses|tablet|tablets|pill|pills|capsule|medicine|medication|meds|gamot|tableta|" +
            "amlodipine|losartan|metformin|amoxicillin|paracetamol|cetirizine|insulin)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isDoseChange(question: String): Boolean = change.containsMatchIn(question) && medicine.containsMatchIn(question)
}
