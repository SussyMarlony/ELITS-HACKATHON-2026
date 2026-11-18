package ai.patnub.ui.patient

import ai.patnub.ui.components.appear
import ai.patnub.ui.components.rememberAppearTracker
import ai.patnub.R
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.UserRole
import ai.patnub.ui.components.EmptyState
import androidx.compose.ui.res.stringResource
import ai.patnub.ui.components.RecordCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Timeline of clinician-approved records imported by QR, newest first. */
@Composable
fun RecordsScreen(db: PatnubDatabase, selfId: String, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val records by remember(selfId) { db.clinicalRecordDao().observeIssued(selfId) }.collectAsState(initial = null)
    val clinicians by remember { db.userDao().observeByRole(UserRole.CLINICIAN) }.collectAsState(initial = emptyList())
    val list = records ?: return
    if (list.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.records_empty_title),
            body = stringResource(R.string.placeholder_records),
            action = stringResource(R.string.scan_start),
            onAction = onScan,
            modifier = modifier,
        )
        return
    }
    val appear = rememberAppearTracker()
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(list, key = { _, r -> r.recordId }) { i, r ->
            val c = clinicians.find { it.userId == r.clinicianId }
            RecordCard(
                assessment = r.assessment,
                encounterDate = r.encounterDate.toString(),
                approvedBy = c?.let { "${it.displayName}, ${it.specialty} · ${it.facility}" } ?: r.clinicianId,
                meds = r.meds, instructions = r.instructions, warnings = r.warnings,
                followUp = r.followUp, version = r.version,
                modifier = Modifier.appear(appear, r.recordId, i),
            )
        }
    }
}
