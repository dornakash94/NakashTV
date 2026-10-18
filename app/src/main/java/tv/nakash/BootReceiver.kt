package tv.nakash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * "הפעלה עם הדלקת המכשיר" (on by default): opens NakashTV when the device finishes starting up. Android 10+ lets an
 * app open itself from the background only with "display over other apps" (SYSTEM_ALERT_WINDOW); without it this
 * does nothing, and the setting says so and opens that permission screen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS || !StartOnBoot.enabled(ctx) || !StartOnBoot.allowed(ctx)) return
        // Not in the middle of the device's own start-up: wait until the screen is on, then a few seconds more for the
        // launcher and the HDMI output to settle (a video started into that could stay frozen on some boxes).
        val pending = goAsync()
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var awakeAt = 0L
        fun step() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (awakeAt == 0L && power?.isInteractive != false) awakeAt = now
            if ((awakeAt > 0 && now - awakeAt >= SETTLE_MS) || now - startedAt >= MAX_WAIT_MS) {
                if (power?.isInteractive != false) runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                pending.finish(); return
            }
            main.postDelayed(::step, 500)
        }
        step()
    }

    companion object {
        private const val SETTLE_MS = 4_000L
        private const val MAX_WAIT_MS = 25_000L
        private val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")
    }
}

object StartOnBoot {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("startup", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("start_on_boot", true)
    fun set(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("start_on_boot", on).apply()
    /** Whether Android lets the app open itself at start-up (always before Android 10). */
    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)
    /** The system screen that grants "display over other apps" (some TVs hide it: then the app's info screen). */
    fun openPermission(ctx: Context) {
        val uri = android.net.Uri.parse("package:${ctx.packageName}")
        val tries = listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
