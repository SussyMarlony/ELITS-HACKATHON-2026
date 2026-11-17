package ai.patnub.transfer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TrustRootTest {
    @Test fun matchesContractDemoRoot() =
        assertEquals(File("../../contract/demo/root_pub.txt").readText().trim(), TrustRoot.ROOT_PUB_B64U)
}
