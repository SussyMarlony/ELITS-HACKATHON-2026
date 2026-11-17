package ai.patnub.transfer

import ai.patnub.transfer.ImportPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec §12 check 7: replaying a code, or an older version, never overwrites a saved record. */
class ImportPolicyTest {
    @Test fun decisions() {
        assertEquals(Decision.SAVE_NEW, ImportPolicy.decide(null, 1))
        assertEquals(Decision.REPLACE_OLDER, ImportPolicy.decide(1, 2))
        assertEquals(Decision.ALREADY_SAVED, ImportPolicy.decide(2, 2))
        assertEquals(Decision.ALREADY_SAVED, ImportPolicy.decide(2, 1))
    }
}
