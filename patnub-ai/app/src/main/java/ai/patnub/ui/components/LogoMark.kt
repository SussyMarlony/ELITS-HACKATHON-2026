package ai.patnub.ui.components

import ai.patnub.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/** The patnub mark, tinted with the theme's primary so it stays legible in dark mode. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_mark),
        contentDescription = null, // decorative; the app name is always next to it or announced
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        modifier = modifier.size(size),
    )
}
