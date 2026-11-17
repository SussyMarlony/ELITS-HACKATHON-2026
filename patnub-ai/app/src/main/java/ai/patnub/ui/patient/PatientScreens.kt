package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.ui.components.PlaceholderPane
import ai.patnub.ui.components.QrCode
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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

/** [checkInCode] is the phone owner's check-in code text, null until onboarding is done. */
@Composable
fun PatientScreen(tab: PatientTab, checkInCode: String?, modifier: Modifier = Modifier) {
    // Each tab becomes its own screen composable as the phases land.
    when {
        tab == PatientTab.Scan && checkInCode != null -> CheckInPane(checkInCode, modifier)
        else -> PlaceholderPane(title = tab.label, body = tab.description, modifier = modifier)
    }
}

/** Shown to the clinic so it can address records to this phone (spec v1.2 §4.6.1). */
@Composable
private fun CheckInPane(code: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.checkin_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.checkin_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        QrCode(
            text = code,
            contentDescription = stringResource(R.string.checkin_title),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            stringResource(R.string.placeholder_scan),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
