package ai.patnub.assistant

import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineException
import ai.patnub.engine.EngineState
import ai.patnub.engine.Heat
import ai.patnub.engine.Thermal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import ai.patnub.assistant.AnswerCleaner.cleaned

/** Where an answer's facts came from, shown as badges under the reply. */
data class Source(val label: String, val fromClinician: Boolean)

sealed interface Reply {
    /** Red flag found before any model call (Spec §4.4). */
    data class Emergency(val flag: RedFlag) : Reply

    /** Dose or schedule change requested: refer to the prescriber (Spec §4.2). */
    data class AskPrescriber(val prescriber: String?) : Reply

    /** No model installed and nothing in the reviewed sources: structured questions to bring to a clinician. */
    data object NoMatch : Reply

    /** A photo question while no model that reads photos is loaded. */
    data object NeedsModel : Reply

    /** Not a health question (decided in code, no model call). */
    data object OffTopic : Reply

    /** Something failed while answering; shown instead of a missing bubble. */
    data class Failed(val message: String?) : Reply

    /**
     * [text] streams pieces. [fallback]: no model ran, the text is the matching reference passage.
     * [grounded]: reference text or the patient's record was given to the model; otherwise the
     * answer is the model's general knowledge and the UI says so. [unreviewed]: a cited passage
     * has no clinician sign-off yet.
     */
    data class Answer(
        val text: Flow<String>,
        val sources: List<Source>,
        val fallback: Boolean,
        val grounded: Boolean = true,
        val unreviewed: Boolean = false,
    ) : Reply
}

/**
 * The Assistant pipeline (build plan Phase 2): red-flag gate → dose-change guard → retrieval over
 * the conversation (reference passages + the patient's own consented records) → conversational
 * prompt → streamed answer. With a model it can ask follow-up questions and reason about possible
 * causes; without one it answers from the matching passage or gives a structured symptom checklist.
 */
class Assistant(
    private val db: PatnubDatabase,
    private val engine: Engine,
    /** Current phone heat; answers get shorter while the phone is hot. */
    private val heat: () -> Heat = { Heat.NORMAL },
) {

    val supportsImages: Boolean get() = modelReady && engine.supportsImages
    private val modelReady: Boolean get() = engine.state.value.let { it == EngineState.Ready || it == EngineState.Generating }

    fun cancel() = engine.cancel()

    /** Caches the fixed instructions after the model loads, so the first question starts faster. */
    suspend fun prewarm() {
        engine.chatFormat?.let { engine.prefill(PromptBuilder.warmup(it)) }
    }

    /**
     * [selfId] is the phone owner. It is never taken from the question text.
     * [history] is the conversation so far (oldest first), used for follow-up answers.
     */
    suspend fun ask(question: String, selfId: String, history: List<Turn> = emptyList(), imagePath: String? = null): Reply {
        RedFlagGate.check(question)?.let { return Reply.Emergency(it) }

        val records = db.clinicalRecordDao().getConsentedIssued(selfId)
        if (MedicationChangeGuard.isDoseChange(question)) {
            val prescriber = records.firstOrNull()?.let { db.userDao().getById(it.clinicianId)?.displayName }
            return Reply.AskPrescriber(prescriber)
        }

        // Follow-up replies ("3 days, it's swollen") carry no topic; search the recent patient turns too.
        val searchText = (history.filter { it.fromPatient }.takeLast(2).map { it.text } + question).joinToString(" ")
        val general = KeywordRanker.rank(searchText, db.knowledgeChunkDao().getApproved())
        val personal = records
            .map { it to KeywordRanker.score(searchText, recordText(it)) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { it.first }

        val sources = general.map { Source(it.chunk.sourceTitle, fromClinician = false) }.distinct() +
            personal.map { Source(clinicianLabel(it), fromClinician = true) }
        val unreviewed = general.any { it.chunk.reviewedBy == null }
        // Small models handle "not about health" badly (they explain their rules instead), so decide it
        // here: only an opening message with no matching passage, no record and no health word at all.
        if (history.isEmpty() && imagePath == null && general.isEmpty() && personal.isEmpty() && !Language.mentionsHealth(question)) {
            return Reply.OffTopic
        }
        val fallbackText = fallback(general, personal)

        if (imagePath != null && !supportsImages) return Reply.NeedsModel
        if (!modelReady) {
            return if (fallbackText.isBlank()) Reply.NoMatch
            else Reply.Answer(flowOf(fallbackText), sources, fallback = true, unreviewed = unreviewed)
        }

        val format = engine.chatFormat
        val prompt = if (format != null) {
            PromptBuilder.chat(format, question, general, personal, history, withImage = imagePath != null)
        } else {
            PromptBuilder.build(question, general, personal, history, withImage = imagePath != null)
        }
        val baseTokens = if (engine.streamed) MAX_TOKENS_STREAMED else MAX_TOKENS
        val maxTokens = Thermal.maxTokens(baseTokens, heat())
        val stream = engine.generate(prompt, maxTokens = maxTokens, temperature = 0.3f, imagePath = imagePath, raw = format != null)
            .cleaned()
            .catch { e -> if (e is EngineException && fallbackText.isNotBlank()) emit(fallbackText) else throw e }
        return Reply.Answer(
            stream, sources, fallback = false,
            grounded = general.isNotEmpty() || personal.isNotEmpty(), unreviewed = unreviewed,
        )
    }

    private suspend fun clinicianLabel(r: ClinicalRecordEntity): String =
        db.userDao().getById(r.clinicianId)?.let { "${it.displayName} · ${r.encounterDate}" } ?: "Your clinician · ${r.encounterDate}"

    companion object {
        /** ~20 s on a phone holding the model in memory. */
        const val MAX_TOKENS = 220

        /** Streamed MedGemma runs ~3 tok/s: keep answers short enough to wait for. */
        const val MAX_TOKENS_STREAMED = 140

        fun recordText(r: ClinicalRecordEntity) = listOfNotNull(
            r.assessment, r.meds.joinToString { "${it.name} ${it.dose} ${it.frequency}" },
            r.instructions.joinToString(" "), r.warnings.joinToString(" "), r.followUp,
        ).joinToString(" ")

        /** Deterministic answer from the retrieved text only, used when no model runs. */
        fun fallback(general: List<ScoredChunk>, personal: List<ClinicalRecordEntity>): String = buildString {
            personal.firstOrNull()?.let { r ->
                append("From your clinician (${r.encounterDate}): ")
                r.assessment?.let { append("$it. ") }
                if (r.meds.isNotEmpty()) append("Your medicines: ${r.meds.joinToString { "${it.name} ${it.dose}, ${it.frequency}" }}. ")
                if (r.instructions.isNotEmpty()) append(r.instructions.joinToString(". ", postfix = ". "))
                r.followUp?.let { append("$it. ") }
                append("\n\n")
            }
            general.firstOrNull()?.let { append("${it.chunk.sourceTitle}:\n${it.chunk.contentText}") }
        }.trim()
    }
}
