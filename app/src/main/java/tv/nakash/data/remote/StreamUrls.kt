package tv.nakash.data.remote

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Standard Xtream stream URLs. We deliberately do NOT use `direct_source` (server-managed tokens that rotate).
 * Server allows m3u8 + ts for live; VOD/series are progressive mp4.
 */
class StreamUrls(private val baseUrl: String, private val user: String, private val pass: String) {
    private val base = baseUrl.trimEnd('/')
    fun live(streamId: Int, ext: String = "m3u8") = "$base/live/$user/$pass/$streamId.$ext"
    fun movie(streamId: Int, ext: String) = "$base/movie/$user/$pass/$streamId.${ext.ifBlank { "mp4" }}"
    fun episode(episodeId: String, ext: String) = "$base/series/$user/$pass/$episodeId.${ext.ifBlank { "mp4" }}"
    /** Catch-up: start is the program start (server timezone is UTC), duration in minutes. */
    fun timeshift(streamId: Int, startEpochSec: Long, durationMin: Int): String {
        val start = LocalDateTime.ofEpochSecond(startEpochSec, 0, ZoneOffset.UTC).format(TS_FMT)
        return "$base/streaming/timeshift.php?username=$user&password=$pass&stream=$streamId&start=$start&duration=$durationMin"
    }
    fun xmltv() = "$base/xmltv.php?username=$user&password=$pass"
    companion object { private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm") }
}
