package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.UserEntity
import ai.patnub.identity.validateProfile
import ai.patnub.ui.components.SectionCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The phone owner's profile: the details their clinic sees in the check-in code. */
@Composable
fun ProfilesScreen(
    db: PatnubDatabase,
    selfId: String,
    onUpdateProfile: suspend (name: String, age: Int, barangay: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val user by remember(selfId) { db.userDao().observeById(selfId) }.collectAsState(initial = null)
    var editing by rememberSaveable { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val u = user ?: return@Column
        Text(stringResource(R.string.profile_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (editing) {
            ProfileEditForm(
                user = u,
                onCancel = { editing = false },
                onSave = { name, age, barangay ->
                    onUpdateProfile(name, age, barangay)
                    editing = false
                },
            )
        } else {
            ProfileCard(u, onEdit = { editing = true })
        }
    }
}

@Composable
private fun ProfileCard(user: UserEntity, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            user.displayName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(user.displayName, style = MaterialTheme.typography.titleLarge)
                    val subtitle = listOfNotNull(
                        user.age?.let { stringResource(R.string.onboarding_age) + " $it" },
                        user.barangay,
                    ).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.profile_edit)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow(stringResource(R.string.onboarding_name), user.displayName)
            user.age?.let { DetailRow(stringResource(R.string.onboarding_age), it.toString()) }
            user.barangay?.let { DetailRow(stringResource(R.string.onboarding_barangay), it) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileEditForm(
    user: UserEntity,
    onCancel: () -> Unit,
    onSave: suspend (name: String, age: Int, barangay: String) -> Unit,
) {
    var name by rememberSaveable(user.displayName) { mutableStateOf(user.displayName) }
    var age by rememberSaveable(user.age) { mutableStateOf(user.age?.toString() ?: "") }
    var barangay by rememberSaveable(user.barangay) { mutableStateOf(user.barangay ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionCard(stringResource(R.string.profile_edit)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text(stringResource(R.string.onboarding_name)) }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = age, onValueChange = { age = it }, singleLine = true,
            label = { Text(stringResource(R.string.onboarding_age)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = barangay, onValueChange = { barangay = it }, singleLine = true,
            label = { Text(stringResource(R.string.onboarding_barangay)) }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
            Button(
                enabled = !saving,
                onClick = {
                    error = validateProfile(name, age)
                    if (error == null) {
                        saving = true
                        scope.launch {
                            try {
                                onSave(name, age.trim().toInt(), barangay)
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                                saving = false
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.profile_save)) }
        }
    }
}
