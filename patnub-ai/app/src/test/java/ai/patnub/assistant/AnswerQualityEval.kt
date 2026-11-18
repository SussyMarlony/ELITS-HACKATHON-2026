package ai.patnub.assistant

import ai.patnub.data.Bundled
import ai.patnub.engine.ChatFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Everyday patient questions and the passage topic a sensible answer should rest on (null = no
 * passage fits, the model should answer from general knowledge). It also writes the real prompts
 * to app/build/eval for scripts/eval-answers.sh to answer with a real model on a PC.
 */
class AnswerQualityEval {
    private val cases = listOf(
        "my fingers hurt after removing some seeds on chili" to "capsaicin pepper sili",
        "nahawakan ko ang sili, mahapdi ang kamay ko" to "capsaicin pepper sili",
        "I rubbed my eye after cutting chili and it burns" to "capsaicin pepper sili",
        "I have had a fever for 3 days and pain behind my eyes" to "dengue",
        "masakit ang ulo ko at nahihilo ako" to "headache|dizziness and vertigo",
        "my child has had diarrhea since yesterday" to "diarrhea",
        "I got bitten by a stray dog on my leg" to "animal bites",
        "I burned my hand with boiling water" to "burns",
        "I have had a cough with phlegm for two weeks" to "cough",
        "my blood pressure is 150 over 95, is that high?" to "high blood pressure",
        "an ant bit me and now it is swollen and itchy" to "insect bites and stings",
        "I twisted my ankle playing basketball" to "sprains and strains",
        "my stomach burns after eating" to "heartburn",
        "I can't sleep at night" to "insomnia|sleep disorders",
        "the skin between my toes is itchy and peeling" to "athlete's foot",
        "I cut my finger with a knife and it is bleeding a little" to "wounds and injuries|bleeding|hand injuries and disorders",
        "sore throat and runny nose" to "sore throat",
        "I ate spoiled food and keep vomiting" to "nausea and vomiting",
        "my knee is swollen and painful in the morning" to null,
        "I feel tired all the time" to "fatigue",
    )

    /** Ranks passages exactly as Assistant.ask does for a first message. */
    private fun ranked(q: String) = KeywordRanker.rank(q, Bundled.knowledge)

    @Test fun retrievalPicksTheRightTopic() {
        val misses = cases.mapNotNull { (q, want) ->
            val got = ranked(q).map { it.chunk.topic }
            // Any acceptable topic ("a|b") as the top passage; null = nothing in particular expected.
            val ok = want == null || got.firstOrNull() in want.split("|")
            if (ok) null else "$q -> $got (want $want)"
        }
        // Always written (cheap), for scripts/eval-answers.sh; the build dir keeps it out of git.
        File("build/eval").apply {
            mkdirs()
            resolve("retrieval.txt").writeText(cases.joinToString("\n") { (q, want) -> "$q\n   want=$want got=${ranked(q).map { "${it.chunk.topic}:%.2f".format(it.score) }}" })
            resolve("prompts").mkdirs()
            resolve("prompts/00-warmup.txt").writeText(PromptBuilder.warmup(ChatFormat.GEMMA4))
            cases.forEachIndexed { i, (q, _) ->
                resolve("prompts/%02d.txt".format(i + 1)).writeText(PromptBuilder.chat(ChatFormat.GEMMA4, q, ranked(q), emptyList()))
            }
            resolve("questions.txt").writeText(cases.joinToString("\n") { it.first })
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }
}

class CareHintsTest {
    private fun first(q: String) = CareHints.forQuestion(q).firstOrNull().orEmpty()

    @Test fun matchesTheSituationsSmallModelsGotWrong() {
        assertTrue(first("I got bitten by a stray dog on my leg").contains("anti-rabies"))
        assertTrue(first("kinagat ako ng aso kahapon").contains("anti-rabies"))
        assertTrue(first("I have had a cough with phlegm for two weeks").contains("TB"))
        assertTrue(first("ubo ko 3 linggo na").contains("TB"))
        assertTrue(first("fever for 3 days and pain behind my eyes").contains("dengue"))
        assertTrue(first("nahawakan ko ang sili, mahapdi ang kamay ko").contains("cool water"))
        assertTrue(first("I cut my finger with a knife").contains("press firmly"))
        assertTrue(first("I burned my hand with boiling water").contains("20 minutes"))
        assertTrue(first("my child has had diarrhea since yesterday").contains("ORS"))
    }

    @Test fun staysOutOfUnrelatedQuestions() {
        listOf("sore throat and runny nose", "my stomach burns after eating", "I have a cough since yesterday", "I can't sleep at night", "Two days, a little swollen, no fever", "dalawang araw na, walang lagnat").forEach {
            assertTrue(it, CareHints.forQuestion(it).isEmpty())
        }
    }
}

class SmallTalkTest {
    @Test fun greetingsThanksAndByeAreRecognised() {
        listOf("hi", "Hi!", "hello po", "hey there", "good morning", "kumusta", "magandang umaga po").forEach {
            assertTrue(it, Language.smallTalk(it) == Language.SmallTalk.HELLO)
        }
        listOf("thanks", "thank you!", "salamat po", "maraming salamat").forEach { assertTrue(it, Language.smallTalk(it) == Language.SmallTalk.THANKS) }
        listOf("bye", "paalam").forEach { assertTrue(it, Language.smallTalk(it) == Language.SmallTalk.BYE) }
        assertTrue(Language.soundsTagalog("kumusta") && Language.soundsTagalog("salamat po") && !Language.soundsTagalog("hello"))
    }

    @Test fun healthMessagesAndAnswersAreNotSmallTalk() {
        listOf(
            "hi, my head hurts", "thanks but my fever is back", "ok", "yes", "oo", "no",
            "hello I have a cough", "highest fever today", "I'm worried about my mom",
        ).forEach { assertTrue(it, Language.smallTalk(it) == null) }
        assertTrue(Language.mentionsHealth("I'm so stressed lately") && Language.mentionsHealth("nalulungkot ako"))
    }
}
