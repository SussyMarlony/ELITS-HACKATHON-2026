package ai.patnub.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val gib = 1L shl 30

    @Test fun tiersFromReportedRam() {
        assertEquals(PhoneTier.LOW, ModelCatalog.tierFor((3.6 * gib).toLong()))   // "4 GB" phone
        assertEquals(PhoneTier.MID, ModelCatalog.tierFor((5.5 * gib).toLong()))   // "6 GB"
        assertEquals(PhoneTier.HIGH, ModelCatalog.tierFor((7.3 * gib).toLong()))  // "8 GB"
        assertEquals(PhoneTier.HIGH, ModelCatalog.tierFor(11L * gib))
    }

    @Test fun recommendations() {
        assertEquals("gemma4-e2b", ModelCatalog.recommendedFor(3L * gib).id)
        assertTrue(ModelCatalog.recommendedFor(6L * gib).streamed)
        assertFalse(ModelCatalog.recommendedFor(12L * gib).streamed)
        assertTrue(ModelCatalog.recommendedFor(12L * gib).medical)
    }

    @Test fun everyFileHasAChecksumAndSize() {
        ModelCatalog.packages.flatMap { it.files }.forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.bytes > 500_000_000)
            assertTrue(it.url.startsWith("https://huggingface.co/") && it.url.endsWith(it.fileName))
        }
    }

    @Test fun streamedPackageHasNoVision() {
        ModelCatalog.packages.filter { it.streamed }.forEach { assertEquals(null, it.vision) }
    }
}
