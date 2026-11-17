package ai.patnub.data.local

import ai.patnub.data.local.entity.KnowledgeChunkEntity
import ai.patnub.data.local.entity.KnowledgeStatus
import ai.patnub.data.local.entity.MedicationReferenceEntity
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
    private fun med(
        name: String, alias: String?, cls: String, strengths: List<String>, forms: List<String>,
        uses: List<String>, side: List<String>, warn: List<String>, storage: String, label: String,
    ) = MedicationReferenceEntity(
        medId = "demo-${name.lowercase().replace(' ', '-')}", name = name, alias = alias, drugClass = cls,
        strengths = strengths, forms = forms, uses = uses, sideEffects = side, warnings = warn,
        storageText = storage, labelInstructions = label,
        sourceCitation = "PNF (demo placeholder, from the alaga-health prototype; not reviewed)",
    )

    /** Placeholder medicine information from the prototype. Must be replaced by reviewed PNF content. */
    val medications = listOf(
        med("Amlodipine", null, "Calcium channel blocker", listOf("5 mg", "10 mg"), listOf("Tablet"),
            listOf("High blood pressure (hypertension)", "Some types of chest pain (angina)"),
            listOf("Swelling of the ankles or feet", "Headache or flushing", "Dizziness, especially when starting"),
            listOf("Don’t stop suddenly without talking to your doctor", "Tell your doctor about liver problems", "Large amounts of grapefruit may increase its effect"),
            "Room temperature, away from moisture and direct sunlight.", "Usually taken once a day at the same time, with or without food."),
        med("Paracetamol", null, "Pain reliever and fever reducer", listOf("500 mg", "120 mg/5 mL", "250 mg/5 mL"), listOf("Tablet", "Syrup"),
            listOf("Fever", "Mild to moderate pain such as headache or body aches"),
            listOf("Uncommon when taken as directed", "Allergic skin reactions (rare)"),
            listOf("Never take more than the label’s maximum daily amount", "Check other medicines for hidden paracetamol", "Ask a doctor first if you have liver disease or drink heavily"),
            "Room temperature, container tightly closed.", "Take as directed on the label, with doses spaced at least 4–6 hours apart."),
        med("Amoxicillin", null, "Antibiotic (penicillin type)", listOf("500 mg", "250 mg/5 mL"), listOf("Capsule", "Suspension"),
            listOf("Bacterial infections such as some throat, ear, and chest infections"),
            listOf("Upset stomach or diarrhea", "Rash"),
            listOf("Don’t take it if you’re allergic to penicillin", "Finish the full course, even if you feel better", "Doesn’t work for colds or flu (viruses)"),
            "Capsules at room temperature. Liquid forms often need the refrigerator — check the label.", "Taken at evenly spaced times for the full number of days prescribed."),
        med("Metformin", null, "Blood sugar medicine (biguanide)", listOf("500 mg", "850 mg"), listOf("Tablet"),
            listOf("Type 2 diabetes"),
            listOf("Upset stomach, nausea, or diarrhea — often improves with time", "Metallic taste"),
            listOf("Tell your doctor about kidney problems", "May need to be paused before X-ray dye or surgery", "Avoid heavy alcohol use"),
            "Room temperature, in a dry place.", "Usually taken with meals to reduce stomach upset."),
        med("Losartan", null, "Angiotensin receptor blocker", listOf("50 mg", "100 mg"), listOf("Tablet"),
            listOf("High blood pressure", "Kidney protection in some people with diabetes"),
            listOf("Dizziness", "Tiredness"),
            listOf("Not for use during pregnancy", "Ask before using potassium supplements or salt substitutes"),
            "Room temperature, away from light.", "Usually taken once a day, with or without food."),
        med("Oral Rehydration Salts", "ORS", "Rehydration solution", listOf("20.5 g sachet"), listOf("Powder for solution"),
            listOf("Replacing fluids and salts lost from diarrhea or vomiting"),
            listOf("Rare; vomiting if drunk too quickly"),
            listOf("Mix with exactly the amount of clean water on the packet", "Get care for blood in the stool, high fever, or signs of severe dehydration"),
            "Unmixed packets: dry, room temperature. Mixed solution: use within 24 hours.", "Sip small amounts often, especially after each loose stool."),
        med("Cetirizine", null, "Antihistamine", listOf("10 mg", "5 mg/5 mL"), listOf("Tablet", "Syrup"),
            listOf("Allergy symptoms: sneezing, runny nose, itchy eyes", "Hives"),
            listOf("Drowsiness in some people", "Dry mouth"),
            listOf("Take care driving until you know how it affects you", "Ask a doctor first if you have kidney disease"),
            "Room temperature.", "Usually taken once a day."),
    )
}

/** Safe to run on every launch: existing rows are never overwritten. */
suspend fun PatnubDatabase.seedDemoData() {
    knowledgeChunkDao().insertOrIgnore(SeedData.knowledgeChunks)
    medicationReferenceDao().insertOrIgnore(SeedData.medications)
}
