package ai.patnub.ui.doctor

import ai.patnub.R
import ai.patnub.ui.components.PlaceholderPane
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The three Doctor portal steps from the build plan. */
enum class DoctorStep(@StringRes val label: Int, @StringRes val description: Int) {
    WriteNote(R.string.doctor_step_write, R.string.placeholder_doctor_write),
    ReviewDraft(R.string.doctor_step_review, R.string.placeholder_doctor_review),
    IssueQr(R.string.doctor_step_issue, R.string.placeholder_doctor_issue),
}

@Composable
fun DoctorScreen(
    step: DoctorStep,
    onStepChange: (DoctorStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = DoctorStep.entries
    Column(modifier = modifier) {
        DoctorStepIndicator(current = step)

        PlaceholderPane(
            title = step.label,
            body = step.description,
            modifier = Modifier.weight(1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = { onStepChange(steps[step.ordinal - 1]) },
                enabled = step.ordinal > 0,
            ) {
                Text(stringResource(R.string.action_back))
            }
            if (step == steps.last()) {
                Button(onClick = { onStepChange(steps.first()) }) {
                    Text(stringResource(R.string.action_start_over))
                }
            } else {
                Button(onClick = { onStepChange(steps[step.ordinal + 1]) }) {
                    Text(stringResource(R.string.action_next))
                }
            }
        }
    }
}

@Composable
private fun DoctorStepIndicator(current: DoctorStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DoctorStep.entries.forEach { step ->
            val reached = step.ordinal <= current.ordinal
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "${step.ordinal + 1}", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(
                    text = stringResource(step.label),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = if (step == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
