package ai.patnub.ui

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.FakeEngine
import ai.patnub.identity.PatientIdentity
import ai.patnub.identity.SelfProfile
import ai.patnub.ui.onboarding.OnboardingScreen
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.launch

/**
 * Root of the patient app: top bar with the engine status pill, five patient tabs.
 * Clinicians use the separate offline web app in `doctor-web/` (spec v1.2).
 */
@Composable
fun PatnubApp(engine: Engine, identity: PatientIdentity?) {
    var patientTab by rememberSaveable { mutableStateOf(PatientTab.Assistant) }
    // Preview has no identity; treat it as an onboarded phone.
    val profile = identity?.profile?.collectAsState()?.value ?: SelfProfile.Loading
    val scope = rememberCoroutineScope()
    val debuggable = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    Scaffold(
        topBar = {
            PatnubTopBar(
                engine = engine,
                onDebugReset = if (debuggable && identity != null && profile is SelfProfile.Ready) {
                    { scope.launch { identity.resetForDemo() } }
                } else {
                    null
                },
            )
        },
        bottomBar = {
            if (profile !is SelfProfile.Missing) {
                PatientNavigationBar(selected = patientTab, onSelect = { patientTab = it })
            }
        },
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (profile) {
            SelfProfile.Missing -> OnboardingScreen(onCreate = { n, a, b -> identity!!.create(n, a, b) }, modifier = modifier)
            else -> PatientScreen(tab = patientTab, modifier = modifier)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatnubTopBar(engine: Engine, onDebugReset: (() -> Unit)?) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name))
                EngineStatusPill(engine)
            }
        },
        actions = {
            onDebugReset?.let { TextButton(onClick = it) { Text(stringResource(R.string.debug_reset)) } }
        },
    )
}

@Preview(showSystemUi = true)
@Composable
private fun PatnubAppPreview() {
    PatnubTheme {
        PatnubApp(FakeEngine(), identity = null)
    }
}
