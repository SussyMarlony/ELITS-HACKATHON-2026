package ai.patnub.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Medicine information copied verbatim from FDA drug labels (Spec §4.8). PNF content can replace it later. */
@Entity(
    tableName = "medication_references",
    indices = [Index("name")],
)
data class MedicationReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "med_id") val medId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "alias") val alias: String?,
    @ColumnInfo(name = "drug_class") val drugClass: String,
    @ColumnInfo(name = "strengths_json") val strengths: List<String>,
    @ColumnInfo(name = "forms_json") val forms: List<String>,
    @ColumnInfo(name = "uses_json") val uses: List<String>,
    @ColumnInfo(name = "side_effects_json") val sideEffects: List<String>,
    @ColumnInfo(name = "warnings_json") val warnings: List<String>,
    @ColumnInfo(name = "storage_text") val storageText: String,
    @ColumnInfo(name = "label_instructions") val labelInstructions: String,
    @ColumnInfo(name = "source_citation") val sourceCitation: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    /** Clinician who signed off this entry; null until clinical review (content/REVIEW.md). */
    @ColumnInfo(name = "reviewed_by") val reviewedBy: String? = null,
)
