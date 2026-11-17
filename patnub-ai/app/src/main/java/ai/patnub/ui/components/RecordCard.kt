package ai.patnub.ui.components

import ai.patnub.R
import ai.patnub.data.local.entity.PrescribedMedication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A clinician-approved record. Blue marks clinician content (see theme/Color.kt). */
@Composable
fun RecordCard(
    assessment: String?,
    encounterDate: String,
    approvedBy: String,
    meds: List<PrescribedMedication>,
    instructions: List<String>,
    warnings: List<String>,
    followUp: String?,
    version: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.record_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(assessment ?: stringResource(R.string.record_untitled), style = MaterialTheme.typography.titleMedium)
            Text("$encounterDate · $approvedBy" + if (version > 1) " · " + stringResource(R.string.record_version, version) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (meds.isNotEmpty()) {
                Section(stringResource(R.string.record_meds))
                meds.forEach { Text("• ${it.name} ${it.dose} — ${it.frequency}".replace(Regex("\\s+—\\s*$"), "")) }
            }
            if (instructions.isNotEmpty()) {
                Section(stringResource(R.string.record_instructions))
                instructions.forEach { Text("• $it") }
            }
            if (!followUp.isNullOrBlank()) {
                Section(stringResource(R.string.record_follow_up))
                Text(followUp)
            }
            if (warnings.isNotEmpty()) {
                Text(stringResource(R.string.record_warnings), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
