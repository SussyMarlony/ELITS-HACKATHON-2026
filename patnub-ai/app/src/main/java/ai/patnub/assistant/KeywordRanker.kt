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
    )

    /**
     * Everyday English and Tagalog words patients use, mapped to the words the reference text uses.
     * shortcut: a small hand list; replace with embeddings (`embedding_blob`) when that exists.
     */
    private val synonyms: Map<String, List<String>> = mapOf(
        "hurt" to listOf("pain"), "hurts" to listOf("pain"), "ache" to listOf("pain"), "aches" to listOf("pain"), "sore" to listOf("pain"),
        "tummy" to listOf("abdominal", "stomach"), "belly" to listOf("abdominal", "stomach"),
        "finger" to listOf("hand"), "fingers" to listOf("hand"), "wrist" to listOf("hand"), "thumb" to listOf("hand"),
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
        val q = keys(question)
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

    @Volatile private var index: Index? = null

    private fun indexFor(chunks: List<KnowledgeChunkEntity>): Index =
        index?.takeIf { it.chunks === chunks || it.chunks == chunks } ?: Index(chunks).also { index = it }

    /**
     * Top [topK] passages, at most one per topic, best first. Empty means "no close match".
     * Rare words count more than common ones (IDF), and words that name the topic count three
     * times. A passage needs two shared keywords, or one that names its topic ("dengue"), so a
     * single common word never pulls in an unrelated passage.
     */
    fun rank(
        question: String,
        chunks: List<KnowledgeChunkEntity>,
        topK: Int = 2,
        minScore: Double = 0.25,
    ): List<ScoredChunk> {
        val q = keys(question)
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val ix = indexFor(chunks)
        val total = q.sumOf { ix.idf(it) }
        return chunks.indices
            .mapNotNull { i ->
                val hits = q.filter { it in ix.text[i] }
                val named = hits.filter { it in ix.topic[i] }
                if (hits.size >= 2 || named.isNotEmpty()) {
                    ScoredChunk(chunks[i], (hits.sumOf { ix.idf(it) } + 2 * named.sumOf { ix.idf(it) }) / total)
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
