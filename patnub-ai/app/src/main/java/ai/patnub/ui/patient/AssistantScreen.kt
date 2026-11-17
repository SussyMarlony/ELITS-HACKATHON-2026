package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.assistant.Assistant
import ai.patnub.assistant.Reply
import ai.patnub.assistant.Source
import ai.patnub.ui.components.EmptyState
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface ChatItem {
    data class Question(val text: String) : ChatItem
    data class Answer(val reply: Reply, val text: String = "", val done: Boolean = false) : ChatItem
}

/**
 * Conversation kept above the tab switch, so changing tabs never loses it, and answers keep
 * streaming in [scope] (the app root's scope) while another tab is open.
 * shortcut: memory only, cleared when the app process ends; persist to Room if patients ask for history.
 */
class ChatState(private val scope: CoroutineScope) {
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false)
        private set

    fun send(question: String, assistant: Assistant, selfId: String) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        busy = true
        items += ChatItem.Question(q)
        scope.launch {
            val index = items.size
            try {
                val reply = assistant.ask(q, selfId)
                items += ChatItem.Answer(reply)
                if (reply is Reply.Answer) {
                    reply.text.collect { piece ->
                        val cur = items[index] as ChatItem.Answer
                        items[index] = cur.copy(text = cur.text + piece)
                    }
                }
            } finally {
                (items.getOrNull(index) as? ChatItem.Answer)?.let { items[index] = it.copy(done = true) }
                busy = false
            }
        }
    }
}

@Composable
fun AssistantScreen(chat: ChatState, assistant: Assistant, selfId: String, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val last = chat.items.lastOrNull()
    // Follow new messages. While text streams in, follow only if the last message is on screen,
    // so someone reading an earlier answer is not pulled away.
    LaunchedEffect(chat.items.size) { if (chat.items.isNotEmpty()) listState.animateScrollToItem(chat.items.lastIndex) }
    LaunchedEffect((last as? ChatItem.Answer)?.text?.length) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (chat.items.isNotEmpty() && lastVisible == chat.items.lastIndex) {
            listState.scrollToItem(chat.items.lastIndex, scrollOffset = Int.MAX_VALUE / 2) // clamps to the end
        }
    }

    fun send(text: String) {
        chat.send(text, assistant, selfId)
        input = ""
    }

    Column(modifier) {
        if (chat.items.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(stringResource(R.string.assistant_empty_title), stringResource(R.string.assistant_intro))
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    listOf(R.string.suggest_1, R.string.suggest_2, R.string.suggest_3).forEach { id ->
                        val text = stringResource(id)
                        SuggestionChip(onClick = { send(text) }, label = { Text(text) })
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(chat.items) { _, item ->
                    when (item) {
                        is ChatItem.Question -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                item.text,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                        is ChatItem.Answer -> AnswerBubble(item)
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.assistant_hint)) },
                modifier = Modifier.weight(1f), maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            Button(onClick = { send(input) }, enabled = !chat.busy && input.isNotBlank()) { Text(stringResource(R.string.assistant_send)) }
        }
    }
}

@Composable
private fun AnswerBubble(item: ChatItem.Answer) {
    val context = LocalContext.current
    val bubble = Modifier.widthIn(max = 340.dp)
    when (val r = item.reply) {
        is Reply.Emergency -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.emergency_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.emergency_body), color = MaterialTheme.colorScheme.onErrorContainer)
                Button(
                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:911".toUri())) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.emergency_call)) }
            }
        }
        is Reply.AskPrescriber -> Card(bubble) {
            Text(
                if (r.prescriber != null) stringResource(R.string.ask_prescriber_named, r.prescriber) else stringResource(R.string.ask_prescriber),
                Modifier.padding(16.dp),
            )
        }
        Reply.NoMatch -> Card(bubble) { Text(stringResource(R.string.no_match), Modifier.padding(16.dp)) }
        is Reply.Answer -> Card(bubble, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.fallback) Label(stringResource(R.string.answer_fallback))
                else if (r.simulated) Label(stringResource(R.string.answer_simulated), MaterialTheme.colorScheme.error)
                if (item.text.isEmpty() && !item.done) TypingDots() else Text(item.text)
                if (item.done && r.sources.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.sources.forEach { SourceChip(it) } }
                    Label(stringResource(R.string.answer_disclaimer))
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)

/** Three softly pulsing dots until the first piece of the answer arrives. */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f, label = "dot$i",
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
            )
            Box(Modifier.size(7.dp).alpha(a).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun SourceChip(s: Source) = AssistChip(
    onClick = {},
    label = { Text(s.label, color = if (s.fromClinician) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) },
)
