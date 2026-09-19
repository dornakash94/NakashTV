package tv.nakash.domain

import tv.nakash.data.remote.LiveStreamDto

/**
 * Collapses backup / accessible / Russian variants into one logical channel.
 * Grouping key: shared epg_channel_id + same logo, OR same base name. The primary is the stream with
 * tv_archive = 1 (only primaries carry the archive), else the lowest `num`.
 */
object ChannelGrouper {

    data class Source(val streamId: Int, val kind: Normalizer.SourceKind, val tvArchive: Boolean, val archiveDays: Int, val rawName: String)

    data class LogicalChannel(
        val key: String,
        val displayName: String,
        val logo: String?,
        val epgChannelId: String?,
        val hasEpg: Boolean,
        val categoryIds: List<Int>,
        val order: Int,
        val sources: List<Source>,
    ) {
        val primary get() = sources.first()
        val archiveDays get() = sources.maxOf { it.archiveDays }
    }

    fun group(streams: List<LiveStreamDto>): List<LogicalChannel> {
        // Union-find: streams join a group when they share an epg id (real, not placeholder) OR the same base name.
        // Real data needs both: "ספורט 5 - (גיבוי 1)" has a different epg id than "ספורט 5", while the RUS variants
        // of yes channels share the epg id but have a different logo and a different name.
        val parent = IntArray(streams.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        val byEpg = HashMap<String, Int>(); val byName = HashMap<String, Int>()
        streams.forEachIndexed { i, s ->
            val epg = s.epgChannelId?.trim()
            if (epg != null && Normalizer.hasEpg(s)) byEpg.putIfAbsent(epg, i)?.let { union(i, it) }
            byName.putIfAbsent(Normalizer.baseName(s.name), i)?.let { union(i, it) }
        }
        val groups = LinkedHashMap<Int, MutableList<LiveStreamDto>>()
        streams.forEachIndexed { i, s -> groups.getOrPut(find(i)) { mutableListOf() }.add(s) }
        return groups.values.mapIndexed { idx, list ->
            val sorted = list.sortedWith(
                compareByDescending<LiveStreamDto> { (it.tvArchive ?: 0) == 1 }
                    .thenBy { Normalizer.sourceKind(it.name).ordinal }
                    .thenBy { it.num ?: Int.MAX_VALUE }
            )
            val prim = sorted.first()
            LogicalChannel(
                key = "ch:${prim.streamId}",
                displayName = Normalizer.displayName(prim.name),
                logo = Normalizer.imageUrl(prim.streamIcon),
                epgChannelId = prim.epgChannelId?.takeIf { Normalizer.hasEpg(prim) },
                hasEpg = Normalizer.hasEpg(prim),
                categoryIds = list.flatMap { it.categoryIds }.distinct().ifEmpty { listOfNotNull(prim.categoryId?.toIntOrNull()) },
                order = list.minOf { it.num ?: Int.MAX_VALUE }.let { if (it == Int.MAX_VALUE) idx else it },
                sources = sorted.map {
                    Source(it.streamId, Normalizer.sourceKind(it.name), (it.tvArchive ?: 0) == 1, it.tvArchiveDuration ?: 0, it.name)
                },
            )
        }.sortedBy { it.order }
    }
}
