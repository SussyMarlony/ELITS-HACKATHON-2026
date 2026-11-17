package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.MedicationReferenceEntity
import ai.patnub.data.local.entity.UserRole
import ai.patnub.medicines.MedicineMatcher
import ai.patnub.ui.components.CameraScanner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

private sealed interface MedState {
    data object Browse : MedState
    data object Scanning : MedState
    /** Spec §4.8: confirm name, strength and form before showing details of a scanned package. */
    data class Confirm(val candidates: List<MedicationReferenceEntity>, val ocrText: String) : MedState
    data class Detail(val med: MedicationReferenceEntity, val strength: String?, val form: String?) : MedState
}

@Composable
fun MedicinesScreen(db: PatnubDatabase, selfId: String, modifier: Modifier = Modifier) {
    val meds by remember { db.medicationReferenceDao().observeAll() }.collectAsState(initial = emptyList())
    var state by remember { mutableStateOf<MedState>(MedState.Browse) }

    when (val s = state) {
        MedState.Browse -> Browse(meds, onPick = { state = MedState.Detail(it, null, null) }, onScan = { state = MedState.Scanning }, modifier)
        MedState.Scanning -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.med_scan_instructions), style = MaterialTheme.typography.titleMedium)
            val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            DisposableEffect(Unit) { onDispose { recognizer.close() } }
            CameraScanner(
                // Keep reading frames until a known medicine name shows up in the text.
                detect = { image ->
                    recognizer.process(image).continueWith { t ->
                        if (t.isSuccessful) t.result.text.takeIf { MedicineMatcher.findInText(it, meds).isNotEmpty() } else null
                    }
                },
                onResult = { text -> state = MedState.Confirm(MedicineMatcher.findInText(text, meds), text) },
                deniedText = stringResource(R.string.scan_camera_denied),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
            OutlinedButton(onClick = { state = MedState.Browse }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        }
        is MedState.Confirm -> Confirm(s, onConfirm = { m, st, f -> state = MedState.Detail(m, st, f) }, onCancel = { state = MedState.Browse }, modifier)
        is MedState.Detail -> Detail(db, selfId, s, onBack = { state = MedState.Browse }, modifier)
    }
}

@Composable
private fun Browse(meds: List<MedicationReferenceEntity>, onPick: (MedicationReferenceEntity) -> Unit, onScan: () -> Unit, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.med_search)) }, singleLine = true)
        }
        item { Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.med_scan)) } }
        items(MedicineMatcher.search(query, meds), key = { it.medId }) { m ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { onPick(m) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(m.name + (m.alias?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleMedium)
                    Text(m.drugClass, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Confirm(s: MedState.Confirm, onConfirm: (MedicationReferenceEntity, String, String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var med by remember { mutableStateOf(s.candidates.singleOrNull()) }
    var strength by remember { mutableStateOf(med?.let { MedicineMatcher.strengthsIn(s.ocrText, it).singleOrNull() }) }
    var form by remember { mutableStateOf(med?.forms?.singleOrNull()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.med_confirm_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.med_confirm_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.med_confirm_name), fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.candidates.forEach { c -> FilterChip(med == c, { med = c; strength = null; form = c.forms.singleOrNull() }, { Text(c.name) }) }
        }
        med?.let { m ->
            Text(stringResource(R.string.med_confirm_strength), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { m.strengths.forEach { x -> FilterChip(strength == x, { strength = x }, { Text(x) }) } }
            Text(stringResource(R.string.med_confirm_form), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { m.forms.forEach { x -> FilterChip(form == x, { form = x }, { Text(x) }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Button(enabled = med != null && strength != null && form != null, onClick = { onConfirm(med!!, strength!!, form!!) }) {
                Text(stringResource(R.string.med_confirm_ok))
            }
        }
    }
}

@Composable
private fun Detail(db: PatnubDatabase, selfId: String, s: MedState.Detail, onBack: () -> Unit, modifier: Modifier) {
    val records by remember(selfId) { db.clinicalRecordDao().observeIssued(selfId) }.collectAsState(initial = emptyList())
    val clinicians by remember { db.userDao().observeByRole(UserRole.CLINICIAN) }.collectAsState(initial = emptyList())
    val m = s.med
    val mine = MedicineMatcher.prescriptionsFor(m, records)
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.med_back)) }
        Text(m.name + listOfNotNull(s.strength, s.form).joinToString(" · ", prefix = if (s.strength != null) " · " else ""), style = MaterialTheme.typography.headlineSmall)
        Text(m.drugClass, color = MaterialTheme.colorScheme.onSurfaceVariant)

        mine.firstOrNull()?.let { (record, p) ->
            val who = clinicians.find { it.userId == record.clinicianId }?.displayName ?: stringResource(R.string.med_your_clinician)
            OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.med_your_prescription, who), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                    Text("${p.name} ${p.dose} — ${p.frequency}")
                    Text(record.encounterDate.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (s.strength != null && MedicineMatcher.strengthConflicts(s.strength, p)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.med_conflict, s.strength, p.dose), Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        Section(stringResource(R.string.med_label), listOf(m.labelInstructions))
        Section(stringResource(R.string.med_uses), m.uses)
        Section(stringResource(R.string.med_side_effects), m.sideEffects)
        Section(stringResource(R.string.med_warnings), m.warnings)
        Section(stringResource(R.string.med_storage), listOf(m.storageText))
        Text(stringResource(R.string.med_source, m.sourceCitation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.med_no_changes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, lines: List<String>) {
    Text(title, fontWeight = FontWeight.Bold)
    lines.forEach { Text("• $it") }
}
