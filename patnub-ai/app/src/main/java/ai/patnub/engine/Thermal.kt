package ai.patnub.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** How hot the phone is, as far as answer length is concerned. */
enum class Heat { NORMAL, WARM, HOT }

/**
 * Phone heat from Android's thermal status (`PowerManager.currentThermalStatus`, Android 10+).
 * Ordinary apps can read it without permission, unlike `/sys/class/thermal`, which the engine's
 * own `thermalC` relies on and which is usually blocked. Long generations heat the phone and the
 * system then slows the CPU, so answers are shortened while it is warm instead of crawling.
 */
object Thermal {
    /** Android's raw thermal status, or null before Android 10 or if the service is missing. */
    fun status(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    }

    fun heat(context: Context): Heat = status(context)?.let(::heatFor) ?: Heat.NORMAL

    /**
     * MODERATE is where Android starts throttling noticeably; SEVERE and above are
     * heavy throttling up to imminent shutdown.
     */
    fun heatFor(status: Int): Heat = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> Heat.HOT
        status >= PowerManager.THERMAL_STATUS_MODERATE -> Heat.WARM
        else -> Heat.NORMAL
    }

    /** Answer length budget for the current heat: full, three quarters, or half. */
    fun maxTokens(base: Int, heat: Heat): Int = when (heat) {
        Heat.NORMAL -> base
        Heat.WARM -> base * 3 / 4
        Heat.HOT -> base / 2
    }
}
