package ai.patnub.data.local

import ai.patnub.data.local.entity.KnowledgeChunkEntity
import ai.patnub.data.local.entity.KnowledgeStatus
import ai.patnub.data.local.entity.UserEntity
import ai.patnub.data.local.entity.UserRole
import java.time.LocalDate

/** Demo accounts from the build plan (Phase 1, `users`). Fictional people. */
object SeedData {
    val users = listOf(
        UserEntity(
            userId = "p1",
            role = UserRole.PATIENT,
            displayName = "Maria Dela Cruz",
            age = 54,
            barangay = "Brgy. Malinis",
        ),
        UserEntity(
            userId = "p2",
            role = UserRole.PATIENT,
            displayName = "Jose Ramos",
            age = 67,
            barangay = "Brgy. Bagong Silang",
        ),
        UserEntity(
            userId = "p3",
            role = UserRole.PATIENT,
            displayName = "Liza Santos",
            age = 29,
            barangay = "Brgy. Mabini",
        ),
        UserEntity(
            userId = "d1",
            role = UserRole.CLINICIAN,
            displayName = "Dr. Ana Reyes",
            specialty = "Internal Medicine",
            facility = "Rural Health Unit · San Isidro",
            prcLicenseNo = "0123456",
        ),
    )

    private fun demoChunk(id: String, title: String, publisher: String, topic: String, text: String) =
        KnowledgeChunkEntity(
            chunkId = id,
            sourceId = id,
            sourceTitle = "$title (demo placeholder)",
            publisher = publisher,
            version = "demo-0",
            reviewDate = LocalDate.of(2026, 11, 18),
            status = KnowledgeStatus.APPROVED,
            topic = topic,
            contentText = text,
        )

    /**
     * Placeholder passages so the Assistant pipeline can be built and tested. They are NOT
     * the real DOH/WHO/PNF texts; Marlon's clinically reviewed corpus replaces them.
     */
    val knowledgeChunks = listOf(
        demoChunk(
            "demo-htn-1", "Hypertension patient guide", "DOH Philippines", "hypertension",
            "High blood pressure often has no symptoms. Some people have headache or dizziness, " +
                "especially when standing up quickly. Take prescribed medicine daily, reduce salt, " +
                "stay active, and check blood pressure regularly. See a doctor if symptoms persist.",
        ),
        demoChunk(
            "demo-fever-1", "Fever self-care", "WHO", "fever",
            "Fever is a body temperature above 37.5 C. Rest, drink fluids, and use paracetamol as " +
                "directed on the label. Seek care if fever lasts more than three days or the person is very weak.",
        ),
        demoChunk(
            "demo-amlo-1", "Amlodipine label guidance", "PNF", "amlodipine",
            "Amlodipine is taken once daily for high blood pressure. Do not change the dose without " +
                "asking the prescribing doctor. Possible effects include ankle swelling and dizziness.",
        ),
    )
}

/** Safe to run on every launch: existing rows are never overwritten. */
suspend fun PatnubDatabase.seedDemoData() {
    userDao().insertOrIgnore(SeedData.users)
    knowledgeChunkDao().insertOrIgnore(SeedData.knowledgeChunks)
}
