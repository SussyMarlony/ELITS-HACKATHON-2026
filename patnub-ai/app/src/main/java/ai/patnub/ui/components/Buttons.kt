package ai.patnub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

// The app's buttons: Material 3 buttons that shrink on touch-down (Motion.kt `pressScale`).
// Same names and parameters as Material's, so screens import these instead of androidx.compose.material3.

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.Button(
        onClick, modifier.pressScale(source), enabled, shape, colors,
        contentPadding = contentPadding, interactionSource = source, content = content,
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.OutlinedButton(
        onClick, modifier.pressScale(source), enabled, shape, colors,
        border = border, contentPadding = contentPadding, interactionSource = source, content = content,
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.TextButton(
        onClick, modifier.pressScale(source), enabled, shape, colors,
        contentPadding = contentPadding, interactionSource = source, content = content,
    )
}
