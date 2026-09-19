package tv.nakash.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import tv.nakash.data.local.ChannelDao
import tv.nakash.data.local.EpgDao
import tv.nakash.data.local.EpgEntity
import tv.nakash.data.remote.ApiProvider
import tv.nakash.domain.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRepository @Inject constructor(
    private val api: ApiProvider,
    private val client: OkHttpClient,
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
) {
    fun range(epgId: String, from: Long, to: Long): Flow<List<EpgEntity>> = epgDao.range(epgId, from, to)
    suspend fun nowPlaying(epgId: String?): EpgEntity? = epgId?.let { epgDao.nowPlaying(it, now()) }?.takeIf { !it.isFiller }
    suspend fun nowAndNext(epgId: String?): Pair<EpgEntity?, EpgEntity?> {
        if (epgId == null) return null to null
        val n = now()
        val list = epgDao.rangeMany(listOf(epgId), n, n + 6 * 3600)
        val cur = list.firstOrNull { it.start <= n && it.end > n }
        val next = list.firstOrNull { it.start > n }
        return (cur?.takeIf { !it.isFiller }) to (next?.takeIf { !it.isFiller })
    }
    suspend fun gridRows(epgIds: List<String>, from: Long, to: Long) = epgDao.rangeMany(epgIds, from, to).groupBy { it.epgChannelId }
    suspend fun search(q: String) = epgDao.search(Normalizer.clean(q), now() - 32 * 86400)

    /**
     * Fast path for one channel when the XMLTV hasn't landed yet: get_short_epg (Base64 titles).
     */
    suspend fun refreshShort(streamId: Int, epgId: String, channelName: String) = withContext(Dispatchers.IO) {
        val r = runCatching { api.api().shortEpg(streamId, 8) }.getOrNull() ?: return@withContext
        val items = r.listings.mapNotNull { l ->
            val s = l.startTs ?: return@mapNotNull null; val e = l.stopTs ?: return@mapNotNull null
            val title = Normalizer.decodeBase64(l.title)
            EpgEntity("$epgId:$s", epgId, s, e, title, Normalizer.decodeBase64(l.description), isFiller = Normalizer.isFiller(channelName, title, 0, ((e - s) / 60).toInt()))
        }
        epgDao.insertAll(items)
    }

    /**
     * Full XMLTV import, streamed with XmlPullParser (the file can be large). Keeps 7 days ahead, 32 back.
     * Filler detection runs per channel/day after parsing.
     */
    suspend fun syncXmltv() = withContext(Dispatchers.IO) {
        val url = api.urls().xmltv()
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) { resp.close(); error("xmltv ${resp.code}") }
        val names = HashMap<String, String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        resp.body!!.byteStream().use { input ->
            parser.setInput(input, null)
            var ev = parser.eventType
            val batch = ArrayList<EpgEntity>(500)
            val perDay = HashMap<String, MutableMap<String, Int>>() // "$epg:$day" -> title -> count
            var epg: String? = null; var start = 0L; var end = 0L; var title = ""; var desc = ""; var inTitle = false; var inDesc = false
            var displayName: String? = null; var inDisplay = false; var chanId: String? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> chanId = parser.getAttributeValue(null, "id")
                        "display-name" -> inDisplay = true
                        "programme" -> { epg = parser.getAttributeValue(null, "channel"); start = parseTs(parser.getAttributeValue(null, "start")); end = parseTs(parser.getAttributeValue(null, "stop")); title = ""; desc = "" }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                    XmlPullParser.TEXT -> when {
                        inTitle -> title += parser.text
                        inDesc -> desc += parser.text
                        inDisplay -> displayName = parser.text
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "display-name" -> { inDisplay = false; if (chanId != null && displayName != null) names.putIfAbsent(chanId, displayName!!) }
                        "channel" -> { chanId = null; displayName = null }
                        "programme" -> {
                            val e = epg
                            if (e != null && end > start && end > now() - 33 * 86400 && start < now() + 8 * 86400) {
                                val day = "$e:${start / 86400}"
                                val counts = perDay.getOrPut(day) { HashMap() }
                                counts[title] = (counts[title] ?: 0) + 1
                                batch += EpgEntity("$e:$start", e, start, end, title.trim(), desc.trim(), isFiller = false)
                                if (batch.size >= 500) { epgDao.insertAll(batch); batch.clear() }
                            }
                        }
                    }
                }
                ev = parser.next()
            }
            if (batch.isNotEmpty()) epgDao.insertAll(batch)
            markFiller(names, perDay)
        }
        epgDao.purgeBefore(now() - 33 * 86400)
    }

    private suspend fun markFiller(names: Map<String, String>, perDay: Map<String, MutableMap<String, Int>>) {
        // Filler = title equals channel name, or a ≥3h title repeating ≥4 times a day. Cheap second pass over today±1.
        val n = now()
        val ids = perDay.keys.map { it.substringBefore(':') }.distinct()
        if (ids.isEmpty()) return
        val rows = epgDao.rangeMany(ids, n - 86400, n + 2 * 86400)
        val flagged = rows.filter { r ->
            val day = "${r.epgChannelId}:${r.start / 86400}"
            val cnt = perDay[day]?.get(r.title) ?: 0
            Normalizer.isFiller(names[r.epgChannelId] ?: "", r.title, cnt, ((r.end - r.start) / 60).toInt())
        }.map { it.copy(isFiller = true) }
        if (flagged.isNotEmpty()) epgDao.insertAll(flagged)
    }

    private fun now() = System.currentTimeMillis() / 1000
    companion object {
        private val XMLTV_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        fun parseTs(s: String?): Long = try { OffsetDateTime.parse(s!!.trim(), XMLTV_TS).toEpochSecond() } catch (_: Throwable) { 0L }
    }
}
