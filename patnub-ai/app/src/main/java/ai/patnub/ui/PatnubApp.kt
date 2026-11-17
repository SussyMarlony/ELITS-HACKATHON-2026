package ai.patnub.ui

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineState
import ai.patnub.models.ModelStore
import ai.patnub.ui.models.ModelScreen
import ai.patnub.identity.PatientIdentity
import ai.patnub.identity.SelfProfile
import ai.patnub.transfer.CheckIn
import ai.patnub.ui.onboarding.OnboardingScreen
import ai.patnub.ui.components.EngineStatusPill
import ai.patnub.ui.components.LogoMark
import ai.patnub.ui.patient.PatientNavigationBar
import ai.patnub.ui.patient.PatientScreen
import ai.patnub.ui.patient.PatientServices
import ai.patnub.ui.patient.ChatState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import ai.patnub.ui.patient.PatientTab
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.launch

/**
 * Root of the patient app: top bar with the engine status pill, five patient tabs.
 * Clinicians use the separate offline web app in `doctor-web/` (spec v1.2).
 */
/** What the model screen needs from the app process. */
class ModelHost(val store: ModelStore, val loadedPackageId: () -> String?, val startModel: () -> Unit)

@Composable
fun PatnubApp(
    engine: Engine,
    identity: PatientIdentity,
    models: ModelHost,
    makeServices: (selfId: String, checkInCode: String) -> PatientServices,
) {
    var patientTab by rememberSaveable { mutableStateOf(PatientTab.Assistant) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val engineState by engine.state.collectAsState()
    // Preview has no identity; treat it as an onboarded phone.
    val profile by identity.profile.collectAsState()
    val scope = rememberCoroutineScope()
    val chat = remember { ChatState(scope) }
    val checkInCode = remember(profile) {
        val user = (profile as? SelfProfile.Ready)?.user
        val pub = identity.publicKey()
        if (user != null && pub != null) CheckIn.of(user.displayName, user.age ?: 0, user.barangay, pub).encode() else null
    }
    val services = remember(checkInCode) {
        val user = (profile as? SelfProfile.Ready)?.user
        if (user != null && checkInCode != null) makeServices(user.userId, checkInCode) else null
    }
    val debuggable = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    Scaffold(
        topBar = {
            PatnubTopBar(
                engine = engine,
                onManage = { showModels = true },
                onDebugReset = if (debuggable && profile is SelfProfile.Ready) {
                    { scope.launch { identity.resetForDemo() } }
                } else {
                    null
                },
            )
        },
        bottomBar = {
            if (profile !is SelfProfile.Missing && !showModels) {
                PatientNavigationBar(selected = patientTab, onSelect = { patientTab = it })
            }
        },
    ) { innerPadding ->
        // Back from another tab returns to the Assistant before leaving the app.
        BackHandler(enabled = profile is SelfProfile.Ready && patientTab != PatientTab.Assistant && !showModels) { patientTab = PatientTab.Assistant }
        BackHandler(enabled = showModels) { showModels = false }
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // The keyboard pushes content up instead of covering inputs (chat box, forms).
            .consumeWindowInsets(innerPadding)
            .imePadding()
        when {
            profile == SelfProfile.Missing -> OnboardingScreen(onCreate = { n, a, b -> identity.create(n, a, b) }, modifier = modifier)
            showModels -> ModelScreen(models.store, engineState, models.loadedPackageId(), models.startModel, modifier)
            else -> PatientScreen(
                tab = patientTab, services = services, chat = chat,
                modelReady = engineState == EngineState.Ready || engineState == EngineState.Generating,
                onManageModel = { showModels = true },
                onTab = { patientTab = it }, modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatnubTopBar(engine: Engine, onManage: () -> Unit, onDebugReset: (() -> Unit)?) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoMark(30.dp)
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    EngineStatusPill(engine, onManage)
                }
            }
        },
        actions = {
            onDebugReset?.let { TextButton(onClick = it) { Text(stringResource(R.string.debug_reset)) } }
        },
    )
}

