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
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
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
