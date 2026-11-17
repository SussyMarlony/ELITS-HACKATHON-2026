package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.assistant.Assistant
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.transfer.RecordImporter
import ai.patnub.ui.components.PlaceholderPane
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/** The five Patient tabs from the build plan. */
enum class PatientTab(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val description: Int,
) {
    Assistant(R.string.tab_assistant, R.drawable.ic_tab_assistant, R.string.placeholder_assistant),
    Records(R.string.tab_records, R.drawable.ic_tab_records, R.string.placeholder_records),
    Scan(R.string.tab_scan, R.drawable.ic_tab_scan, R.string.placeholder_scan),
    Medicines(R.string.tab_medicines, R.drawable.ic_tab_medicines, R.string.placeholder_medicines),
    Visits(R.string.tab_visits, R.drawable.ic_tab_visits, R.string.placeholder_visits),
}

@Composable
fun PatientNavigationBar(selected: PatientTab, onSelect: (PatientTab) -> Unit) {
    NavigationBar {
        PatientTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

/** What the patient screens need once the phone has a profile. */
class PatientServices(
    val db: PatnubDatabase,
    val importer: RecordImporter,
    val assistant: Assistant,
    val selfId: String,
    val checkInCode: String,
)

@Composable
fun PatientScreen(tab: PatientTab, services: PatientServices?, onTab: (PatientTab) -> Unit, modifier: Modifier = Modifier) {
    when {
        services == null -> PlaceholderPane(title = tab.label, body = tab.description, modifier = modifier)
        tab == PatientTab.Assistant -> AssistantScreen(services.assistant, services.selfId, modifier)
        tab == PatientTab.Records -> RecordsScreen(services.db, services.selfId, modifier)
        tab == PatientTab.Scan -> ScanScreen(services.checkInCode, services.importer, onSaved = { onTab(PatientTab.Records) }, modifier = modifier)
        // Medicines and Visits arrive in Phase 5.
        else -> PlaceholderPane(title = tab.label, body = tab.description, modifier = modifier)
    }
}
