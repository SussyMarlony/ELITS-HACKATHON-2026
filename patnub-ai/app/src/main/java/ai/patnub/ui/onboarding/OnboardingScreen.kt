package ai.patnub.ui.onboarding

import ai.patnub.ui.components.Motion
import ai.patnub.ui.components.appear
import ai.patnub.ui.components.rememberAppearTracker
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import ai.patnub.R
import ai.patnub.identity.validateProfile
import ai.patnub.ui.components.LogoMark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import ai.patnub.ui.components.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** First launch: the phone's owner enters the details their clinic will see in the check-in code. */
@Composable
fun OnboardingScreen(
    onCreate: suspend (name: String, age: Int, barangay: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var barangay by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // First run only, so it may take a moment: each part rises in, 40 ms after the one above.
    val appear = rememberAppearTracker(windowMs = 1_000)
    var n = 0
    @Composable fun Modifier.enter() = appear(appear, n, n++, rise = 20f)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LogoMark(88.dp, Modifier.enter().padding(top = 16.dp))
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.enter().semantics { heading() })
        Text(stringResource(R.string.onboarding_body), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.enter())
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text(stringResource(R.string.onboarding_name)) }, modifier = Modifier.enter().fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = age, onValueChange = { age = it }, singleLine = true,
            label = { Text(stringResource(R.string.onboarding_age)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.enter().fillMaxWidth(),
        )
        OutlinedTextField(
            value = barangay, onValueChange = { barangay = it }, singleLine = true,
            label = { Text(stringResource(R.string.onboarding_barangay)) }, modifier = Modifier.enter().fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(Motion.spring()), exit = fadeOut() + shrinkVertically(Motion.spring(0.25f))) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        Button(
            enabled = !saving,
            modifier = Modifier.enter().fillMaxWidth(),
            onClick = {
                error = validateProfile(name, age)
                if (error == null) {
                    saving = true
                    scope.launch {
                        try {
                            onCreate(name, age.trim().toInt(), barangay)
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                            saving = false
                        }
                    }
                }
            },
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(R.string.onboarding_continue))
        }
        Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.enter())
    }
}
