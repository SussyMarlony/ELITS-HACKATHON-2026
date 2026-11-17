package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant

/** Visit prep checklists and patient-written visit recaps (Spec §4.9, §4.10). */
@Entity(
    tableName = "visit_items",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["user_id"],
            childColumns = ["patient_id"],
        ),
    ],
    indices = [Index("patient_id", "item_type")],
)
data class VisitItemEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "patient_id") val patientId: String,
    @ColumnInfo(name = "item_type") val itemType: VisitItemType,
    @ColumnInfo(name = "origin") val origin: VisitItemOrigin = VisitItemOrigin.PATIENT_REPORTED,
    @ColumnInfo(name = "raw_input") val rawInput: String,
    /** Structured prep questions or recap sections. Shape is defined in Phase 5. */
    @ColumnInfo(name = "structured_json") val structuredJson: String?,
    @ColumnInfo(name = "has_conflict") val hasConflict: Boolean,
    @ColumnInfo(name = "conflict_note") val conflictNote: String?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)
