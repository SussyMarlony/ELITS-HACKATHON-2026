package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.assistant.Assistant
import ai.patnub.assistant.Reply
import ai.patnub.assistant.Source
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch

private sealed interface ChatItem {
    data class Question(val text: String) : ChatItem
    data class Answer(val reply: Reply, val text: String = "", val done: Boolean = false) : ChatItem
}

@Composable
fun AssistantScreen(assistant: Assistant, selfId: String, modifier: Modifier = Modifier) {
    val items = remember { mutableStateListOf<ChatItem>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex) }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        items += ChatItem.Question(q)
        scope.launch {
            try {
                val reply = assistant.ask(q, selfId)
                val index = items.size
                items += ChatItem.Answer(reply)
                if (reply is Reply.Answer) {
                    reply.text.collect { piece ->
                        val cur = items[index] as ChatItem.Answer
                        items[index] = cur.copy(text = cur.text + piece)
                    }
                }
                items[index] = (items[index] as ChatItem.Answer).copy(done = true)
            } finally {
                busy = false
            }
        }
    }

    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Text(stringResource(R.string.assistant_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(items) { _, item ->
                when (item) {
                    is ChatItem.Question -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            item.text,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    is ChatItem.Answer -> AnswerBubble(item)
                }
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.assistant_hint)) },
                modifier = Modifier.weight(1f), maxLines = 4,
            )
            Button(onClick = ::send, enabled = !busy && input.isNotBlank()) { Text(stringResource(R.string.assistant_send)) }
        }
    }
}

@Composable
private fun AnswerBubble(item: ChatItem.Answer) {
    val context = LocalContext.current
    when (val r = item.reply) {
        is Reply.Emergency -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.emergency_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.emergency_body), color = MaterialTheme.colorScheme.onErrorContainer)
                Button(
                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:911".toUri())) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.emergency_call)) }
            }
        }
        is Reply.AskPrescriber -> Card {
            Text(
                if (r.prescriber != null) stringResource(R.string.ask_prescriber_named, r.prescriber) else stringResource(R.string.ask_prescriber),
                Modifier.padding(16.dp),
            )
        }
        Reply.NoMatch -> Card { Text(stringResource(R.string.no_match), Modifier.padding(16.dp)) }
        is Reply.Answer -> Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.simulated && !r.fallback) {
                    Text(stringResource(R.string.answer_simulated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (r.fallback) {
                    Text(stringResource(R.string.answer_fallback), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(item.text.ifEmpty { "…" })
                if (item.done) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.sources.forEach { SourceChip(it) } }
                    Text(stringResource(R.string.answer_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SourceChip(s: Source) = AssistChip(
    onClick = {},
    label = { Text(s.label, color = if (s.fromClinician) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) },
)
