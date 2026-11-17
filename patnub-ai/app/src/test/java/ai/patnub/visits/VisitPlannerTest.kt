package ai.patnub.visits

import ai.patnub.data.local.entity.ApprovalStatus
import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.data.local.entity.PrescribedMedication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class VisitPlannerTest {
    private val record = ClinicalRecordEntity(
        recordId = "r1", patientId = "me", clinicianId = "s1", encounterDate = LocalDate.of(2026, 11, 18),
        rawDoctorNote = "", assessment = "Stage 1 hypertension",
        meds = listOf(PrescribedMedication("Amlodipine", "5 mg", "once daily")),
        instructions = emptyList(), warnings = emptyList(), followUp = null,
        approvalStatus = ApprovalStatus.ISSUED, version = 1, createdAt = Instant.EPOCH,
    )

    @Test fun prepIsTailoredAndIncludesPrescription() {
        val qs = VisitPlanner.prepQuestions("Masakit ang ulo at nahihilo", listOf(record))
        assertTrue(qs.first().contains("blood pressure"))
        assertTrue("Is Amlodipine 5 mg still right for me?" in qs)
        assertEquals(qs.size, qs.distinct().size)
    }

    @Test fun recapSortsSentencesAndFlagsDoseConflict() {
        val r = VisitPlanner.recap(
            "The doctor said my pressure is high. Take Amlodipine 10 mg every morning. Avoid salty food. Balik after 4 weeks. I think she said to walk more.",
            listOf(record),
        )
        assertEquals(listOf("Take Amlodipine 10 mg every morning", "Avoid salty food", "I think she said to walk more"), r.todo)
        assertEquals(listOf("Balik after 4 weeks"), r.followUp)
        assertEquals(listOf("The doctor said my pressure is high"), r.summary)
        assertEquals(1, r.conflicts.size)
        assertTrue(r.conflicts[0].contains("10 mg") && r.conflicts[0].contains("5 mg"))
        assertTrue(r.clarify.first().contains("weren’t sure"))
    }

    @Test fun matchingDoseIsNotAConflict() {
        assertTrue(VisitPlanner.recap("Take Amlodipine 5mg daily.", listOf(record)).conflicts.isEmpty())
    }
}
