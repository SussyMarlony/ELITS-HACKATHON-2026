package ai.patnub.assistant

/**
 * Short first-aid and "where to go" facts for the situation in a question, written to the patient.
 * Shown word for word above the answer (and given to the model as context), so they reach the
 * patient even when the model ignores them or no model is installed.
 *
 * Measured with AnswerQualityEval: a 2B model given these as standing rules mixed them up (TB
 * advice in a sore-throat answer); given them per question it mostly followed them, but still
 * dropped "TB" when the cough reference said coughs under 3 weeks are usually acute.
 *
 * App-team wording of standard first aid (DOH/WHO practice), not copied from a source.
 * Pending clinician review with content/REVIEW.md.
 */
object CareHints {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val F = "(?<!no )(?<!walang )(?<!without )(fever|lagnat)"

    private val hints: List<Pair<Regex, String>> = listOf(
        Regex("\\b(dog|cat|aso|pusa|bat|paniki|monkey|unggoy|animal|hayop)\\b.{0,40}\\b(bit|bite|bitten|scratch|kagat|kinagat|kalmot)|\\b(bit|bitten|kagat|kinagat|kalmot)\\w*\\b.{0,40}\\b(dog|cat|aso|pusa|bat|paniki|monkey|animal|hayop)\\b", opt) to
            "Animal bite or scratch: wash with soap and running water for 15 minutes, then go to an animal bite treatment center today for anti-rabies vaccine, even if the wound is small. Rabies cannot be cured once symptoms start.",
        Regex("(cough|ubo)\\D{0,40}(\\b([2-9]|two|three|four|several|many|dalawang|tatlong)\\s*(weeks?|linggo)|months?|buwan)|(\\b([2-9]|two|three|four|several|many|dalawang|tatlong)\\s*(weeks?|linggo)|months?|buwan)\\D{0,40}(cough|ubo)", opt) to
            "A cough for 2 weeks or more should be checked for TB (tuberculosis). Go to your health center for a TB test; TB testing and treatment there are free.",
        // F = "fever" not said as "no fever" / "walang lagnat".
        Regex("$F\\D{0,40}\\b([2-9]|two|three|four|five|dalawang|tatlong)\\s*(days?|araw)|\\b([2-9]|two|three|four|five|dalawang|tatlong)\\s*(days?|araw)\\D{0,40}$F", opt) to
            "Fever for 2 days or more: get checked for dengue at the health center. For fever, paracetamol is safer here; avoid ibuprofen and aspirin until dengue is ruled out. Drink plenty of fluids.",
        Regex("chil+i|\\bsili\\b|siling|labuyo|capsaicin|jalape|hot pepper", opt) to
            "Chili on the skin: wash with soap and cool water, several times if needed; warm water makes the burning worse. Keep your hands away from your eyes. If it got in your eyes, rinse with cool clean water for 15 minutes.",
        Regex("\\b(cut|cuts|sliced|knife|kutsilyo|hiwa|nahiwa|bleed\\w*|dumudugo|dugo)\\b", opt) to
            "Bleeding cut: press firmly on it with a clean cloth for 10 minutes to stop the bleeding, then rinse with clean water and cover it. If it is deep, gaping, dirty, or still bleeding, go to the health center (stitches, tetanus shot).",
        Regex("\\b(burn|burned|burnt|scald\\w*|paso|napaso|nasunog)\\b", opt) to
            "Burn or scald: cool it under clean running water for 20 minutes. Do not use ice, toothpaste, butter or oil, and do not pop blisters. If it is large, deep, or on the face, hands or genitals, go to the health center.",
        Regex("diarr|loose stool|pagtatae|nagtatae|vomit|suka|nagsusuka|spoiled|panis", opt) to
            "Diarrhea or vomiting: take small sips of ORS often. Go to the health center now if there is blood in the stool, no urine for 8 hours, if you are very sleepy or weak, cannot keep fluids down, or it is a baby or an older person.",
    )

    /** Hints for [question], at most [max] so the prompt stays short (each is ~40 tokens). */
    fun forQuestion(question: String, max: Int = 2): List<String> =
        hints.filter { it.first.containsMatchIn(question) }.map { it.second }.take(max)
}
