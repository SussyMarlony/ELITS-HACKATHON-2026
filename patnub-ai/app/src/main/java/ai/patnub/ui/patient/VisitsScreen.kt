package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.VisitItemEntity
import ai.patnub.data.local.entity.VisitItemType
import ai.patnub.visits.PrepChecklist
import ai.patnub.visits.Recap
import ai.patnub.visits.VisitPlanner
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

@Composable
fun VisitsScreen(db: PatnubDatabase, selfId: String, modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(VisitItemType.PREP) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            VisitItemType.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = mode == t, onClick = { mode = t },
                    shape = SegmentedButtonDefaults.itemShape(i, VisitItemType.entries.size),
                    label = { Text(stringResource(if (t == VisitItemType.PREP) R.string.visits_prep else R.string.visits_recap)) },
                )
            }
        }
        if (mode == VisitItemType.PREP) Prep(db, selfId) else RecapPane(db, selfId)
        Saved(db, selfId, mode)
    }
}

@Composable
private fun Prep(db: PatnubDatabase, selfId: String) {
    var concern by rememberSaveable { mutableStateOf("") }
    val questions = remember { mutableStateListOf<String>() }
    val records by remember(selfId) { db.clinicalRecordDao().observeIssued(selfId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Text(stringResource(R.string.visits_prep_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(concern, { concern = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.visits_concern)) }, minLines = 2)
    Button(enabled = concern.isNotBlank(), onClick = { questions.clear(); questions += VisitPlanner.prepQuestions(concern, records) }) {
        Text(stringResource(R.string.visits_suggest))
    }
    if (questions.isNotEmpty()) {
        Text(stringResource(R.string.visits_not_advice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        questions.forEachIndexed { i, q ->
            Row {
                OutlinedTextField(q, { questions[i] = it }, Modifier.weight(1f))
                TextButton(onClick = { questions.removeAt(i) }) { Text(stringResource(R.string.visits_remove)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { questions += "" }) { Text(stringResource(R.string.visits_add)) }
            Button(onClick = {
                val list = questions.filter { it.isNotBlank() }
                scope.launch {
                    db.visitItemDao().upsert(
                        VisitItemEntity(
                            itemId = UUID.randomUUID().toString(), patientId = selfId, itemType = VisitItemType.PREP,
                            rawInput = concern, structuredJson = json.encodeToString(PrepChecklist.serializer(), PrepChecklist(concern, list)),
                            hasConflict = false, conflictNote = null, createdAt = Instant.now(),
                        ),
                    )
                    questions.clear()
                    concern = ""
                }
            }) { Text(stringResource(R.string.visits_save)) }
        }
    }
}

@Composable
private fun RecapPane(db: PatnubDatabase, selfId: String) {
    var text by rememberSaveable { mutableStateOf("") }
    var recap by remember { mutableStateOf<Recap?>(null) }
    val records by remember(selfId) { db.clinicalRecordDao().observeIssued(selfId) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Text(stringResource(R.string.visits_recap_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(text, { text = it; recap = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.visits_recap_hint)) }, minLines = 4)
    Button(enabled = text.isNotBlank(), onClick = { recap = VisitPlanner.recap(text, records) }) { Text(stringResource(R.string.visits_organize)) }
    recap?.let { r ->
        RecapCard(r)
        Text(stringResource(R.string.visits_review_first), style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            scope.launch {
                db.visitItemDao().upsert(
                    VisitItemEntity(
                        itemId = UUID.randomUUID().toString(), patientId = selfId, itemType = VisitItemType.RECAP,
                        rawInput = text, structuredJson = json.encodeToString(Recap.serializer(), r),
                        hasConflict = r.conflicts.isNotEmpty(), conflictNote = r.conflicts.joinToString("\n").ifEmpty { null },
                        createdAt = Instant.now(),
                    ),
                )
                text = ""
                recap = null
            }
        }) { Text(stringResource(R.string.visits_save)) }
    }
}

@Composable
private fun RecapCard(r: Recap) = OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.visits_patient_reported), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.conflicts.forEach {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Lines(stringResource(R.string.visits_summary), r.summary)
        Lines(stringResource(R.string.visits_todo), r.todo)
        Lines(stringResource(R.string.visits_follow), r.followUp)
        Lines(stringResource(R.string.visits_clarify), r.clarify)
    }
}

@Composable
private fun Lines(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, fontWeight = FontWeight.Bold)
    lines.forEach { Text("• $it") }
}

@Composable
private fun Saved(db: PatnubDatabase, selfId: String, type: VisitItemType) {
    val items by remember(selfId, type) { db.visitItemDao().observe(selfId, type) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    if (items.isEmpty()) return
    Text(stringResource(R.string.visits_saved), style = MaterialTheme.typography.titleMedium)
    items.forEach { item ->
        val body = item.structuredJson ?: return@forEach
        Column {
            if (type == VisitItemType.PREP) {
                val c = json.decodeFromString(PrepChecklist.serializer(), body)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(c.concern, fontWeight = FontWeight.Bold)
                        c.questions.forEach { Text("☐ $it") }
                    }
                }
            } else {
                RecapCard(json.decodeFromString(Recap.serializer(), body))
            }
            TextButton(onClick = { scope.launch { db.visitItemDao().delete(item.itemId, selfId) } }) { Text(stringResource(R.string.visits_delete)) }
        }
    }
}
