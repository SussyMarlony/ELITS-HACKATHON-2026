package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.transfer.OpenedRecord
import ai.patnub.transfer.RecordImporter
import ai.patnub.transfer.RejectReason
import ai.patnub.transfer.ScanOutcome
import ai.patnub.ui.components.BrightScreenWhileVisible
import ai.patnub.ui.components.EmptyState
import ai.patnub.ui.components.QrCode
import ai.patnub.ui.components.QrScanner
import ai.patnub.ui.components.RecordCard
import ai.patnub.ui.components.ResultIcon
import ai.patnub.ui.components.confirmHaptic
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

private sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Preview(val opened: OpenedRecord, val replacesVersion: Int?) : ScanState
    data class Rejected(val reason: RejectReason) : ScanState
    data object Saved : ScanState
}

/** Scan tab: the check-in code to show the clinic, and scanning a record QR from the clinic. */
@Composable
fun ScanScreen(checkInCode: String, importer: RecordImporter, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    BackHandler(enabled = state != ScanState.Idle) { state = ScanState.Idle }

    if (state == ScanState.Scanning) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.scan_instructions), style = MaterialTheme.typography.titleMedium)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
            ) {
                QrScanner(
                    onResult = { text ->
                        view.confirmHaptic()
                        scope.launch {
                            state = when (val out = importer.preview(text)) {
                                is ScanOutcome.Preview -> ScanState.Preview(out.opened, out.replacesVersion)
                                is ScanOutcome.Rejected -> ScanState.Rejected(out.reason)
                            }
                        }
                    },
                    deniedText = stringResource(R.string.scan_camera_denied),
                    modifier = Modifier.fillMaxSize(),
                )
                // Viewfinder: shows where to place the code.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.72f)
                        .aspectRatio(1f)
                        .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
                )
            }
            OutlinedButton(onClick = { state = ScanState.Idle }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        }
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            is ScanState.Preview -> {
                val r = s.opened.record
                val staff = s.opened.staff
                Text(stringResource(R.string.scan_preview_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(
                    if (s.replacesVersion != null) stringResource(R.string.scan_preview_replaces, s.replacesVersion) else stringResource(R.string.scan_preview_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                RecordCard(
                    assessment = r.assessment.ifBlank { null },
                    encounterDate = r.encounter_date,
                    approvedBy = "${staff.name}, ${staff.profession}, PRC ${staff.license_no} · ${s.opened.license.clinic_name}",
                    meds = r.meds, instructions = r.instructions, warnings = r.warnings,
                    followUp = r.follow_up, version = r.rv,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { state = ScanState.Idle }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_dont_save)) }
                    Button(
                        onClick = { scope.launch { importer.save(s.opened); view.confirmHaptic(); state = ScanState.Saved } },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.scan_save)) }
                }
            }
            is ScanState.Rejected -> EmptyState(
                title = stringResource(R.string.scan_rejected_title),
                body = stringResource(
                    when (s.reason) {
                        RejectReason.NOT_PATNUB -> R.string.scan_rejected_not_patnub
                        RejectReason.TAMPERED -> R.string.scan_rejected_tampered
                        RejectReason.NOT_FOR_THIS_PHONE -> R.string.scan_rejected_other_phone
                        RejectReason.EXPIRED -> R.string.scan_rejected_expired
                        RejectReason.ALREADY_SAVED -> R.string.scan_rejected_already
                    },
                ),
                action = stringResource(R.string.scan_try_again),
                onAction = { state = ScanState.Scanning },
                icon = { ResultIcon("!", MaterialTheme.colorScheme.error) },
            )
            ScanState.Saved -> EmptyState(
                title = stringResource(R.string.scan_saved_title),
                body = stringResource(R.string.scan_saved_body),
                action = stringResource(R.string.scan_view_records),
                onAction = onSaved,
                icon = { ResultIcon("✓", MaterialTheme.colorScheme.primary) },
            )
            else -> {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scan_record_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.scan_record_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { state = ScanState.Scanning }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(stringResource(R.string.scan_start))
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.checkin_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().semantics { heading() })
                        Text(stringResource(R.string.checkin_body), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                        BrightScreenWhileVisible()
                        QrCode(
                            text = checkInCode,
                            contentDescription = stringResource(R.string.checkin_title),
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }
        }
    }
}
