package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant

/**
 * A patient's permission for the app to use one record. The Assistant may only
 * retrieve records with a GRANTED consent (build plan Phase 2, step 2).
 */
@Entity(
    tableName = "patient_consents",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["user_id"],
            childColumns = ["patient_id"],
        ),
        ForeignKey(
            entity = ClinicalRecordEntity::class,
            parentColumns = ["record_id"],
            childColumns = ["record_id"],
        ),
    ],
    indices = [
        Index("patient_id", "record_id"),
        Index("record_id"),
    ],
)
data class PatientConsentEntity(
    @PrimaryKey @ColumnInfo(name = "consent_id") val consentId: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "status") val status: ConsentStatus,
    @ColumnInfo(name = "timestamp") val timestamp: Instant,
)
