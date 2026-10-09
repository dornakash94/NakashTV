package tv.nakash.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picks the TV's display mode that shows a video's frame rate evenly (25 fps on 50 Hz, 23.976 on 23.976 / 47.95,
 * 29.97 on 59.94...). Without it, 25 fps on a 60 Hz output holds frames for uneven times: motion looks choppy and,
 * where the rates almost match, one frame is held longer every few seconds. Same resolution only, so nothing else
 * about the picture changes. Returns 0 when nothing better than the current mode exists.
 */
object FrameRateMatcher {
    /** How far a mode may be from an exact multiple of the frame rate: 0.04 % (60 Hz is 0.1 % off 29.97 x 2). */
    private const val TOLERANCE = 0.0004

    fun bestModeId(activity: Activity, fps: Float): Int {
        if (fps <= 1f) return 0
        val display = displayOf(activity) ?: return 0
        val current = display.mode
        if (fits(current.refreshRate, fps) != null) return 0
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { m -> fits(m.refreshRate, fps)?.let { err -> m to err } }
            // The most exact match; between equally exact ones the higher refresh (50 over 25, 47.95 over 23.976).
            .minWithOrNull(compareBy<Pair<Display.Mode, Double>> { it.second }.thenByDescending { it.first.refreshRate })
            ?.first?.modeId ?: 0
    }

    /** The relative error when [refresh] shows [fps] as a whole number of refreshes per frame (1-3), else null. */
    internal fun fits(refresh: Float, fps: Float): Double? {
        val ratio = refresh.toDouble() / fps
        val k = ratio.roundToInt()
        if (k !in 1..3) return null
        val err = abs(ratio - k) / k
        return err.takeIf { it <= TOLERANCE }
    }

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
}
