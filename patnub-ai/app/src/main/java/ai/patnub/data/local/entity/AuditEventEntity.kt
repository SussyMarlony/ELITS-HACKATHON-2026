package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant

/**
 * Security and clinical workflow log. There are deliberately no foreign keys,
 * so audit rows outlive the users and records they mention.
 */
@Entity(
    tableName = "audit_events",
    indices = [Index("created_at")],
)
data class AuditEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "actor_role") val actorRole: ActorRole,
    /** For example `CLINICAL_RECORD_APPROVED` or `QR_WRONG_PATIENT_BLOCKED`. */
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "resource_id") val resourceId: String?,
    @ColumnInfo(name = "result") val result: String,
    @ColumnInfo(name = "details_json") val detailsJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)
