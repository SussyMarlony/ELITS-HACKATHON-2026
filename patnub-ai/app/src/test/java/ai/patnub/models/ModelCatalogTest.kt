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

    @Test fun importedFilesAreMatchedToTheirPackageBySize() {
        val all = ModelCatalog.packages
        val gemma4 = all.first { it.id == "gemma4-e2b" }
        assertEquals(gemma4.main, ModelCatalog.fileOfSize(gemma4.main.bytes))
        assertEquals(null, ModelCatalog.fileOfSize(12_345))
        // Every package is found from exactly its own files.
        all.forEach { p -> assertEquals(p.id, ModelCatalog.packageForFiles(p.files.toSet())?.id) }
        assertEquals(null, ModelCatalog.packageForFiles(emptySet()))
        // MedGemma main file alone: the streamed package (complete) beats the full one (missing vision)...
        val medMain = all.first { it.medical }.main
        val streamed = ModelCatalog.packageForFiles(setOf(medMain))!!
        assertTrue(streamed.streamed && streamed.files.size == 1)
        // ...but with the vision file already on the phone, the full package is complete and wins.
        val vision = all.first { it.medical && it.vision != null }.vision!!
        val full = ModelCatalog.packageForFiles(setOf(medMain)) { it == vision }!!
        assertTrue(!full.streamed && full.vision == vision)
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

    @Test fun repackOnlyWhenMemoryHeadroomFits() {
        val gemma4 = ModelCatalog.byId("gemma4-e2b")!!
        val medStreamed = ModelCatalog.byId("medgemma-4b-streamed")!!
        val medResident = ModelCatalog.byId("medgemma-4b-resident")!!
        assertFalse(ModelCatalog.shouldRepack(gemma4, (3.6 * gib).toLong()))
        assertTrue(ModelCatalog.shouldRepack(gemma4, (5.5 * gib).toLong()))
        assertFalse(ModelCatalog.shouldRepack(medStreamed, 12L * gib))
        assertFalse(ModelCatalog.shouldRepack(medResident, (7.3 * gib).toLong()))
        assertTrue(ModelCatalog.shouldRepack(medResident, 11L * gib))
    }
}
