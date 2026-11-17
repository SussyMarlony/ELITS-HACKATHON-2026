package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.UserRole
import ai.patnub.ui.components.PlaceholderPane
import ai.patnub.ui.components.RecordCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Timeline of clinician-approved records imported by QR, newest first. */
@Composable
fun RecordsScreen(db: PatnubDatabase, selfId: String, modifier: Modifier = Modifier) {
    val records by remember(selfId) { db.clinicalRecordDao().observeIssued(selfId) }.collectAsState(initial = null)
    val clinicians by remember { db.userDao().observeByRole(UserRole.CLINICIAN) }.collectAsState(initial = emptyList())
    val list = records ?: return
    if (list.isEmpty()) {
        PlaceholderPane(title = PatientTab.Records.label, body = R.string.placeholder_records, modifier = modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.recordId }) { r ->
            val c = clinicians.find { it.userId == r.clinicianId }
            RecordCard(
                assessment = r.assessment,
                encounterDate = r.encounterDate.toString(),
                approvedBy = c?.let { "${it.displayName}, ${it.specialty}, PRC ${it.prcLicenseNo} · ${it.facility}" } ?: r.clinicianId,
                meds = r.meds, instructions = r.instructions, warnings = r.warnings,
                followUp = r.followUp, version = r.version,
            )
        }
    }
}
