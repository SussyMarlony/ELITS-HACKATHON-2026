package ai.patnub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Brand90,
    onPrimaryContainer = Brand10,
    secondary = NeutralVariant50,
    secondaryContainer = Brand95,
    onSecondaryContainer = Brand10,
    tertiary = ClinicianBlue40,
    error = Danger40,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    onSurfaceVariant = NeutralVariant50,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Neutral94,
    surfaceContainerHighest = Neutral94,
    outline = NeutralVariant80,
    outlineVariant = Neutral94,
)

private val DarkColors = darkColorScheme(
    primary = Brand80,
    onPrimary = Brand20,
    primaryContainer = Brand30,
    onPrimaryContainer = Brand90,
    secondary = NeutralVariant80,
    secondaryContainer = Brand30,
    onSecondaryContainer = Brand90,
    tertiary = ClinicianBlue80,
    error = Danger80,
    background = Neutral6,
    onBackground = Neutral94,
    surface = Neutral6,
    onSurface = Neutral94,
    onSurfaceVariant = NeutralVariant80,
    surfaceContainerLowest = Neutral6,
    surfaceContainerLow = Neutral17,
    surfaceContainer = Neutral17,
    surfaceContainerHigh = Neutral24,
    surfaceContainerHighest = Neutral24,
    outline = NeutralVariant30,
    outlineVariant = Neutral24,
)

// Tighter tracking on large text, slightly heavier titles (Apple UI typography guidance).
private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

/**
 * App theme. Dynamic (wallpaper) color is intentionally off so the safety colors
 * (teal = app, blue = clinician, red = danger) stay consistent on every device.
 */
@Composable
fun PatnubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
