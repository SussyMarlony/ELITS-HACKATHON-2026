package ai.patnub.transfer

import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.ActorRole
import ai.patnub.data.local.entity.ApprovalStatus
import ai.patnub.data.local.entity.AuditEventEntity
import ai.patnub.data.local.entity.ClinicalRecordEntity
import ai.patnub.data.local.entity.ConsentStatus
import ai.patnub.data.local.entity.PatientConsentEntity
import ai.patnub.data.local.entity.UserEntity
import ai.patnub.data.local.entity.UserRole
import ai.patnub.identity.PatientIdentity
import androidx.room3.withWriteTransaction
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What the Scan tab shows before the patient decides to save. */
sealed interface ScanOutcome {
    data class Preview(val opened: OpenedRecord, val replacesVersion: Int?) : ScanOutcome
    data class Rejected(val reason: RejectReason) : ScanOutcome
}

enum class RejectReason { NOT_PATNUB, TAMPERED, NOT_FOR_THIS_PHONE, EXPIRED, ALREADY_SAVED }

/** Scan → verify → preview → consent → save (spec §4.6 patient workflow). Fully offline. */
class RecordImporter(private val db: PatnubDatabase, private val identity: PatientIdentity) {

    suspend fun preview(text: String, now: Long = Instant.now().epochSecond): ScanOutcome {
        val private = identity.privateKey()
        val opened = try {
            RecordCodec.open(text.trim(), TrustRoot.publicKey, private, identity.publicKey()!!, now)
        } catch (e: OpenRecordException) {
            val reason = when (e.code) {
                OpenErrorCode.FORMAT -> RejectReason.NOT_PATNUB
                OpenErrorCode.SIGNATURE, OpenErrorCode.CHAIN -> RejectReason.TAMPERED
                OpenErrorCode.NOT_FOR_THIS_PHONE -> RejectReason.NOT_FOR_THIS_PHONE
                OpenErrorCode.EXPIRED -> RejectReason.EXPIRED
            }
            audit("QR_REJECTED", null, reason.name)
            return ScanOutcome.Rejected(reason)
        } finally {
            private.fill(0)
        }
        val existing = db.clinicalRecordDao().getById(opened.record.rid)
        if (ImportPolicy.decide(existing?.version, opened.record.rv) == ImportPolicy.Decision.ALREADY_SAVED) {
            audit("QR_REJECTED", opened.record.rid, RejectReason.ALREADY_SAVED.name)
            return ScanOutcome.Rejected(RejectReason.ALREADY_SAVED)
        }
        return ScanOutcome.Preview(opened, existing?.version)
    }

    /** Called only after the patient taps "Save" on the preview. */
    suspend fun save(opened: OpenedRecord) {
        val patientId = identity.selfUserId()
        val r = opened.record
        val s = opened.staff
        db.withWriteTransaction {
            db.userDao().upsert(
                UserEntity(
                    userId = s.staff_id, role = UserRole.CLINICIAN, displayName = s.name,
                    specialty = s.profession, facility = opened.license.clinic_name, prcLicenseNo = s.license_no,
                ),
            )
            db.clinicalRecordDao().upsert(
                ClinicalRecordEntity(
                    recordId = r.rid,
                    patientId = patientId,
                    clinicianId = s.staff_id,
                    encounterDate = LocalDate.parse(r.encounter_date),
                    rawDoctorNote = "", // the clinician's raw note stays at the clinic (spec v1.2)
                    assessment = r.assessment.ifBlank { null },
                    meds = r.meds,
                    instructions = r.instructions,
                    warnings = r.warnings,
                    followUp = r.follow_up.ifBlank { null },
                    approvalStatus = ApprovalStatus.ISSUED,
                    version = r.rv,
                    createdAt = Instant.ofEpochSecond(r.iat),
                ),
            )
            db.patientConsentDao().upsert(
                PatientConsentEntity(
                    consentId = "consent-${r.rid}", patientId = patientId, recordId = r.rid,
                    scope = "assistant_context", status = ConsentStatus.GRANTED, timestamp = Instant.now(),
                ),
            )
            audit("RECORD_IMPORTED", r.rid, "v${r.rv} from ${opened.license.clinic_id}/${s.staff_id}")
        }
    }

    private suspend fun audit(action: String, resource: String?, details: String) {
        db.auditEventDao().insert(
            AuditEventEntity(
                eventId = UUID.randomUUID().toString(),
                actorId = runCatching { identity.selfUserId() }.getOrDefault("unknown"),
                actorRole = ActorRole.PATIENT,
                action = action,
                resourceId = resource,
                result = if (action == "QR_REJECTED") "REJECTED" else "OK",
                detailsJson = JsonPrimitive(details).toString(),
                createdAt = Instant.now(),
            ),
        )
    }
}
