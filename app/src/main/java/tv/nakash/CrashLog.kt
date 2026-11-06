package tv.nakash

import android.content.Context
import java.io.File

/**
 * The last crash, kept on the device (no logs reach us from relatives' TV boxes). Written from the default uncaught
 * exception handler, then the app goes down as before. Settings shows a one-line summary someone can photograph.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(format(error, thread.name, System.currentTimeMillis())) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** "2026-11-06 21:14 · NoSuchMethodError · ThumbnailGenerator.kt:84", or null when the app never crashed. */
    fun summary(ctx: Context): String? = runCatching { file(ctx).takeIf { it.isFile }?.useLines { it.firstOrNull() } }.getOrNull()

    internal fun format(error: Throwable, thread: String, at: Long): String {
        val root = generateSequence(error) { it.cause?.takeIf { c -> c !== it } }.last()
        val frame = (root.stackTrace.firstOrNull { it.className.startsWith("tv.nakash") } ?: root.stackTrace.firstOrNull())
            ?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
        val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of("Asia/Jerusalem"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return "$time · ${root.javaClass.simpleName} · $frame · v${BuildConfig.VERSION_NAME}\n" +
            "thread=$thread\n" + error.stackTraceToString().take(16_000)
    }
}
