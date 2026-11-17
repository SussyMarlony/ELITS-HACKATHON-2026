package ai.patnub.assistant

import ai.patnub.data.Bundled
import ai.patnub.engine.ChatFormat
import ai.patnub.ui.patient.lightMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationTest {
    @Test fun everydayAndTagalogWordsFindTheRightTopic() {
        val topics = { q: String -> KeywordRanker.rank(q, Bundled.knowledge).map { it.chunk.topic } }
        assertTrue(topics("My fingers hurt").toString(), "hand injuries and disorders" in topics("My fingers hurt"))
        assertTrue(topics("masakit ang tiyan ko").toString(), topics("masakit ang tiyan ko").any { it == "abdominal pain" || it == "stomach disorders" })
        assertTrue(topics("may ubo at sipon").toString(), topics("may ubo at sipon").any { it == "cough" || it == "common cold" })
        assertTrue(topics("kinagat ako ng aso").toString(), topics("kagat ng aso").any { it == "animal bites" || it == "rabies" })
    }

    @Test fun earlierTurnsReachThePromptAndTheNewQuestionIsLast() {
        val history = listOf(Turn(true, "My fingers hurt"), Turn(false, "How long has it hurt, and is it swollen?"))
        val p = PromptBuilder.chat(ChatFormat.GEMMA4, "Two days, a little swollen, no fever", emptyList(), emptyList(), history)
        assertTrue(p.contains("<|turn>user\nMy fingers hurt<turn|>\n<|turn>model\nHow long has it hurt, and is it swollen?<turn|>"))
        assertTrue(p.contains("Two days, a little swollen, no fever\n\n[Reply in simple English.]<turn|>\n<|turn>model\n"))
        assertTrue("Ask 2 or 3 short clarifying questions" in p)
    }

    @Test fun promptsShareAPrefixSoTheCacheIsReused() {
        val k = Bundled.knowledge
        val f = ChatFormat.GEMMA3
        val t1 = listOf(Turn(true, "My fingers hurt"), Turn(false, "How long?"))
        val t2 = t1 + listOf(Turn(true, "Two days"), Turn(false, "Is it swollen?"))
        val p2 = PromptBuilder.chat(f, "Two days", KeywordRanker.rank("fingers two days", k), emptyList(), t1)
        val p3 = PromptBuilder.chat(f, "Yes, swollen", KeywordRanker.rank("fingers swollen", k), emptyList(), t2)
        assertTrue(p2.startsWith(PromptBuilder.warmup(f)))
        // Everything up to the previous patient message is identical: the instructions and the first exchange.
        val shared = p2.commonPrefixWith(p3)
        assertTrue(shared.contains("How long?<end_of_turn>\n<start_of_turn>user\n"))
    }

    @Test fun longHistoryIsTrimmedFromTheOldEnd() {
        val history = (1..40).map { Turn(it % 2 == 1, "message number $it with some words in it") }
        val p = PromptBuilder.build("latest", emptyList(), emptyList(), history)
        assertTrue("message number 40" in p)
        assertTrue("message number 1 " !in p)
    }

    @Test fun markdownBoldAndBullets() {
        val out = lightMarkdown("**Possible causes:**\n* strain\n- arthritis")
        assertEquals("Possible causes:\n•  strain\n•  arthritis", out.text)
        assertEquals(1, out.spanStyles.size)
    }

    @Test fun chatFormatHasRealTurnsAndSkipsReasoning() {
        val g3 = ChatFormat.GEMMA3.format("RULES", listOf(true to "hi", false to "hello", true to "my finger hurts"), lastHasImage = false)
        assertEquals(
            "<start_of_turn>user\nRULES<end_of_turn>\n<start_of_turn>model\nUnderstood. I am patnub. I will reply directly to the patient.<end_of_turn>\n" +
                "<start_of_turn>user\nhi<end_of_turn>\n<start_of_turn>model\nhello<end_of_turn>\n" +
                "<start_of_turn>user\nmy finger hurts<end_of_turn>\n<start_of_turn>model\n<unused94>thought\n<unused95>",
            g3,
        )
        val g4 = ChatFormat.GEMMA4.format("RULES", listOf(true to "photo?"), lastHasImage = true)
        assertEquals("<|turn>system\nRULES<turn|>\n<|turn>user\n<__media__>\nphoto?<turn|>\n<|turn>model\n", g4)
    }

    /** Writes real prompts for the host model test. Set PROMPT_DIR to enable. */
    @Test fun dumpPromptsForModelTesting() {
        val dir = System.getenv("PROMPT_DIR") ?: return
        File(dir).mkdirs()
        val k = Bundled.knowledge
        for (f in ChatFormat.entries) {
            val tag = f.name.lowercase()
            fun save(name: String, q: String, history: List<Turn>) {
                val search = (history.filter { it.fromPatient }.map { it.text } + q).joinToString(" ")
                File(dir, "$name.$tag.txt").writeText(PromptBuilder.chat(f, q, KeywordRanker.rank(search, k), emptyList(), history))
            }
            save("01-fingers", "My fingers hurt", emptyList())
            File(dir, "01-fingers.$tag.answer").takeIf { it.exists() }?.readText()?.trim()?.let { a1 ->
                save("02-fingers-followup", "Since 2 days ago, the middle finger is swollen after playing basketball. No fever.",
                    listOf(Turn(true, "My fingers hurt"), Turn(false, a1)))
            }
            save("03-cough", "Whats a medicine for cough", emptyList())
            save("04-tagalog", "Masakit ang tiyan ko at nagtatae ako mula kahapon", emptyList())
            save("05-offtopic", "Who won the basketball game?", emptyList())
            // A 3-turn conversation for measuring cache reuse (fixed model answers).
            File(dir, "conv-0.$tag.txt").writeText(PromptBuilder.warmup(f))
            val h1 = listOf(Turn(true, "My fingers hurt"), Turn(false, "How long have they hurt, and is any finger swollen?"))
            val h2 = h1 + listOf(Turn(true, "Two days. The middle finger is swollen."), Turn(false, "Did you hurt it, for example playing sports? Do you have a fever?"))
            save("conv-1", "My fingers hurt", emptyList())
            save("conv-2", "Two days. The middle finger is swollen.", h1)
            save("conv-3", "I jammed it playing basketball. No fever.", h2)
        }
    }
}

class CpuInfoTest {
    @Test fun countsBigCoresOnly() {
        // 1 prime + 3 big + 4 little (e.g. Snapdragon 8 Gen 2)
        assertEquals(4, ai.patnub.engine.CpuInfo.pick(listOf(1_800_000, 1_800_000, 1_800_000, 1_800_000, 2_800_000, 2_800_000, 2_800_000, 3_200_000).map { it.toLong() }, 8))
        // 2 big + 6 little with close clocks (e.g. Snapdragon 695: 2.21 GHz vs 1.80 GHz)
        assertEquals(2, ai.patnub.engine.CpuInfo.pick(listOf(1_804_800L, 1_804_800L, 1_804_800L, 1_804_800L, 1_804_800L, 1_804_800L, 2_208_000L, 2_208_000L), 8))
        assertEquals(2, ai.patnub.engine.CpuInfo.pickByCapacity(listOf(439L, 439L, 439L, 439L, 439L, 439L, 1024L, 1024L), 8))
        assertEquals(4, ai.patnub.engine.CpuInfo.pick(emptyList(), 8))
        assertEquals(2, ai.patnub.engine.CpuInfo.pick(listOf(2_000_000L, 2_000_000L, 1_000_000L, 1_000_000L), 4))
        assertEquals(4, ai.patnub.engine.CpuInfo.pick(listOf(2_000_000L, 2_000_000L, 2_000_000L, 2_000_000L), 4))
    }
}

class LanguageTest {
    @Test fun detectsTagalogAndTaglish() {
        assertTrue(Language.isTagalog("Masakit ang tiyan ko at nagtatae ako mula kahapon"))
        assertTrue(Language.isTagalog("may ubo po ako since monday"))
        assertTrue(!Language.isTagalog("My fingers hurt"))
        assertTrue(!Language.isTagalog("Whats a medicine for cough"))
    }

    @Test fun healthWords() {
        assertTrue(!Language.mentionsHealth("Who won the basketball game?"))
        assertTrue(Language.mentionsHealth("I feel weird after lunch"))
        assertTrue(Language.mentionsHealth("masakit ang paa ko"))
    }
}

class AnswerCleanerTest {
    @Test fun stopsAtAnInventedPatientTurn() {
        val (t, stop) = AnswerCleaner.clean("**Patnub:** How long has it hurt?\n\n**Patient:** Mga 3 beses lang")
        assertEquals("How long has it hurt?", t)
        assertTrue(stop)
    }

    @Test fun hidesLeakedReasoningUntilTheAnswerStarts() {
        assertEquals("", AnswerCleaner.clean("thought\nThe user wants me to act as").first)
        assertEquals("Your finger may be sprained.", AnswerCleaner.clean("thought\nplan the reply\n\nYour finger may be sprained.").first)
    }

    @Test fun hidesReasoningBetweenMarkersEvenWhileStreaming() {
        assertEquals("", AnswerCleaner.clean("<unused94>thought\nThe user wants").first)
        assertEquals("Rest the finger.", AnswerCleaner.clean("<unused94>thought\nplan<unused95>Rest the finger.").first)
        assertEquals("Hi there.", AnswerCleaner.clean("<|channel>thought\nx<channel|>Hi there.<turn|>").first)
    }

    @Test fun leavesNormalAnswersAlone() {
        val (t, stop) = AnswerCleaner.clean("Possible causes:\n* strain\n* arthritis")
        assertEquals("Possible causes:\n* strain\n* arthritis", t)
        assertTrue(!stop)
    }
}
