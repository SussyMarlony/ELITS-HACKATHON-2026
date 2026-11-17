package ai.patnub.ui.components

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineState
import ai.patnub.engine.EngineStats
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val POLL_MS = 2_000L

/**
 * Patient-facing status ("Private · works offline", or "Thinking…" while generating).
 * Tapping it opens the engine telemetry, which is for judges and debugging, not patients.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineStatusPill(engine: Engine, modifier: Modifier = Modifier) {
    val state by engine.state.collectAsState()
    var open by remember { mutableStateOf(false) }

    Text(
        text = stringResource(if (state == EngineState.Generating) R.string.status_thinking else R.string.status_private),
        modifier = modifier.clickable(onClickLabel = stringResource(R.string.engine_details)) { open = true },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) { EngineDetails(engine, state) }
    }
}

@Composable
private fun EngineDetails(engine: Engine, state: EngineState) {
    val stats by produceState<EngineStats?>(null, state) {
        // Stats only exist while a model is loaded; poll so memory and speed stay live.
        while (state is EngineState.Ready || state is EngineState.Generating) {
            value = engine.stats()
            delay(POLL_MS)
        }
    }
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.engine_details), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.engine_details_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (engine.isSimulated) {
            Text(stringResource(R.string.engine_simulated_long), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        val status = when (state) {
            EngineState.Unloaded -> R.string.engine_offline
            EngineState.Loading -> R.string.engine_loading
            is EngineState.Failed -> R.string.engine_failed
            EngineState.Generating -> R.string.engine_generating
            EngineState.Ready -> R.string.engine_ready
        }
        Row2(stringResource(R.string.engine_row_status), stringResource(status))
        stats?.let { st ->
            st.vmRssKb?.let { Row2(stringResource(R.string.engine_row_memory), "${it / 1024} MiB") }
            Row2(stringResource(R.string.engine_row_layers), "${st.streamedLayers} streamed · ${st.budgetBytes shr 20} MiB budget")
            if (st.generatedTokens > 0) Row2(stringResource(R.string.engine_row_speed), "%.1f tok/s · first token %d ms".format(st.generateTokensPerSecond, st.ttftMs))
            st.thermalC?.let { Row2(stringResource(R.string.engine_row_temp), "$it °C") }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
