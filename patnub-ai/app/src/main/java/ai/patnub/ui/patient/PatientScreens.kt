package ai.patnub.ui.patient

import ai.patnub.R
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

@Composable
fun PatientScreen(tab: PatientTab, modifier: Modifier = Modifier) {
    // Each tab becomes its own screen composable as the phases land.
    PlaceholderPane(title = tab.label, body = tab.description, modifier = modifier)
}
