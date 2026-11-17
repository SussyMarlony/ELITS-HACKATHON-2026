package ai.patnub.ui.models

import ai.patnub.R
import ai.patnub.engine.EngineState
import ai.patnub.engine.NativeEngine
import ai.patnub.models.InstallState
import ai.patnub.models.ModelCatalog
import ai.patnub.models.ModelPackage
import ai.patnub.models.ModelStore
import ai.patnub.ui.components.SectionCard
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/**
 * Choose, download or import, and verify the on-device AI model. The phone's RAM picks the
 * recommended package; others can be chosen but may be too heavy.
 */
@Composable
fun ModelScreen(
    store: ModelStore,
    engineState: EngineState,
    loadedPackageId: String?,
    onStartModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val states by store.state.collectAsState()
    var selected by remember { mutableStateOf(store.selected) }
    val recommended = remember { ModelCatalog.recommendedFor(store.totalRamBytes) }
    var agreed by rememberSaveable(selected.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = states[selected.id] ?: InstallState.NotInstalled
    val busy = state is InstallState.Downloading || state is InstallState.Verifying

    LaunchedEffect(Unit) { store.refresh() }
    LaunchedEffect(state) { if (state == InstallState.Ready) onStartModel() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { store.import(selected, uris) }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.models_phone, gb(store.totalRamBytes), recommended.title),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!NativeEngine.cpuSupported) {
            Text(stringResource(R.string.models_cpu_unsupported), color = MaterialTheme.colorScheme.error)
        }

        ModelCatalog.packages.forEach { p ->
            PackageRow(
                p = p,
                selected = p.id == selected.id,
                recommended = p.id == recommended.id,
                tooHeavy = p.tier > ModelCatalog.tierFor(store.totalRamBytes),
                installed = states[p.id] == InstallState.Ready,
                onClick = { if (!busy) { selected = p; store.selected = p } },
            )
        }

        SectionCard(stringResource(R.string.models_install)) {
            Text(stringResource(R.string.models_size, gb(selected.totalBytes), gb(store.freeBytes())))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agreed || state == InstallState.Ready, onCheckedChange = { agreed = it }, enabled = state != InstallState.Ready)
                Text(stringResource(R.string.models_agree, selected.license), modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { uri.openUri(selected.licenseUrl) }) { Text(stringResource(R.string.models_read_license)) }

            when (state) {
                is InstallState.Downloading -> Progress(stringResource(R.string.models_downloading), state.progress)
                is InstallState.Verifying -> Progress(stringResource(R.string.models_verifying), state.progress)
                is InstallState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                InstallState.Ready -> Text(stringResource(R.string.models_ready), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                InstallState.NotInstalled -> {}
            }

            if (state != InstallState.Ready) {
                Button(
                    onClick = { scope.launch { store.download(selected) } },
                    enabled = agreed && !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.models_download)) }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = agreed && !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.models_import)) }
                Text(stringResource(R.string.models_import_hint, selected.files.joinToString { it.fileName }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = { store.delete(selected) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_delete)) }
            }
        }

        // msr_engine allows one model per process: switching needs a fresh start.
        if (state == InstallState.Ready && loadedPackageId != null && loadedPackageId != selected.id) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.models_restart_needed))
                    Button(onClick = {
                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                            context.startActivity(Intent.makeRestartActivityTask(it.component))
                        }
                        exitProcess(0)
                    }) { Text(stringResource(R.string.models_restart)) }
                }
            }
        }
        if (engineState is EngineState.Failed) {
            Text(stringResource(R.string.models_load_failed, engineState.error.message ?: ""), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.models_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PackageRow(p: ModelPackage, selected: Boolean, recommended: Boolean, tooHeavy: Boolean, installed: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                val tags = listOfNotNull(
                    stringResource(R.string.models_recommended).takeIf { recommended },
                    stringResource(R.string.models_installed).takeIf { installed },
                    stringResource(R.string.models_photos).takeIf { p.vision != null },
                    gb(p.totalBytes),
                )
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(p.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tooHeavy) Text(stringResource(R.string.models_too_heavy), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Progress(label: String, value: Float) {
    Text("$label ${(value * 100).toInt()}%")
    LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
}
