package ai.patnub.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** Centered empty or result state: the logo, a title, one sentence and at most one action. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    icon: @Composable () -> Unit = { LogoMark(64.dp) },
) {
    Column(
        modifier.appearOnce().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The icon settles in with a small spring a beat after the text: a result arriving, not a page loading.
        val pop = remember { Animatable(if (Motion.enabled) 0.8f else 1f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, Motion.spring(0.45f, 0.6f)) }
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) { icon() }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
    }
}
