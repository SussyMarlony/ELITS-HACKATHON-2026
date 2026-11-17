package ai.patnub.medicines

import ai.patnub.data.local.SeedData
import ai.patnub.data.local.entity.PrescribedMedication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicineMatcherTest {
    private val meds = SeedData.medications

    @Test fun ocrTextFindsNameAndStrength() {
        val text = "BIOGESIC\nParacetamol\n500mg Tablet\n20 tablets"
        val found = MedicineMatcher.findInText(text, meds)
        assertEquals(listOf("Paracetamol"), found.map { it.name })
        assertEquals(listOf("500 mg"), MedicineMatcher.strengthsIn(text, found.single()))
    }

    @Test fun aliasAndSearch() {
        assertEquals("Oral Rehydration Salts", MedicineMatcher.findInText("ORS sachet", meds).single().name)
        assertEquals(listOf("Amlodipine"), MedicineMatcher.search("amlo", meds).map { it.name })
    }

    @Test fun strengthConflict() {
        assertTrue(MedicineMatcher.strengthConflicts("10 mg", PrescribedMedication("Amlodipine", "5 mg", "daily")))
        assertFalse(MedicineMatcher.strengthConflicts("5 mg", PrescribedMedication("Amlodipine", "5mg", "daily")))
    }
}
