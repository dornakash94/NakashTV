package tv.nakash.domain

object DiscoveryOrder {
    /** Alternate newest movies and newest series while preserving each catalog's own order. */
    fun <T> interleave(movies:List<T>,series:List<T>):List<T> = buildList {
        for(index in 0 until maxOf(movies.size,series.size)) {
            movies.getOrNull(index)?.let(::add)
            series.getOrNull(index)?.let(::add)
        }
    }
}
