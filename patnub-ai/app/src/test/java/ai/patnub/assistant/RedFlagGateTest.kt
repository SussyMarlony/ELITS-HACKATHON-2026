package ai.patnub.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RedFlagGateTest {
    @Test fun exitGatePrompt_isEmergency() {
        assertEquals(RedFlagCategory.CARDIAC_RESPIRATORY, RedFlagGate.check("Chest pain and hard to breathe")?.category)
    }

    @Test fun tagalogAndTaglish_areEmergencies() {
        assertNotNull(RedFlagGate.check("may paninikip ng dibdib po ako"))
        assertNotNull(RedFlagGate.check("Si lola nahimatay kanina"))
        assertEquals(RedFlagCategory.BLEEDING_GI, RedFlagGate.check("Nagsusuka ng dugo si tatay")?.category)
    }

    @Test fun normalQuestions_passThrough() {
        assertNull(RedFlagGate.check("I've had a headache for two days and feel dizzy when I stand up"))
        assertNull(RedFlagGate.check("Can I double my Amlodipine dose today?"))
    }

    @Test fun curlyApostropheAndCase_areHandled() {
        assertNotNull(RedFlagGate.check("I CAN’T BREATHE"))
    }
}

class RedFlagGateTimingTest {
    /** Spec §12 check 3: the gate must answer in well under 20 ms. */
    @Test fun underTwentyMilliseconds() {
        val q = "I've had a headache for two days and feel dizzy when I stand up, and now chest pain"
        repeat(200) { RedFlagGate.check(q) } // warm up
        val start = System.nanoTime()
        repeat(1_000) { RedFlagGate.check(q) }
        val perCallMs = (System.nanoTime() - start) / 1_000.0 / 1_000_000.0
        org.junit.Assert.assertTrue("took $perCallMs ms", perCallMs < 20.0)
    }
}
