package ai.patnub.ui

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineState
import ai.patnub.models.ModelCatalog
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
import ai.patnub.ui.components.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
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
import ai.patnub.ui.components.TextButton
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
            // Leaves downward with the Models push and comes back up with the pop.
            AnimatedVisibility(
                visible = profile !is SelfProfile.Missing && !showModels,
                enter = if (Motion.enabled) slideInVertically(Motion.spring(0.4f)) { it } + fadeIn() else fadeIn(),
                exit = if (Motion.enabled) slideOutVertically(Motion.spring(0.3f)) { it } + fadeOut() else fadeOut(),
            ) {
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
        val page = when {
            profile == SelfProfile.Missing -> Page.Onboarding
            showModels -> Page.Models
            else -> Page.Patient
        }
        // Models is a push: in from the right, back out to the right (same path both ways).
        AnimatedContent(page, transitionSpec = { pageTransition(initialState, targetState) }, label = "page") { p ->
            when (p) {
                Page.Onboarding -> OnboardingScreen(onCreate = { n, a, b -> identity.create(n, a, b) }, modifier = modifier)
                Page.Models -> ModelScreen(models.store, engineState, models.loadedPackageId(), models.startModel, modifier)
                Page.Patient -> PatientScreen(
                    tab = patientTab, services = services, chat = chat,
                    modelReady = engineState == EngineState.Ready || engineState == EngineState.Generating,
                    engineState = engineState,
                    // The running model, else the chosen one if it is installed (loads on next start).
                    modelName = (models.loadedPackageId()?.let { id -> ModelCatalog.packages.find { it.id == id } }
                        ?: models.store.selected.takeIf { models.store.isReady(it) })?.title,
                    onManageModel = { showModels = true },
                    onTab = { patientTab = it },
                    onUpdateProfile = { n, a, b -> identity.update(n, a, b) },
                    modifier = modifier,
                )
            }
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


private enum class Page { Onboarding, Patient, Models }

private fun pageTransition(from: Page, to: Page): ContentTransform {
    if (!Motion.enabled) return fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    val push = tween<IntOffset>(380, easing = Motion.EaseDrawer)
    return when {
        to == Page.Models -> slideInHorizontally(push) { it } togetherWith
            (slideOutHorizontally(push) { -it / 4 } + fadeOut(tween(380)))
        from == Page.Models -> (slideInHorizontally(push) { -it / 4 } + fadeIn(tween(380))) togetherWith
            slideOutHorizontally(push) { it }
        // Onboarding finished: a calm cross-fade with the new content settling in.
        else -> (fadeIn(tween(300, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.EaseOut), initialScale = 0.97f)) togetherWith
            fadeOut(tween(150))
    }.apply { targetContentZIndex = if (to == Page.Models) 1f else 0f }
}
