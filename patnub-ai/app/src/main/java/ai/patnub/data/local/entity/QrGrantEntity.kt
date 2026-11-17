package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant

/**
 * A single-use grant that lets one patient import one record by QR (Spec §4.6).
 * Only the token's hash is stored, never the token itself.
 */
@Entity(
    tableName = "qr_grants",
    foreignKeys = [
        ForeignKey(
            entity = ClinicalRecordEntity::class,
            parentColumns = ["record_id"],
            childColumns = ["record_id"],
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["user_id"],
            childColumns = ["intended_patient_id"],
        ),
    ],
    indices = [
        Index("record_id"),
        Index("intended_patient_id"),
        Index("token_hash", unique = true),
    ],
)
data class QrGrantEntity(
    @PrimaryKey @ColumnInfo(name = "grant_id") val grantId: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "intended_patient_id") val intendedPatientId: String,
    @ColumnInfo(name = "token_hash") val tokenHash: String,
    @ColumnInfo(name = "signature") val signature: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Instant,
    @ColumnInfo(name = "status") val status: QrGrantStatus,
    @ColumnInfo(name = "redeemed_at") val redeemedAt: Instant? = null,
)
