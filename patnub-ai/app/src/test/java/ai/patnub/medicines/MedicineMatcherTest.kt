package ai.patnub.medicines

import ai.patnub.data.Bundled
import ai.patnub.data.local.entity.PrescribedMedication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicineMatcherTest {
    private val meds = Bundled.medicines

    @Test fun ocrTextFindsNameAndStrength() {
        val text = "NORVASC\nAmlodipine\n5mg Tablet\n30 tablets"
        val found = MedicineMatcher.findInText(text, meds)
        assertEquals(listOf("Amlodipine"), found.map { it.name })
        assertEquals(listOf("5 mg"), MedicineMatcher.strengthsIn(text, found.single()))
    }

    @Test fun aliasAndSearch() {
        assertEquals("Salbutamol", MedicineMatcher.findInText("Albuterol nebule", meds).single().name)
        assertEquals("Paracetamol", MedicineMatcher.findInText("ACETAMINOPHEN 500 mg", meds).single().name)
        assertEquals(listOf("Amlodipine"), MedicineMatcher.search("amlo", meds).map { it.name })
    }

    @Test fun strengthConflict() {
        assertTrue(MedicineMatcher.strengthConflicts("10 mg", PrescribedMedication("Amlodipine", "5 mg", "daily")))
        assertFalse(MedicineMatcher.strengthConflicts("5 mg", PrescribedMedication("Amlodipine", "5mg", "daily")))
    }
}
