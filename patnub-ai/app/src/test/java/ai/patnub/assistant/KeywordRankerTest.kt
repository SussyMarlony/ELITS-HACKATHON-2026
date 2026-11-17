package ai.patnub.assistant

import ai.patnub.data.local.SeedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordRankerTest {
    private val chunks = SeedData.knowledgeChunks

    @Test fun headacheAndDizzy_matchesHypertension() {
        val hits = KeywordRanker.rank("I've had a headache for two days and feel dizzy when I stand up", chunks)
        assertEquals("demo-htn-1", hits.first().chunk.chunkId)
    }

    @Test fun unrelatedQuestion_returnsNothing() {
        assertTrue(KeywordRanker.rank("Who won the basketball game?", chunks).isEmpty())
    }

    @Test fun neverReturnsMoreThanTopK() {
        assertTrue(KeywordRanker.rank("amlodipine dizzy blood pressure fever", chunks, topK = 2).size <= 2)
    }
}
