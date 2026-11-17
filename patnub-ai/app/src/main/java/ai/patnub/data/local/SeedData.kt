package ai.patnub.data.local

import ai.patnub.data.local.entity.KnowledgeChunkEntity
import ai.patnub.data.local.entity.KnowledgeStatus
import java.time.LocalDate

/** Demo reference content. Demo patients and clinicians live in the clinic app (spec v1.2). */
object SeedData {
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
    knowledgeChunkDao().insertOrIgnore(SeedData.knowledgeChunks)
}
