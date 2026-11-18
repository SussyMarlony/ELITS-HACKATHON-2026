package ai.patnub.ui.components

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI

/**
 * Shared motion values, after Apple's fluid-interface guidance: critically damped springs by default,
 * bounce only where the user's own tap caused the motion, and a strong ease-out for enters.
 */
object Motion {
    /** Strong ease-out: starts fast so the UI feels like it answered at once. */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** iOS sheet/push curve. */
    val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** Apple's two spring knobs: [response] in seconds (lower = snappier), [damping] 1 = no overshoot. */
    fun <T> spring(response: Float = 0.35f, damping: Float = 1f): FiniteAnimationSpec<T> {
        val omega = 2 * PI.toFloat() / response
        return spring(dampingRatio = damping, stiffness = omega * omega)
    }

    /**
     * False when the user turned off animations (Developer options or Accessibility "Remove animations").
     * Callers then skip movement and keep only what aids understanding.
     */
    val enabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()
}

/** Shrinks slightly while [source] is pressed: instant feedback on touch-down, released by a spring. */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.97f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed && Motion.enabled) pressed else 1f,
        // Down is quick and firm; the release springs back with the faintest give.
        animationSpec = if (isPressed) Motion.spring(0.12f) else Motion.spring(0.3f, 0.75f),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** `clickable` with the same press shrink as the app's buttons. */
@Composable
fun Modifier.pressClickable(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return pressScale(source).clickable(source, ripple(), enabled = enabled, role = role, onClick = onClick)
}

/**
 * Remembers which items already appeared, so an enter animation plays once: for the first screenful
 * when a screen opens (within [windowMs]), never again when an item scrolls back into view.
 */
class AppearTracker(private val windowMs: Long = 450) {
    private val seen = HashSet<Any>()
    private val start = SystemClock.uptimeMillis()

    /** Items already on screen before this tracker existed (e.g. earlier chat messages). */
    fun markSeen(keys: Iterable<Any>) { seen.addAll(keys) }

    internal fun claim(key: Any): Boolean = seen.add(key) && SystemClock.uptimeMillis() - start < windowMs
}

@Composable
fun rememberAppearTracker(windowMs: Long = 450): AppearTracker = remember { AppearTracker(windowMs) }

/**
 * Fades the element in and lifts it [rise] into place. [index] staggers a list (40 ms apart, capped),
 * and only movement is dropped when animations are off.
 */
@Composable
fun Modifier.appear(tracker: AppearTracker, key: Any, index: Int = 0, rise: Float = 16f): Modifier {
    val animate = remember(key) { tracker.claim(key) && Motion.enabled }
    val progress = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay(40L * index.coerceAtMost(6))
            progress.animateTo(1f, Motion.spring(0.45f))
        }
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise.dp.toPx()
    }
}

/** [appear] for a single element shown once, such as an empty state. */
@Composable
fun Modifier.appearOnce(rise: Float = 12f): Modifier = appear(rememberAppearTracker(), Unit, rise = rise)
