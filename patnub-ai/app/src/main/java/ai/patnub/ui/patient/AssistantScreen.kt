package ai.patnub.ui.patient

import ai.patnub.R
import ai.patnub.assistant.Assistant
import ai.patnub.assistant.Reply
import ai.patnub.assistant.Source
import ai.patnub.ui.components.EmptyState
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import ai.patnub.assistant.Turn
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface ChatItem {
    data class Question(val text: String, val hasPhoto: Boolean = false) : ChatItem
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

    private var current: Assistant? = null

    /** Stops the answer being written; what arrived so far stays. */
    fun stop() = current?.cancel()

    /** Earlier exchanges, oldest first, for follow-up questions. Emergency and refusal cards are left out. */
    private fun history(): List<Turn> = items.mapNotNull { item ->
        when (item) {
            is ChatItem.Question -> Turn(fromPatient = true, text = item.text)
            is ChatItem.Answer -> {
                val r = item.reply
                if (r is Reply.Answer && !r.fallback && item.text.isNotBlank()) Turn(fromPatient = false, text = item.text.trim()) else null
            }
        }
    }

    fun send(question: String, assistant: Assistant, selfId: String, imagePath: String? = null) {
        val q = question.trim().ifEmpty { if (imagePath != null) "What is in this photo?" else "" }
        if (q.isEmpty() || busy) return
        busy = true
        current = assistant
        val earlier = history()
        items += ChatItem.Question(q, hasPhoto = imagePath != null)
        scope.launch {
            val index = items.size
            try {
                val reply = try {
                    assistant.ask(q, selfId, earlier, imagePath)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Reply.Failed(e.message)
                }
                items += ChatItem.Answer(reply)
                if (reply is Reply.Answer) {
                    try {
                        reply.text.collect { piece ->
                            val cur = items[index] as ChatItem.Answer
                            items[index] = cur.copy(text = cur.text + piece)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        items[index] = ChatItem.Answer(Reply.Failed(e.message))
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
fun AssistantScreen(
    chat: ChatState,
    assistant: Assistant,
    selfId: String,
    modelReady: Boolean,
    onManageModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Photo Picker: no storage permission; downscaled on IO so vision encoding stays fast on phones.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val out = java.io.File(context.cacheDir, "question-photo.jpg")
                if (withContext(Dispatchers.IO) { saveDownscaledPhoto(context, uri, out) }) photo = out.path
            }
        }
    }
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
        chat.send(text, assistant, selfId, photo)
        input = ""
        photo = null
    }

    Column(modifier) {
        if (chat.items.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(stringResource(R.string.assistant_empty_title), stringResource(R.string.assistant_intro))
                if (!modelReady) {
                    TextButton(onClick = onManageModel) { Text(stringResource(R.string.assistant_install_model), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
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
                                (if (item.hasPhoto) "📷 " else "") + item.text,
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
        if (photo != null) {
            InputChip(
                selected = true, onClick = { photo = null },
                label = { Text(stringResource(R.string.assistant_photo_attached)) },
                trailingIcon = { Text("×") },
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        // iMessage-style composer: + photo button, borderless field, circular send/stop.
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (assistant.supportsImages) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.assistant_photo),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            stringResource(R.string.assistant_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(input) }),
                        maxLines = 4,
                    )
                }
                if (chat.busy) {
                    CircleAction(
                        icon = R.drawable.ic_stop,
                        description = stringResource(R.string.assistant_stop),
                        background = MaterialTheme.colorScheme.surfaceContainerHighest,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { chat.stop() },
                    )
                } else {
                    val canSend = input.isNotBlank() || photo != null
                    CircleAction(
                        icon = R.drawable.ic_send_arrow,
                        description = stringResource(R.string.assistant_send),
                        background = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        enabled = canSend,
                        onClick = { send(input) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleAction(
    @androidx.annotation.DrawableRes icon: Int,
    description: String,
    background: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier
            .size(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
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
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=Rural+Health+Unit+or+hospital".toUri()))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.emergency_find_rhu), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
        is Reply.AskPrescriber -> Card(bubble) {
            Text(
                if (r.prescriber != null) stringResource(R.string.ask_prescriber_named, r.prescriber) else stringResource(R.string.ask_prescriber),
                Modifier.padding(16.dp),
            )
        }
        Reply.NoMatch -> Card(bubble) { Text(stringResource(R.string.no_match), Modifier.padding(16.dp)) }
        Reply.NeedsModel -> Card(bubble) { Text(stringResource(R.string.assistant_needs_model), Modifier.padding(16.dp)) }
        Reply.OffTopic -> Card(bubble) { Text(stringResource(R.string.assistant_off_topic), Modifier.padding(16.dp)) }
        is Reply.Failed -> Card(bubble) { Text(stringResource(R.string.assistant_failed), Modifier.padding(16.dp)) }
        is Reply.Answer -> Card(bubble, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.fallback) Label(stringResource(R.string.answer_fallback))
                when {
                    item.text.isBlank() && !item.done -> ReadingProgress()
                    // The model stopped without a visible reply (e.g. ran out of length while reasoning).
                    item.text.isBlank() -> Text(stringResource(R.string.assistant_empty_answer), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(lightMarkdown(item.text.trim()))
                }
                if (item.done) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (r.sources.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.sources.forEach { SourceChip(it) } }
                    }
                    if (!r.grounded) Label(stringResource(R.string.answer_general_knowledge))
                    Label(stringResource(R.string.answer_disclaimer))
                    if (r.unreviewed) Label(stringResource(R.string.answer_unreviewed))
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)

/** Three softly pulsing dots plus elapsed wait time while the model processes the prompt. */
@Composable
private fun ReadingProgress() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TypingDots()
        Text(
            if (seconds >= 1) stringResource(R.string.assistant_reading_elapsed, seconds)
            else stringResource(R.string.assistant_reading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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

/** Downscales [uri] so the longest side is at most [maxDim] px, saving vision encoding time. */
private fun saveDownscaledPhoto(context: Context, uri: Uri, out: java.io.File, maxDim: Int = 768): Boolean =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
            return@runCatching true
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching false
        val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        true
    }.getOrDefault(false)

@Composable
private fun SourceChip(s: Source) = AssistChip(
    onClick = {},
    label = { Text(s.label, color = if (s.fromClinician) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) },
)

/**
 * The models write light Markdown. Show **bold** as bold and "* " / "- " lines as bullets;
 * leave everything else as typed.
 */
internal fun lightMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val bullet = Regex("^\\s*[*-]\\s+").find(raw)
        val line = if (bullet != null) "•  " + raw.substring(bullet.range.last + 1) else raw.replace(Regex("^#+\\s*"), "")
        var rest = line
        while (true) {
            val start = rest.indexOf("**")
            val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
            if (start < 0 || end < 0) { append(rest); break }
            append(rest.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
            rest = rest.substring(end + 2)
        }
    }
}
