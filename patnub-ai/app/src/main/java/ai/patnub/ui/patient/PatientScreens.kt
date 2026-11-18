package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.assistant.Assistant
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.engine.EngineState
import ai.patnub.transfer.RecordImporter
import ai.patnub.ui.components.Motion
import ai.patnub.ui.components.PlaceholderPane
import ai.patnub.ui.components.pressScale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The five Patient tabs. Scan sits in the middle as the raised action button. */
enum class PatientTab(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val description: Int,
) {
    Assistant(R.string.tab_assistant, R.drawable.ic_tab_assistant, R.string.placeholder_assistant),
    Records(R.string.tab_records, R.drawable.ic_tab_records, R.string.placeholder_records),
    Scan(R.string.tab_scan, R.drawable.ic_tab_scan, R.string.placeholder_scan),
    Medicines(R.string.tab_medicines, R.drawable.ic_tab_medicines, R.string.placeholder_medicines),
    Profiles(R.string.tab_profiles, R.drawable.ic_tab_profile, R.string.placeholder_profiles),
}

/**
 * iOS-style bottom bar: a quiet tinted strip with a hairline on top, small
 * icon-over-label items, and Scan as a raised filled button in the center.
 */
@Composable
fun PatientNavigationBar(selected: PatientTab, onSelect: (PatientTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(72.dp)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PatientTab.entries.forEach { tab ->
                    if (tab == PatientTab.Scan) {
                        ScanTabButton(tab, selected == tab, Modifier.weight(1f)) { onSelect(tab) }
                    } else {
                        PatientTabItem(tab, selected == tab, Modifier.weight(1f)) { onSelect(tab) }
                    }
                }
            }
        }
    }
}

// Tabs are `selectable` with Role.Tab so TalkBack says e.g. "Selected, Records, Tab, 2 of 5".
@Composable
private fun PatientTabItem(tab: PatientTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(200, easing = Motion.EaseOut), label = "tabTint",
    )
    // The tapped icon dips and springs back with a little bounce: the tap caused it, so it may overshoot.
    val pop = remember { Animatable(1f) }
    val seenFirst = remember { booleanArrayOf(false) } // no pop for the tab selected at launch
    LaunchedEffect(selected) {
        val wasFirst = !seenFirst[0]
        seenFirst[0] = true
        if (selected && !wasFirst && Motion.enabled) {
            pop.snapTo(0.82f)
            pop.animateTo(1f, Motion.spring(0.35f, 0.55f))
        }
    }
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .pressScale(source, 0.92f)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab, interactionSource = source, indication = null),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The label below names the tab; the icon would only repeat it.
        Icon(
            painterResource(tab.icon), contentDescription = null, tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
        Text(stringResource(tab.label), color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ScanTabButton(tab: PatientTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(56.dp)
                .offset(y = (-4).dp)
                .pressScale(source, 0.9f)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab, interactionSource = source, indication = ripple()),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
                Icon(
                    painterResource(tab.icon),
                    contentDescription = stringResource(tab.label),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
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
fun PatientScreen(
    tab: PatientTab,
    services: PatientServices?,
    chat: ChatState,
    modelReady: Boolean,
    engineState: EngineState,
    modelName: String?,
    onManageModel: () -> Unit,
    onTab: (PatientTab) -> Unit,
    onUpdateProfile: suspend (name: String, age: Int, barangay: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tabs are switched many times a day, so the change is a quick cross-fade, not a slide.
    AnimatedContent(
        tab,
        transitionSpec = { fadeIn(tween(180, delayMillis = 40, easing = Motion.EaseOut)) togetherWith fadeOut(tween(90)) },
        label = "tab",
    ) { tab -> PatientTabContent(tab, services, chat, modelReady, engineState, modelName, onManageModel, onTab, onUpdateProfile, modifier) }
}

@Composable
private fun PatientTabContent(
    tab: PatientTab,
    services: PatientServices?,
    chat: ChatState,
    modelReady: Boolean,
    engineState: EngineState,
    modelName: String?,
    onManageModel: () -> Unit,
    onTab: (PatientTab) -> Unit,
    onUpdateProfile: suspend (name: String, age: Int, barangay: String) -> Unit,
    modifier: Modifier,
) {
    when {
        services == null -> PlaceholderPane(title = tab.label, body = tab.description, modifier = modifier)
        tab == PatientTab.Assistant -> AssistantScreen(chat, services.assistant, services.selfId, modelReady, onManageModel, modifier)
        tab == PatientTab.Records -> RecordsScreen(services.db, services.selfId, onScan = { onTab(PatientTab.Scan) }, modifier = modifier)
        tab == PatientTab.Scan -> ScanScreen(services.checkInCode, services.importer, onSaved = { onTab(PatientTab.Records) }, modifier = modifier)
        tab == PatientTab.Medicines -> MedicinesScreen(services.db, services.selfId, modifier)
        else -> ProfilesScreen(services.db, services.selfId, onUpdateProfile, engineState, modelName, onManageModel, modifier)
    }
}
