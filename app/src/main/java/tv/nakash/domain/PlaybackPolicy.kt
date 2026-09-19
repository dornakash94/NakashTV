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
}
