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

    fun mentionsHealth(text: String): Boolean = health.containsMatchIn(text) || feelings.containsMatchIn(text)

    /** Worry and mood are health topics too ("I'm stressed", "nalulungkot ako"). */
    private val feelings = Regex(
        "worr|anxi|stress|nervous|panic|scared|afraid|sad\\b|depress|lonely|mood|cry|" +
            "kaba|kinakabahan|nag-aalala|alala|takot|lungkot|nalulungkot|iyak|umiiyak|stress",
        RegexOption.IGNORE_CASE,
    )

    /** What kind of small talk a message is, or null when it is anything else. */
    enum class SmallTalk { HELLO, THANKS, BYE }

    private val hello = Regex("^(hi+|hello+|hey+|helo|good (morning|afternoon|evening|day)|kumusta|kamusta|musta|magandang (umaga|tanghali|hapon|gabi)|yo)\\b", RegexOption.IGNORE_CASE)
    private val thanks = Regex("^(thanks?|thank you|ty|salamat|maraming salamat|thx)\\b", RegexOption.IGNORE_CASE)
    private val bye = Regex("^(bye|goodbye|see you|paalam|sige po|ingat)\\b", RegexOption.IGNORE_CASE)

    /**
     * Greetings, thanks and goodbyes: short (up to 6 words) with no health word, so "thanks, but it
     * still hurts" still goes to the Assistant. "ok", "yes", "oo" are not small talk: they answer
     * the Assistant's own questions.
     */
    fun smallTalk(text: String): SmallTalk? {
        val t = text.trim().trimEnd('!', '.', '?', ' ')
        if (t.split(Regex("\\s+")).size > 6 || mentionsHealth(t)) return null
        return when {
            hello.containsMatchIn(t) -> SmallTalk.HELLO
            thanks.containsMatchIn(t) -> SmallTalk.THANKS
            bye.containsMatchIn(t) -> SmallTalk.BYE
            else -> null
        }
    }

    /** Tagalog greeting or thanks words, for answering small talk in kind. */
    fun soundsTagalog(text: String): Boolean =
        isTagalog(text) || Regex("kumusta|kamusta|musta|magandang|salamat|paalam|ingat|\\bpo\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** "When is my next check-up?", "kailan ang balik ko?": answered from the record, not the model. */
    private val nextVisit = Regex(
        "check[ -]?ups?|follow[ -]?ups?|appointment|next (visit|consult|clinic)|come back|go back to the (clinic|doctor|health)|" +
            "balik|bumalik|konsulta|schedule",
        RegexOption.IGNORE_CASE,
    )

    fun asksNextVisit(text: String): Boolean = nextVisit.containsMatchIn(text)

    fun isTagalog(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val hits = words.count { it in tagalog }
        return hits >= 2 || hits.toDouble() / words.size >= 0.25
    }
}
