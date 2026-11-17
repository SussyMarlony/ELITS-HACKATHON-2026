package ai.patnub.data.local.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One medicine line in a clinician-approved record, stored inside `clinical_records.meds_json`. */
@Serializable
data class PrescribedMedication(
    val name: String,
    val dose: String,
    @SerialName("freq") val frequency: String,
)
