package ai.patnub.ui

import ai.patnub.R
import ai.patnub.engine.Engine
import ai.patnub.engine.FakeEngine
import ai.patnub.ui.components.EngineStatusPill
import ai.patnub.ui.doctor.DoctorScreen
import ai.patnub.ui.doctor.DoctorStep
import ai.patnub.ui.patient.PatientNavigationBar
import ai.patnub.ui.patient.PatientScreen
import ai.patnub.ui.patient.PatientTab
import ai.patnub.ui.theme.PatnubTheme
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp

/** Who is using the phone right now. Real sign-in replaces this switch in a later phase. */
enum class Role(@StringRes val label: Int) {
    Patient(R.string.role_patient),
    Doctor(R.string.role_doctor),
}

/**
 * Root of the app: one Scaffold whose top bar holds the Patient/Doctor switch.
 * The bottom navigation bar is shown only in the Patient view.
 */
@Composable
fun PatnubApp(engine: Engine) {
    var role by rememberSaveable { mutableStateOf(Role.Patient) }
    var patientTab by rememberSaveable { mutableStateOf(PatientTab.Assistant) }
    var doctorStep by rememberSaveable { mutableStateOf(DoctorStep.WriteNote) }

    Scaffold(
        topBar = {
            PatnubTopBar(engine = engine, role = role, onRoleChange = { role = it })
        },
        bottomBar = {
            if (role == Role.Patient) {
                PatientNavigationBar(selected = patientTab, onSelect = { patientTab = it })
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (role) {
            Role.Patient -> PatientScreen(tab = patientTab, modifier = contentModifier)
            Role.Doctor -> DoctorScreen(
                step = doctorStep,
                onStepChange = { doctorStep = it },
                modifier = contentModifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatnubTopBar(engine: Engine, role: Role, onRoleChange: (Role) -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name))
                EngineStatusPill(engine)
            }
        },
        actions = {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(end = 12.dp)) {
                Role.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = role == option,
                        onClick = { onRoleChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = Role.entries.size),
                        icon = {},
                        label = { Text(stringResource(option.label)) },
                    )
                }
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
