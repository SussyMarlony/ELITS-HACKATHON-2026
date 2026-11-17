package ai.patnub.assistant

import ai.patnub.data.local.entity.KnowledgeChunkEntity

/** A chunk and how well it matches the question (0..1). */
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

    private fun keys(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .map { it.take(4) }
            .toSet()

    /** Share of the question's keywords found in [text], 0..1. */
    fun score(question: String, text: String): Double {
        val q = keys(question)
        if (q.isEmpty()) return 0.0
        val t = keys(text)
        return q.count { it in t }.toDouble() / q.size
    }

    /**
     * Top [topK] chunks scoring at least [minScore], best first. Empty means "no close match".
     * A chunk needs two shared keywords, or one that names its topic ("dengue"), so a single
     * common word never pulls in an unrelated passage.
     */
    fun rank(
        question: String,
        chunks: List<KnowledgeChunkEntity>,
        topK: Int = 2,
        minScore: Double = 0.25,
    ): List<ScoredChunk> {
        val q = keys(question)
        if (q.isEmpty()) return emptyList()
        return chunks
            .mapNotNull { c ->
                val hits = q.filter { it in keys("${c.topic} ${c.contentText}") }
                val namesTopic = hits.any { it in keys(c.topic) }
                if (hits.size >= 2 || namesTopic) ScoredChunk(c, hits.size.toDouble() / q.size) else null
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }
}
