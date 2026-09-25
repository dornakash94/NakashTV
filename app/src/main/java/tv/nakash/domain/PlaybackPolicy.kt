package tv.nakash.domain

object PlaybackPolicy {
    fun archiveAvailable(start: Long, end: Long, filler: Boolean, days: Int, now: Long): Boolean =
        !filler && days > 0 && end > start && start < now && start >= now - days * 86400L
    /** Whole-minute archive positions cannot enter the future or pass the program end. */
    fun archiveOffsetSeconds(start:Long,end:Long,now:Long,requested:Long):Long {
        val latest=(minOf(end,now)-start-1).coerceAtLeast(0)/60*60
        return requested.coerceIn(0,latest)/60*60
    }
    fun archiveMinutes(start: Long, end: Long): Int = ((end - start).coerceAtLeast(60) + 59).div(60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** The newest archive minutes lag the broadcast; catch-up never asks for anything closer to live than this. */
    const val LIVE_EDGE_SEC = 120L
    /** One timeshift request covers at most this much; playback chains the next chunk when it ends. */
    const val CHUNK_MAX_MIN = 360

    /**
     * Continuous channel catch-up: any whole minute inside the archive window, never past the live edge.
     * Independent of guide program boundaries, so it can reach into earlier shows.
     */
    fun timeshiftStart(requested: Long, now: Long, archiveDays: Int): Long {
        val earliest = now - archiveDays.coerceAtLeast(1) * 86400L + 60
        val latest = (now - LIVE_EDGE_SEC).coerceAtLeast(earliest)
        return requested.coerceIn(earliest, latest) / 60 * 60
    }

    /** Asked for beyond "now", like start-over always did: a server that keeps recording keeps the stream flowing. */
    const val FUTURE_MARGIN_MIN = 30

    /**
     * How long to stream from [start]: past the present (capped), never just to the guide's program end, so a show
     * that runs late is not cut off and playback carries straight on into the next show. If the server stops at
     * what it has recorded, playback chains the next chunk from where it stopped.
     */
    fun timeshiftMinutes(start: Long, now: Long): Int = ((now - start) / 60 + FUTURE_MARGIN_MIN).coerceIn(1, CHUNK_MAX_MIN.toLong()).toInt()

    /** Rewinding from live lands at least this far back, where the archive is certain to exist. */
    fun rewindLimit(now: Long): Long = now - LIVE_EDGE_SEC - 60

    /** Close enough to the broadcast that catch-up should hand over to the live stream. */
    fun nearLive(epoch: Long, now: Long): Boolean = now - epoch < LIVE_EDGE_SEC + 60

    /** Scrub step for the n-th rapid press in a row: taps move a minute, holding accelerates to 5 and 15. */
    fun scrubStepSeconds(streak: Int): Long = when { streak < 4 -> 60; streak < 10 -> 300; else -> 900 }

    /**
     * While scrubbing from [base] to [target], stop on the first program start crossed, so holding ◀ lands exactly
     * on the beginning of a show (and ▶ on the beginning of the next one). Starts equal to [base] are not crossed.
     */
    fun snapToProgramStart(base: Long, target: Long, starts: List<Long>): Long =
        if (target < base) starts.filter { it > target && it < base }.maxOrNull() ?: target
        else starts.filter { it > base && it < target }.minOrNull() ?: target
}
