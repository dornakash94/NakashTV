package tv.nakash.domain

/**
 * Error reports that leave the TV: what failed and on which device, never the provider login or a stream URL
 * (those carry the user name and password in their path).
 */
object DiagReport {
    private val URL = Regex("""(?i)\b(?:https?|rtmp|rtsp)://[^\s"'<>]+""")

    /** Removes URLs and every given secret (user name, password, server) from [text]. */
    fun redact(text: String?, secrets: Collection<String>): String {
        if (text.isNullOrEmpty()) return ""
        var out = URL.replace(text, "<url>")
        // Longest first, so a password that contains the user name is removed whole.
        for (s in secrets.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }) out = out.replace(s, "***", ignoreCase = true)
        return out
    }

    /** The cause chain as "Class: message" lines, redacted, at most [max] entries. */
    fun causes(t: Throwable, secrets: Collection<String>, max: Int = 8): List<String> =
        generateSequence(t) { it.cause }.take(max).map { "${it.javaClass.name}: ${redact(it.message, secrets).take(300)}" }.toList()

    /** The first [max] stack frames of [t] (the app's own frames are the useful ones, so they are kept first). */
    fun stack(t: Throwable, max: Int = 25): List<String> {
        val root = generateSequence(t) { it.cause }.last()
        val frames = (t.stackTrace.toList() + if (root !== t) root.stackTrace.toList() else emptyList()).map { it.toString() }
        val own = frames.filter { it.startsWith("tv.nakash.") }
        return (own + frames.filterNot { it.startsWith("tv.nakash.") }).distinct().take(max)
    }
}
