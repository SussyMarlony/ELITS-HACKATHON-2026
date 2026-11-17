package ai.patnub.assistant

import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineException
import ai.patnub.engine.EngineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf

/** Where an answer's facts came from, shown as badges under the reply. */
data class Source(val label: String, val fromClinician: Boolean)

sealed interface Reply {
    /** Red flag found before any model call (Spec §4.4). */
    data class Emergency(val flag: RedFlag) : Reply

    /** Dose or schedule change requested: refer to the prescriber (Spec §4.2). */
    data class AskPrescriber(val prescriber: String?) : Reply

    /** Nothing close in reviewed sources: do not guess (Spec §4.3). */
    data object NoMatch : Reply

    /** [text] streams pieces. [simulated] when the engine is FakeEngine; [fallback] when no model ran. */
    data class Answer(val text: Flow<String>, val sources: List<Source>, val simulated: Boolean, val fallback: Boolean) : Reply
}

/**
 * The Assistant pipeline (build plan Phase 2): red-flag gate → dose-change guard →
 * dual-collection retrieval with patient isolation → grounded prompt → streamed answer,
 * with a deterministic fallback whenever the model is not ready or busy.
 */
class Assistant(private val db: PatnubDatabase, private val engine: Engine) {

    /** [selfId] is the phone owner. It is never taken from the question text. */
    suspend fun ask(question: String, selfId: String): Reply {
        RedFlagGate.check(question)?.let { return Reply.Emergency(it) }

        val records = db.clinicalRecordDao().getConsentedIssued(selfId)
        if (MedicationChangeGuard.isDoseChange(question)) {
            val prescriber = records.firstOrNull()?.let { db.userDao().getById(it.clinicianId)?.displayName }
            return Reply.AskPrescriber(prescriber)
        }

        val general = KeywordRanker.rank(question, db.knowledgeChunkDao().getApproved())
        val personal = records
            .map { it to KeywordRanker.score(question, recordText(it)) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { it.first }
        if (general.isEmpty() && personal.isEmpty()) return Reply.NoMatch

        val sources = general.map { Source(it.chunk.publisher, fromClinician = false) } +
            personal.map { Source(clinicianLabel(it), fromClinician = true) }
        val fallbackText = fallback(general, personal)

        if (engine.state.value != EngineState.Ready) {
            return Reply.Answer(flowOf(fallbackText), sources, engine.isSimulated, fallback = true)
        }
        val stream = engine.generate(PromptBuilder.build(question, general, personal), maxTokens = 96)
            .catch { e -> if (e is EngineException) emit(fallbackText) else throw e }
        return Reply.Answer(stream, sources, engine.isSimulated, fallback = false)
    }

    private suspend fun clinicianLabel(r: ClinicalRecordEntity): String =
        db.userDao().getById(r.clinicianId)?.let { "${it.displayName} · ${r.encounterDate}" } ?: "Your clinician · ${r.encounterDate}"

    companion object {
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
            general.firstOrNull()?.let { append("General information (${it.chunk.publisher}): ${it.chunk.contentText}") }
        }.trim()
    }
}
