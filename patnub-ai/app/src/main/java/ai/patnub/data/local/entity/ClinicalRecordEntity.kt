package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * Collection B: doctor-issued visit records, always scoped to one patient.
 * `raw_doctor_note` keeps the clinician's original text verbatim (Spec §4.5).
 */
@Entity(
    tableName = "clinical_records",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["user_id"],
            childColumns = ["patient_id"],
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["user_id"],
            childColumns = ["clinician_id"],
        ),
    ],
    indices = [
        Index("patient_id", "approval_status"),
        Index("clinician_id"),
    ],
)
data class ClinicalRecordEntity(
    @PrimaryKey @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    @ColumnInfo(name = "clinician_id") val clinicianId: String,
    @ColumnInfo(name = "encounter_date") val encounterDate: LocalDate,
    @ColumnInfo(name = "raw_doctor_note") val rawDoctorNote: String,
    /** Null while the record is a draft that hasn't been structured yet. */
    @ColumnInfo(name = "assessment") val assessment: String?,
    @ColumnInfo(name = "meds_json") val meds: List<PrescribedMedication>,
    @ColumnInfo(name = "instructions_json") val instructions: List<String>,
    @ColumnInfo(name = "warnings_json") val warnings: List<String>,
    @ColumnInfo(name = "follow_up") val followUp: String?,
    @ColumnInfo(name = "approval_status") val approvalStatus: ApprovalStatus,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)
