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
            // MedlinePlus summaries, plus curated FDA label excerpts for topics MedlinePlus lacks.
            assertTrue(it.sourceUrl, it.sourceUrl!!.startsWith("https://medlineplus.gov/") || it.sourceUrl!!.startsWith("https://dailymed.nlm.nih.gov/"))
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

class KeywordRankerChiliTest {
    private val chunks = Bundled.knowledge
    private fun topics(q: String) = KeywordRanker.rank(q, chunks).map { it.chunk.topic }

    @Test fun chiliOnFingers_findsCapsaicin_notWarts() {
        val t = topics("my fingers hurt after removing some seeds on chili")
        assertTrue(t.toString(), t.firstOrNull() == "capsaicin pepper sili")
        assertTrue(t.toString(), "warts" !in t)
    }

    @Test fun tagalogSili_findsCapsaicin() {
        assertTrue(topics("mahapdi kamay ko dahil sa siling labuyo").firstOrNull() == "capsaicin pepper sili")
    }

    @Test fun bodyPartAndPainAlone_doNotPickAPassageByThemselves() {
        assertTrue("warts" !in topics("my fingers hurt"))
    }

    @Test fun childrenDoNotMatchChili() {
        assertTrue("capsaicin pepper sili" !in topics("my children have fever and cough"))
    }
}

class NextVisitIntentTest {
    @Test fun recognisesCheckUpQuestions() {
        listOf("When is my next check-up?", "Yes, when is my next check up", "kailan ang balik ko sa clinic?", "when is my follow up").forEach {
            assertTrue(it, Language.asksNextVisit(it))
        }
        listOf("my fingers hurt after chili", "what is metformin for?").forEach { assertTrue(it, !Language.asksNextVisit(it)) }
    }
}
