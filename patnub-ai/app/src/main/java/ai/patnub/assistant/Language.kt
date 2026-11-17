package ai.patnub.assistant

/**
 * Tagalog/Taglish or English, from common Tagalog function words. Small models are unreliable at
 * "reply in the patient's language", so the prompt states the language explicitly.
 */
object Language {
    private val tagalog = setOf(
        "ang", "ng", "mga", "ako", "ko", "ka", "mo", "sa", "na", "po", "ba", "hindi", "may", "ay",
        "akong", "kong", "ito", "iyan", "yan", "yung", "lang", "din", "rin", "naman", "siya", "kami",
        "tayo", "kasi", "pero", "ano", "bakit", "paano", "kailan", "masakit", "sakit", "kahapon",
        "ngayon", "araw", "gabi", "umaga", "nang", "pa", "nga", "talaga", "sobra", "medyo", "parang",
        "nagtatae", "nahihilo", "lagnat", "ubo", "sipon", "tiyan", "ulo", "gamot", "uminom", "kumain",
    )

    /** Words that make a message about health even when no reference passage matches it. */
    private val health = Regex(
        "pain|hurt|ache|sore|sick|ill|fever|cough|cold|flu|vomit|diarr|dizz|head|stomach|tummy|belly|chest|breath|" +
            "blood|bleed|rash|itch|swell|wound|cut|burn|bite|injur|broke|sprain|medicine|medic|drug|pill|tablet|dose|" +
            "doctor|clinic|nurse|hospital|health|pregnan|baby|period|sleep|tired|weak|numb|eye|ear|tooth|teeth|throat|" +
            "skin|bone|joint|muscle|back|neck|leg|arm|hand|foot|feet|finger|toe|heart|lung|kidney|liver|sugar|diabet|" +
            "pressure|weight|eat|food|drink|water|pee|urin|poop|stool|feel|symptom|allerg|infect|virus|vaccin|" +
            "sakit|masakit|lagnat|ubo|sipon|gamot|doktor|tiyan|ulo|dugo|sugat|pagtatae|nagtatae|suka|hilo|pagod|buntis",
        RegexOption.IGNORE_CASE,
    )

    fun mentionsHealth(text: String): Boolean = health.containsMatchIn(text)

    fun isTagalog(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val hits = words.count { it in tagalog }
        return hits >= 2 || hits.toDouble() / words.size >= 0.25
    }
}
