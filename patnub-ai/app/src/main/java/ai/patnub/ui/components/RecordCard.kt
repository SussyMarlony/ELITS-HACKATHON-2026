package ai.patnub.ui.components

import ai.patnub.R
import ai.patnub.data.local.entity.PrescribedMedication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "18 Nov 2026" in the phone's locale; falls back to the raw text if it is not a date. */
fun friendlyDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(iso)

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
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "✓ " + stringResource(R.string.record_badge) + " · " + friendlyDate(encounterDate),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold,
                )
                Text(assessment ?: stringResource(R.string.record_untitled), style = MaterialTheme.typography.titleLarge)
                Text(
                    approvedBy + if (version > 1) " · " + stringResource(R.string.record_version, version) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meds.isNotEmpty()) Part(stringResource(R.string.record_meds)) {
                meds.forEach { m ->
                    Column {
                        Text("${m.name} ${m.dose}".trim(), fontWeight = FontWeight.SemiBold)
                        if (m.frequency.isNotBlank()) Text(m.frequency, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (instructions.isNotEmpty()) Part(stringResource(R.string.record_instructions)) { BulletList(instructions) }
            if (!followUp.isNullOrBlank()) Part(stringResource(R.string.record_follow_up)) { Text(followUp) }
            if (warnings.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.record_warnings), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        BulletList(warnings, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Part(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
