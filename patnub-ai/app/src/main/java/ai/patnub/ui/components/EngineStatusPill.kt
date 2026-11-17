package ai.patnub.ui.components

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineState
import ai.patnub.engine.EngineStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

private const val POLL_MS = 2_000L

/** One-line live engine telemetry for the top bar: state, memory, speed, temperature. */
@Composable
fun EngineStatusPill(engine: Engine, modifier: Modifier = Modifier) {
    val state by engine.state.collectAsState()
    val stats by produceState<EngineStats?>(null, state) {
        value = null
        // Stats only exist while a model is loaded; poll so memory and speed stay live.
        while (state is EngineState.Ready || state is EngineState.Generating) {
            value = engine.stats()
            delay(POLL_MS)
        }
    }

    val label = when (val s = state) {
        EngineState.Unloaded -> stringResource(R.string.engine_offline)
        EngineState.Loading -> stringResource(R.string.engine_loading)
        is EngineState.Failed -> stringResource(R.string.engine_failed)
        else -> buildString {
            append(stringResource(if (s == EngineState.Generating) R.string.engine_generating else R.string.engine_ready))
            stats?.let { st ->
                st.vmRssKb?.let { append(" · ${it / 1024} MiB") }
                if (st.generatedTokens > 0) append(" · %.1f tok/s".format(st.generateTokensPerSecond))
                st.thermalC?.let { append(" · $it°C") }
            }
        }
    }
    val text = if (engine.isSimulated) "${stringResource(R.string.engine_simulated)} · $label" else label

    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
