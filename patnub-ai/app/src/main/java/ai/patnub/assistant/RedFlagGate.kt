package ai.patnub.assistant

enum class RedFlagCategory { CARDIAC_RESPIRATORY, NEUROLOGICAL, BLEEDING_GI }

data class RedFlag(val category: RedFlagCategory, val phrase: String)

/**
 * Pre-LLM emergency screen (Spec §4.4). Runs before retrieval or generation; a match means
 * the Assistant shows the emergency card and never calls the model. Plain substring checks
 * keep it well under the 20 ms budget.
 *
 * The phrase lists are an engineering starting point from the build plan. Clinician review
 * (Marlon, build plan §4) must sign off the final list before any real use.
 */
object RedFlagGate {
    private val phrases: Map<RedFlagCategory, List<String>> = mapOf(
        RedFlagCategory.CARDIAC_RESPIRATORY to listOf(
            "chest pain", "chest tightness", "paninikip ng dibdib", "sakit ng dibdib",
            "hirap huminga", "hirap sa paghinga", "cannot breathe", "can't breathe", "cant breathe",
            "hard to breathe", "difficulty breathing", "trouble breathing", "shortness of breath",
            "blue lips", "asul na labi",
        ),
        RedFlagCategory.NEUROLOGICAL to listOf(
            "slurred speech", "drooping face", "face drooping", "numbness on one side",
            "one side of my body", "seizure", "kombulsyon", "convulsion",
            "fainted", "fainting", "passed out", "nawalan ng malay", "nahimatay",
        ),
        RedFlagCategory.BLEEDING_GI to listOf(
            "bleeding gums", "bleeding nose", "nosebleed", "black stool", "itim na dumi",
            "vomiting blood", "vomit blood", "nagsusuka ng dugo", "persistent vomiting",
            "hindi tumitigil ang pagsusuka",
        ),
    )

    /** First red flag found in [text] (English, Tagalog or Taglish), or null. */
    fun check(text: String): RedFlag? {
        val t = text.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
        for ((category, list) in phrases) {
            list.firstOrNull { it in t }?.let { return RedFlag(category, it) }
        }
        return null
    }
}
