package ai.patnub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A titled white card. Used for detail sections so long content reads in chunks. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = titleColor)
            content()
        }
    }
}

/** Bulleted lines with a hanging indent, so wrapped lines align with the text, not the dot. */
@Composable
fun BulletList(lines: List<String>, color: Color = MaterialTheme.colorScheme.onSurface) {
    lines.forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", color = color)
            Text(line, color = color, modifier = Modifier.weight(1f))
        }
    }
}
