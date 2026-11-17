package ai.patnub.assistant

import ai.patnub.data.Bundled
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordRankerTest {
    private val chunks = Bundled.knowledge

    @Test fun headacheAndDizzy_findsRelevantMedlinePlusTopics() {
        val hits = KeywordRanker.rank("I've had a headache for two days and feel dizzy when I stand up", chunks)
        assertTrue("no hits", hits.isNotEmpty())
        val topics = hits.map { it.chunk.topic }
        assertTrue(topics.toString(), topics.any { it in setOf("headache", "dizziness and vertigo", "low blood pressure", "high blood pressure") })
    }

    @Test fun unrelatedQuestion_returnsNothing() {
        assertTrue(KeywordRanker.rank("Who won the basketball game?", chunks).isEmpty())
    }

    @Test fun neverReturnsMoreThanTopK() {
        assertTrue(KeywordRanker.rank("blood pressure fever dengue asthma", chunks, topK = 2).size <= 2)
    }
}

class BundledContentTest {
    @Test fun everyPassageHasASourceAndIsPendingReview() {
        assertTrue(Bundled.knowledge.size > 50)
        Bundled.knowledge.forEach {
            assertTrue(it.sourceUrl!!.startsWith("https://medlineplus.gov/"))
            assertTrue(it.reviewedBy == null) // nobody has signed off yet; flip this when REVIEW.md is done
        }
    }

    @Test fun everyMedicineCitesItsFdaLabel() {
        assertTrue(Bundled.medicines.size >= 10)
        Bundled.medicines.forEach { assertTrue(it.name, it.sourceUrl!!.startsWith("https://dailymed.nlm.nih.gov/")) }
    }
}

class KeywordRankerScopeTest {
    private val chunks = Bundled.knowledge

    @Test fun genericQuestionsDoNotPullInPassages() {
        listOf("What is my medicine for?", "When is my next check-up?", "Can I eat mango?").forEach {
            assertTrue(it, KeywordRanker.rank(it, chunks).isEmpty())
        }
    }

    @Test fun oneWordTopicStillMatches() {
        assertTrue(KeywordRanker.rank("dengue", chunks).first().chunk.topic == "dengue")
        assertTrue(KeywordRanker.rank("may lagnat ako, fever since yesterday", chunks).any { it.chunk.topic == "fever" })
    }
}
