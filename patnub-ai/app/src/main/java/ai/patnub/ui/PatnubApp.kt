package ai.patnub.ui

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.FakeEngine
import ai.patnub.ui.components.EngineStatusPill
import ai.patnub.ui.patient.PatientNavigationBar
import ai.patnub.ui.patient.PatientScreen
import ai.patnub.ui.patient.PatientTab
import ai.patnub.ui.theme.PatnubTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Root of the patient app: top bar with the engine status pill, five patient tabs.
 * Clinicians use the separate offline web app in `doctor-web/` (spec v1.2).
 */
@Composable
fun PatnubApp(engine: Engine) {
    var patientTab by rememberSaveable { mutableStateOf(PatientTab.Assistant) }

    Scaffold(
        topBar = { PatnubTopBar(engine = engine) },
        bottomBar = { PatientNavigationBar(selected = patientTab, onSelect = { patientTab = it }) },
    ) { innerPadding ->
        PatientScreen(
            tab = patientTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatnubTopBar(engine: Engine) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name))
                EngineStatusPill(engine)
            }
        },
    )
}

@Preview(showSystemUi = true)
@Composable
private fun PatnubAppPreview() {
    PatnubTheme {
        PatnubApp(FakeEngine())
    }
}
