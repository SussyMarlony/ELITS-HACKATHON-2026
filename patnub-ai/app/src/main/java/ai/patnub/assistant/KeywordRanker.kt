package ai.patnub.assistant

import ai.patnub.data.local.entity.KnowledgeChunkEntity
import kotlin.math.ln

/** A chunk and how well it matches the question (higher is better). */
data class ScoredChunk(val chunk: KnowledgeChunkEntity, val score: Double)

/**
 * Keyword matching for Collection A. Words are compared by their first four letters, so
 * "dizzy" matches "dizziness". shortcut: no embeddings yet, so paraphrases without shared
 * words won't match; swap for embedding similarity (`embedding_blob`) when that pipeline exists.
 */
object KeywordRanker {
    private val stopWords = setOf(
        "the", "and", "for", "with", "have", "has", "had", "can", "how", "what", "why", "when",
        "does", "did", "are", "was", "not", "but", "you", "your", "feel", "feeling", "been",
        "since", "ako", "ang", "ng", "sa", "na", "ko", "may", "at", "ay", "po",
        // Common question words: on their own they say nothing about the health topic.
        "who", "won", "what", "where", "which", "will", "would", "should", "could", "this", "that",
        "these", "those", "there", "their", "they", "them", "from", "about", "into", "over", "more",
        "most", "some", "very", "just", "also", "then", "than", "other", "many", "much", "after",
        "before", "next", "get", "got", "take", "taking", "use", "using", "need", "want", "know",
        "tell", "help", "please", "eat", "my", "is", "it", "its", "medicine", "medicines", "today",
        "ano", "bakit", "paano", "kailan", "saan", "yung", "mga", "lang", "pero", "kasi", "naman",
        // Their 4-letter key "chil" would match "children"; the synonyms below carry the meaning.
        "chili", "chilli", "chilies", "chillies", "chilis",
    )

    /**
     * Everyday English and Tagalog words patients use, mapped to the words the reference text uses.
     * shortcut: a small hand list; replace with embeddings (`embedding_blob`) when that exists.
     */
    private val synonyms: Map<String, List<String>> = mapOf(
        "hurt" to listOf("pain"), "hurts" to listOf("pain"), "ache" to listOf("pain"), "aches" to listOf("pain"), "sore" to listOf("pain"),
        "tummy" to listOf("abdominal", "stomach"), "belly" to listOf("abdominal", "stomach"),
        "finger" to listOf("hand"), "fingers" to listOf("hand"), "wrist" to listOf("hand"), "thumb" to listOf("hand"),
        "toe" to listOf("foot"), "toes" to listOf("foot"),
        "knee" to listOf("joint"), "knees" to listOf("joint"), "joints" to listOf("joint"),
        "poop" to listOf("stool", "bowel"), "pee" to listOf("urine", "urinary"), "throw" to listOf("vomiting"),
        "puking" to listOf("vomiting"), "dizzy" to listOf("dizziness"), "tired" to listOf("fatigue"),
        "itchy" to listOf("itching"), "rash" to listOf("rashes", "skin"), "bite" to listOf("bites"), "dog" to listOf("animal", "rabies"),
        "sakit" to listOf("pain"), "masakit" to listOf("pain"), "kirot" to listOf("pain"), "lagnat" to listOf("fever"),
        "ubo" to listOf("cough"), "sipon" to listOf("cold", "nose"), "trangkaso" to listOf("flu"), "pagtatae" to listOf("diarrhea"),
        "nagtatae" to listOf("diarrhea"), "suka" to listOf("vomiting"), "nagsusuka" to listOf("vomiting"), "hilo" to listOf("dizziness"),
        "nahihilo" to listOf("dizziness"), "ulo" to listOf("head", "headache"), "tiyan" to listOf("abdominal", "stomach"),
        "daliri" to listOf("finger", "hand"), "kamay" to listOf("hand"), "paa" to listOf("foot"), "tuhod" to listOf("knee", "joint"),
        "likod" to listOf("back"), "ngipin" to listOf("tooth"), "mata" to listOf("eye"), "tenga" to listOf("ear"),
        "sugat" to listOf("wound", "cut"), "paso" to listOf("burn", "burns"), "kagat" to listOf("bite", "bites"), "aso" to listOf("dog", "rabies"),
        "buntis" to listOf("pregnancy", "pregnant"), "pantal" to listOf("rash", "hives"), "kati" to listOf("itching"), "makati" to listOf("itching"),
        "hika" to listOf("asthma"), "puso" to listOf("heart"), "dugo" to listOf("blood"), "presyon" to listOf("blood", "pressure"),
        "asukal" to listOf("sugar", "diabetes"), "pagod" to listOf("fatigue", "tired"), "tulog" to listOf("sleep", "insomnia"),
        "regla" to listOf("menstruation", "period"), "lalamunan" to listOf("throat"),
        "chili" to listOf("capsaicin", "pepper"), "chilli" to listOf("capsaicin", "pepper"), "chilies" to listOf("capsaicin", "pepper"),
        "chillies" to listOf("capsaicin", "pepper"), "chilis" to listOf("capsaicin", "pepper"), "peppers" to listOf("capsaicin", "pepper"),
        "jalapeno" to listOf("capsaicin", "pepper"), "sili" to listOf("capsaicin", "pepper"), "siling" to listOf("capsaicin", "pepper"),
        "labuyo" to listOf("capsaicin", "pepper"), "anghang" to listOf("capsaicin"), "maanghang" to listOf("capsaicin"),
        "bit" to listOf("bites"), "bitten" to listOf("bites"), "kinagat" to listOf("bites"), "stung" to listOf("stings"),
        "ant" to listOf("insect"), "ants" to listOf("insect"), "langgam" to listOf("insect"), "mosquito" to listOf("insect"),
        "mosquitoes" to listOf("insect"), "lamok" to listOf("insect"), "bee" to listOf("insect", "stings"), "bees" to listOf("insect", "stings"),
        "wasp" to listOf("insect", "stings"), "putakti" to listOf("insect", "stings"), "spider" to listOf("insect"), "gagamba" to listOf("insect"),
    )

    /**
     * Words that say where it hurts or how, but not what is wrong. Two of them ("fingers hurt")
     * are not enough to pick a passage: that matched warts for "fingers hurt from chili".
     */
    private val weak: Set<String> = listOf(
        "pain", "hurt", "hurts", "ache", "sore", "remove", "removing", "removed", "touch", "touched", "touching",
        "hand", "hands", "finger", "fingers", "thumb", "wrist", "arm", "arms", "leg", "legs", "foot", "feet",
        "toe", "toes", "skin", "body", "face", "back", "side", "left", "right",
    ).map { it.take(4) }.toSet()

    /**
     * "My stomach burns" is heartburn, not a burn from fire or hot water (which the Burns passage
     * names). Applied to questions only.
     */
    private val burningInside = Regex(
        "(stomach|chest|tummy|tiyan|sikmura|dibdib)\\b.{0,30}\\bburn|\\bburn\\w*.{0,30}\\b(stomach|chest|tummy|tiyan|sikmura|dibdib)",
        RegexOption.IGNORE_CASE,
    )

    private fun questionKeys(question: String): Set<String> =
        keys(
            if (burningInside.containsMatchIn(question)) {
                question.replace(Regex("(?i)burn\\w*|stomach|chest|tummy|tiyan|sikmura|dibdib"), "") + " acid reflux" // not "heartburn": its key "hear" matches "heart"
            } else {
                question
            },
        )

    private fun keys(text: String): Set<String> {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return (words + words.flatMap { synonyms[it].orEmpty() })
            .filter { it.length >= 3 && it !in stopWords }
            .map { it.take(4) }
            .toSet()
    }

    /** Share of the question's keywords found in [text], 0..1 (used for the patient's own records). */
    fun score(question: String, text: String): Double {
        val q = questionKeys(question)
        if (q.isEmpty()) return 0.0
        val t = keys(text)
        return q.count { it in t }.toDouble() / q.size
    }

    private class Index(val chunks: List<KnowledgeChunkEntity>) {
        val text = chunks.map { keys("${it.topic} ${it.contentText}") }
        val topic = chunks.map { keys(it.topic) }
        private val df = HashMap<String, Int>().also { m -> text.forEach { ks -> ks.forEach { m[it] = (m[it] ?: 0) + 1 } } }
        fun idf(k: String) = ln(1.0 + chunks.size / (1.0 + (df[k] ?: 0)))
    }

    private const val NAMED_BONUS = 3.0

    @Volatile private var index: Index? = null

    private fun indexFor(chunks: List<KnowledgeChunkEntity>): Index =
        index?.takeIf { it.chunks === chunks || it.chunks == chunks } ?: Index(chunks).also { index = it }

    /**
     * Top [topK] passages, at most one per topic, best first. Empty means "no close match".
     * Rare words count more than common ones (IDF), and words that name the topic count four
     * times (a passage called "Burns" beats one that merely says "boil water"). A passage needs two shared keywords that are not [weak] (body parts, "pain"), or one
     * that names its topic ("dengue"), so common words never pull in an unrelated passage.
     */
    fun rank(
        question: String,
        chunks: List<KnowledgeChunkEntity>,
        topK: Int = 2,
        minScore: Double = 0.25,
    ): List<ScoredChunk> {
        val q = questionKeys(question)
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val ix = indexFor(chunks)
        val total = q.sumOf { ix.idf(it) }
        return chunks.indices
            .mapNotNull { i ->
                val hits = q.filter { it in ix.text[i] }
                val named = hits.filter { it in ix.topic[i] }
                if (hits.count { it !in weak } >= 2 || named.isNotEmpty()) {
                    ScoredChunk(chunks[i], (hits.sumOf { ix.idf(it) } + NAMED_BONUS * named.sumOf { ix.idf(it) }) / total)
                } else {
                    null
                }
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .distinctBy { it.chunk.topic }
            .take(topK)
    }
}
