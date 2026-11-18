package ai.patnub.ui.models

import ai.patnub.ui.components.Motion
import ai.patnub.models.ModelFile
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import ai.patnub.ui.components.pressScale
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import ai.patnub.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import ai.patnub.ui.components.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import ai.patnub.ui.components.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    // [active] is what the app uses (store.selected, loaded at start). [selected] is the card being looked
    // at: choosing a card only previews it, and Save changes makes it active.
    var active by remember { mutableStateOf(store.selected) }
    var selected by remember { mutableStateOf(store.selected) }
    val recommended = remember { ModelCatalog.recommendedFor(store.totalRamBytes) }
    var agreed by rememberSaveable(selected.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = states[selected.id] ?: InstallState.NotInstalled
    val busy = state is InstallState.Downloading || state is InstallState.Verifying || state is InstallState.Copying
    // Picked files waiting for the user to confirm the model and accept its terms.
    var pendingImport by remember { mutableStateOf<Pair<ModelPackage, Map<Uri, ModelFile>>?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val activeReady = states[active.id] == InstallState.Ready
    // A model must be restarted to switch away from (msr_engine allows one model per process).
    val needsRestart = loadedPackageId != null && loadedPackageId != selected.id
    val canSave = selected.id != active.id && state == InstallState.Ready

    fun save() {
        store.selected = selected
        active = selected
        if (needsRestart) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                context.startActivity(Intent.makeRestartActivityTask(it.component))
            }
            exitProcess(0)
        } else {
            onStartModel()
        }
    }

    LaunchedEffect(Unit) { store.refresh() }
    // The first model downloaded is used right away: there is nothing to switch from.
    LaunchedEffect(state) {
        if (state == InstallState.Ready && (!activeReady || selected.id == active.id)) {
            store.selected = selected
            active = selected
            onStartModel()
        }
    }

    // Files can be picked for any model: they are recognised by size, and that model is opened.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val found = store.identify(uris)
            val p = ModelCatalog.packageForFiles(found.values.toSet(), store::isVerified)
            importMessage = when {
                p == null -> context.getString(R.string.models_import_unknown)
                store.isReady(p) -> context.getString(R.string.models_import_already, p.title)
                else -> null
            }
            if (p != null) selected = p
            if (p != null && !store.isReady(p)) pendingImport = p to found
        }
    }

    Box(modifier) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            // Room for the Save bar so it never covers the last buttons.
            .padding(bottom = if (canSave) 120.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.models_title), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.models_phone, gb(store.totalRamBytes), recommended.title),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!NativeEngine.cpuSupported) {
            Text(stringResource(R.string.models_cpu_unsupported), color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.models_import))
        }
        Text(
            importMessage ?: stringResource(R.string.models_import_hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (importMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            stringResource(R.string.models_choose), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        // A radio group: TalkBack reads "1 of 3" and keeps the choices together.
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ModelCatalog.packages.forEach { p ->
                PackageRow(
                    p = p,
                    selected = p.id == selected.id,
                    recommended = p.id == recommended.id,
                    tooHeavy = p.tier > ModelCatalog.tierFor(store.totalRamBytes),
                    installed = states[p.id] == InstallState.Ready,
                    inUse = p.id == active.id && states[p.id] == InstallState.Ready,
                    onClick = { if (!busy) selected = p },
                )
            }
        }

        // Details of the card being looked at: its download, terms, or removal.
        SectionCard(selected.title) {
            Text(stringResource(R.string.models_size, gb(selected.totalBytes), gb(store.freeBytes())))
            // One toggle with the sentence as its label; a bare Checkbox would be read as just "Checkbox".
            Row(
                Modifier.toggleable(
                    value = agreed || state == InstallState.Ready,
                    enabled = state != InstallState.Ready,
                    role = Role.Checkbox,
                    onValueChange = { agreed = it },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = agreed || state == InstallState.Ready, onCheckedChange = null, enabled = state != InstallState.Ready)
                Text(stringResource(R.string.models_agree, selected.license), modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { uri.openUri(selected.licenseUrl) }) { Text(stringResource(R.string.models_read_license)) }
            // HAI-DEF §3.1.1: users must be told about the use restrictions before they get the model.
            if (state != InstallState.Ready) {
                selected.restrictionsUrl?.let { url -> UseRestrictions(url) { uri.openUri(it) } }
            }

            when (state) {
                is InstallState.Downloading -> Progress(stringResource(R.string.models_downloading), state.progress)
                is InstallState.Verifying -> Progress(stringResource(R.string.models_verifying), state.progress)
                is InstallState.Copying -> Progress(stringResource(R.string.models_copying), state.progress)
                is InstallState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                InstallState.Ready -> Text(stringResource(R.string.models_ready), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                InstallState.NotInstalled -> {}
            }

            if (state != InstallState.Ready) {
                Button(
                    onClick = { scope.launch { store.download(selected) } },
                    enabled = agreed && !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.models_download)) }
                Text(
                    stringResource(R.string.models_files, selected.files.joinToString { it.fileName }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(onClick = { store.delete(selected) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_delete)) }
            }
        }

        if (engineState is EngineState.Failed) {
            Text(stringResource(R.string.models_load_failed, engineState.error.message ?: ""), color = MaterialTheme.colorScheme.error)
        }

        // Licence notices for every model on the phone, kept visible after install (TODO.md §7).
        // Both MedGemma packages share one file, so list each model once.
        val onPhone = ModelCatalog.packages.filter { states[it.id] == InstallState.Ready }.distinctBy { it.main.fileName }
        if (onPhone.isNotEmpty()) {
            SectionCard(stringResource(R.string.models_licenses)) {
                onPhone.forEach { p -> LicenseNotice(p) { uri.openUri(it) } }
            }
        }
        Text(stringResource(R.string.models_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    pendingImport?.let { (p, files) ->
        ImportDialog(
            p = p,
            files = files.values.filter { it in p.files }.distinct(),
            missing = p.files.filter { f -> f !in files.values && !store.isVerified(f) },
            open = { uri.openUri(it) },
            onCancel = { pendingImport = null },
            onImport = {
                pendingImport = null
                agreed = true
                scope.launch { store.import(p, files) }
            },
        )
    }

    // Unsaved choice: a bar rises from the bottom edge, and leaves the same way on Save or Cancel.
    AnimatedVisibility(
        visible = canSave,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (Motion.enabled) slideInVertically(Motion.spring(0.35f, 0.85f)) { it } + fadeIn() else fadeIn(),
        exit = if (Motion.enabled) slideOutVertically(Motion.spring(0.25f)) { it } + fadeOut() else fadeOut(),
    ) {
        SaveBar(
            title = stringResource(R.string.models_switch_to, selected.title),
            note = if (needsRestart) stringResource(R.string.models_switch_restart_note) else null,
            saveLabel = stringResource(if (needsRestart) R.string.models_save_restart else R.string.models_save),
            onCancel = { selected = active },
            onSave = ::save,
        )
    }
    }
}

/** Confirms which model the picked files are, and takes the licence acceptance before copying. */
@Composable
private fun ImportDialog(
    p: ModelPackage,
    files: List<ModelFile>,
    missing: List<ModelFile>,
    open: (String) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.models_import_title, p.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.models_import_body, files.size, gb(files.sumOf { it.bytes })))
                if (missing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.models_import_missing, missing.joinToString { it.fileName }),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.models_agree, p.license), fontWeight = FontWeight.Medium)
                TextButton(onClick = { open(p.licenseUrl) }) { Text(stringResource(R.string.models_read_license)) }
                p.restrictionsUrl?.let { UseRestrictions(it, open) }
            }
        },
        confirmButton = { Button(onClick = onImport) { Text(stringResource(R.string.models_import_accept)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SaveBar(title: String, note: String?, saveLabel: String, onCancel: () -> Unit, onSave: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(saveLabel) }
            }
        }
    }
}

/** One installed model's licence: name, the required notice word for word, and links. */
@Composable
private fun LicenseNotice(p: ModelPackage, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // "MedGemma 1.5 4B · streamed" and "· full" are the same model.
        Text(p.title.substringBefore(" · "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.models_license_line, p.license))
        p.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { open(p.licenseUrl) }) { Text(stringResource(R.string.models_read_license)) }
        p.restrictionsUrl?.let { UseRestrictions(it, open) }
    }
}

/** What the licence forbids (HAI-DEF §3.2), with a link to Google's prohibited-use policy. */
@Composable
private fun UseRestrictions(url: String, open: (String) -> Unit) {
    Text(stringResource(R.string.models_use_restrictions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { open(url) }) { Text(stringResource(R.string.models_prohibited_uses)) }
}

@Composable
private fun PackageRow(p: ModelPackage, selected: Boolean, recommended: Boolean, tooHeavy: Boolean, installed: Boolean, inUse: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(200, easing = Motion.EaseOut), label = "ring",
    )
    Card(
        // The whole card is one radio button, read with its title, tags and summary.
        Modifier.fillMaxWidth().pressScale(source, 0.98f)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton, interactionSource = source, indication = ripple()),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(2.dp, ring),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    when {
                        inUse -> Badge(stringResource(R.string.models_in_use), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                        installed -> Badge(stringResource(R.string.models_installed), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val tags = listOfNotNull(
                    stringResource(R.string.models_recommended).takeIf { recommended },
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
private fun Badge(text: String, background: Color, content: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = content, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Progress(label: String, value: Float) {
    Text("$label ${(value * 100).toInt()}%")
    // Download progress arrives in jumps; ease between them so the bar glides.
    val shown by animateFloatAsState(value, tween(300, easing = Motion.EaseOut), label = "progress")
    LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
}
