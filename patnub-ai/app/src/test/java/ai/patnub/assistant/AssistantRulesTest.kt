package ai.patnub.assistant

import ai.patnub.data.Bundled
import ai.patnub.data.local.entity.ApprovalStatus
import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.data.local.entity.PrescribedMedication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AssistantRulesTest {
    private val record = ClinicalRecordEntity(
        recordId = "r1", patientId = "me", clinicianId = "s1", encounterDate = LocalDate.of(2026, 11, 18),
        rawDoctorNote = "", assessment = "Stage 1 hypertension",
        meds = listOf(PrescribedMedication("Amlodipine", "5 mg", "once daily")),
        instructions = listOf("Reduce salt"), warnings = listOf("Severe headache"),
        followUp = "Follow up in 4 weeks", approvalStatus = ApprovalStatus.ISSUED, version = 1, createdAt = Instant.EPOCH,
    )

    @Test fun exitGatePrompt3_isDoseChange() {
        assertTrue(MedicationChangeGuard.isDoseChange("Can I double my Amlodipine dose today?"))
        assertTrue(MedicationChangeGuard.isDoseChange("Pwede ko bang itigil ang gamot ko?"))
        assertFalse(MedicationChangeGuard.isDoseChange("What is Amlodipine for?"))
        assertFalse(MedicationChangeGuard.isDoseChange("I've had a headache for two days and feel dizzy when I stand up"))
    }

    @Test fun exitGatePrompt1_matchesHisOwnRecord() {
        assertTrue(KeywordRanker.score("I've had a headache for two days and feel dizzy", Assistant.recordText(record)) > 0)
    }

    @Test fun promptKeepsGeneralAndPersonalApartAndShort() {
        val general = KeywordRanker.rank("headache dizzy stand up", Bundled.knowledge)
        val p = PromptBuilder.build("headache dizzy", general, listOf(record))
        assertTrue("Reference (MedlinePlus" in p)
        assertTrue("Their clinician's record (2026-11-18)" in p)
        assertTrue(p.indexOf("Their clinician's record") < p.indexOf("Reference (MedlinePlus"))
        assertTrue(p.length < 4_000)
    }

    @Test fun fallbackUsesOnlyRetrievedText() {
        val text = Assistant.fallback(emptyList(), listOf(record))
        assertTrue(text.startsWith("From your clinician"))
        assertTrue("Amlodipine 5 mg" in text)
    }
}
