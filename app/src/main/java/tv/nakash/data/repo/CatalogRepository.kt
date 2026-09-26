package tv.nakash.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tv.nakash.data.local.CategoryEntity
import tv.nakash.data.local.ChannelDao
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.ChannelSourceEntity
import tv.nakash.data.local.EpisodeEntity
import tv.nakash.data.local.MovieEntity
import tv.nakash.data.local.SeasonEntity
import tv.nakash.data.local.SeriesEntity
import tv.nakash.data.local.VodDao
import tv.nakash.data.remote.ApiProvider
import tv.nakash.domain.ChannelGrouper
import tv.nakash.domain.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** Pinned categories, by server id (ilvip.net). Everything else folds under "World". */
val PINNED_LIVE_CATEGORIES = mapOf(1 to 0, 30 to 1, 92 to 2, 20 to 3) // ישראל, ספורט, מוזיקה, רדיו
const val RADIO_CATEGORY_ID = 20

@Singleton
class CatalogRepository @Inject constructor(
    private val api: ApiProvider,
    private val channelDao: ChannelDao,
    private val vodDao: VodDao,
) {
    // ---------- reads (UI observes Room only) ----------
    fun channels(): Flow<List<ChannelEntity>> = channelDao.channels()
    fun channelsInCategory(id: Int) = channelDao.channelsInCategory(id)
    fun channelsByIds(ids: List<Int>) = channelDao.channelsByIds(ids)
    fun liveCategories() = channelDao.categories("live")
    fun categories(kind: String) = channelDao.categories(kind)
    suspend fun channel(id: Int) = channelDao.channel(id)
    suspend fun sources(channelId: Int) = channelDao.sources(channelId)

    fun newestMovies(limit: Int = 40) = vodDao.newest(limit)
    fun searchMovies() = vodDao.searchMovies()
    fun searchSeries() = vodDao.searchSeries()
    fun topRatedMovies() = vodDao.topRated(java.time.Year.now().value - 3)
    fun moviesByGenre(g: String) = vodDao.byGenre(g)
    fun movie(id: Int) = vodDao.movie(id)
    fun recentlyUpdatedSeries(limit: Int = 40) = vodDao.recentlyUpdated(limit)
    fun seriesByGenre(g: String) = vodDao.seriesByGenre(g)
    fun series(id: Int) = vodDao.series(id)
    fun seasons(seriesId: Int) = vodDao.seasons(seriesId)
    suspend fun episodeById(id:String)=vodDao.episodeById(id)
    fun episodes(seriesId: Int, season: Int) = vodDao.episodes(seriesId, season)
    suspend fun episode(seriesId: Int, season: Int, number: Int) = vodDao.episode(seriesId, season, number)
    suspend fun nextEpisode(seriesId: Int, season: Int, number: Int) = vodDao.nextEpisode(seriesId, season, number)

    suspend fun search(q: String) = withContext(Dispatchers.IO) {
        val nq = Normalizer.clean(q)
        SearchResult(channelDao.search(nq), vodDao.search(nq), vodDao.searchSeries(nq))
    }
    data class SearchResult(val channels: List<ChannelEntity>, val movies: List<MovieEntity>, val series: List<SeriesEntity>)

    // ---------- sync (called by WorkManager / first login) ----------
    suspend fun syncLive() = withContext(Dispatchers.IO) {
        val cats = api.api().liveCategories()
        val streams = api.api().liveStreams()
        val catEntities = cats.mapIndexed { i, c ->
            val id = c.categoryId.toInt()
            CategoryEntity(id, "live", Normalizer.categoryName(c.categoryName), c.categoryName, order = PINNED_LIVE_CATEGORIES[id] ?: (100 + i), pinned = id in PINNED_LIVE_CATEGORIES)
        }
        val grouped = ChannelGrouper.group(streams)
        val chans = grouped.mapIndexed { i, g ->
            ChannelEntity(
                id = g.primary.streamId, displayName = g.displayName, logo = g.logo, epgChannelId = g.epgChannelId,
                hasEpg = g.hasEpg, categoryIds = g.categoryIds.joinToString(","), order = g.order, number = i + 1,
                archiveDays = g.archiveDays, isRadio = RADIO_CATEGORY_ID in g.categoryIds,
            )
        }
        val srcs = grouped.flatMap { g -> g.sources.mapIndexed { r, s -> ChannelSourceEntity(s.streamId, g.primary.streamId, s.kind.name, r, s.tvArchive, s.archiveDays) } }
        channelDao.replaceLive(catEntities, chans, srcs)
    }

    suspend fun syncVod() = withContext(Dispatchers.IO) {
        val cats = api.api().vodCategories()
        channelDao.upsertCategories(cats.mapIndexed { i, c -> CategoryEntity(c.categoryId.toInt(), "vod", Normalizer.categoryName(c.categoryName), c.categoryName, i) })
        val movies = api.api().vodStreams().map { m ->
            MovieEntity(
                id = m.streamId, title = m.title ?: Normalizer.titleWithoutYear(m.name), year = m.year?.toIntOrNull(),
                poster = Normalizer.imageUrl(m.streamIcon), backdrop = null, plot = Normalizer.plot(m.plot),
                genres = Normalizer.genres(m.genre).joinToString(","), cast = m.cast, director = m.director, rating = m.rating,
                runtimeMin = m.runTimeMin?.takeIf { it > 0 }, durationSec = null, country = null, tmdbId = null,
                trailer = m.youtubeTrailer, added = m.added ?: 0, categoryIds = m.categoryIds.joinToString(","),
                containerExt = m.containerExtension ?: "mp4",
            )
        }
        // keep enriched fields from a previous get_vod_info
        val enriched = vodDao.enrichedMovies().associateBy { it.id }
        val merged = movies.map { m -> enriched[m.id]?.let { old -> m.copy(backdrop = old.backdrop, durationSec = old.durationSec, country = old.country, tmdbId = old.tmdbId, infoLoaded = true, trailer = m.trailer ?: old.trailer) } ?: m }
        vodDao.upsert(merged)
    }

    suspend fun syncSeries() = withContext(Dispatchers.IO) {
        val cats = api.api().seriesCategories()
        channelDao.upsertCategories(cats.mapIndexed { i,c -> CategoryEntity(c.categoryId.toInt(), "series",Normalizer.categoryName(c.categoryName),c.categoryName,i) })
        val list = api.api().series().map { s ->
            val cover = Normalizer.imageUrl(s.cover)
            SeriesEntity(
                id = s.seriesId, title = s.title ?: Normalizer.titleWithoutYear(s.name), year = s.year?.toIntOrNull(), cover = cover,
                backdrop = Normalizer.backdrop(s.backdropPath.mapNotNull(Normalizer::imageUrl), cover), plot = Normalizer.plot(s.plot),
                genres = Normalizer.genres(s.genre).joinToString(","), cast = s.cast, rating = s.rating, runtimeMin = s.runTimeMin?.takeIf { it > 0 },
                lastModified = s.lastModified ?: 0, categoryIds = s.categoryIds.joinToString(","),
            )
        }
        val cachedDetails = vodDao.detailedSeries().associateBy { it.id }
        val merged = list.map { s -> cachedDetails[s.id]?.let { old -> s.copy(detailLoadedAt = old.detailLoadedAt) } ?: s }
        vodDao.upsertSeries(merged)
    }

    /** Detail page: backdrop, tmdb id, real duration, country. Cached for a week. */
    suspend fun ensureMovieInfo(id: Int) = withContext(Dispatchers.IO) {
        val m = vodDao.movieOnce(id) ?: return@withContext
        if (m.infoLoaded) return@withContext
        val info = runCatching { api.api().vodInfo(id).info }.getOrNull() ?: return@withContext
        vodDao.upsert(m.copy(
            backdrop = Normalizer.backdrop(info.backdropPath.mapNotNull(Normalizer::imageUrl), m.poster),
            durationSec = info.durationSecs?.takeIf { it > 0 }, country = info.country, tmdbId = info.tmdbId,
            trailer = m.trailer ?: info.youtubeTrailer, plot = m.plot ?: Normalizer.plot(info.plot), infoLoaded = true,
        ))
    }

    /** Seasons + all episodes in one call. Cached 12h; refreshed sooner if lastModified moved. */
    suspend fun ensureSeriesDetail(id: Int, force: Boolean = false) = withContext(Dispatchers.IO) {
        val s = vodDao.seriesOnce(id) ?: return@withContext
        val fresh = System.currentTimeMillis() - s.detailLoadedAt < 12 * 3600_000L && s.detailLoadedAt > s.lastModified * 1000
        if (fresh && !force) return@withContext
        val r = api.api().seriesInfo(id)
        val seasons = r.seasons.mapNotNull { d -> d.seasonNumber?.let { n -> SeasonEntity("$id:$n", id, n, d.name ?: "עונה $n", d.episodeCount ?: r.episodes[n.toString()]?.size ?: 0, Normalizer.imageUrl(d.coverBig ?: d.cover)) } }
        val eps = r.episodes.flatMap { (seasonKey, list) ->
            val seasonNum = seasonKey.toIntOrNull() ?: 0
            list.map { e ->
                val parsed = Normalizer.episodeTitle(e.title)
                EpisodeEntity(
                    id = e.id, seriesId = id, season = e.season ?: parsed.season ?: seasonNum, number = e.episodeNum ?: parsed.episode ?: 0,
                    title = parsed.title, durationSec = e.info?.durationSecs, containerExt = e.containerExtension ?: "mp4",
                    plot = Normalizer.plot(e.info?.plot), image = Normalizer.imageUrl(e.info?.movieImage), added = e.added ?: 0,
                )
            }
        }
        // seasons missing from `seasons` but present in `episodes`
        val known = seasons.map { it.number }.toSet()
        val extra = eps.map { it.season }.distinct().filter { it !in known }.map { n -> SeasonEntity("$id:$n", id, n, "עונה $n", eps.count { it.season == n }, s.cover) }
        vodDao.upsertSeasons(seasons + extra)
        vodDao.upsertEpisodes(eps)
        vodDao.upsertSeries(s.copy(detailLoadedAt = System.currentTimeMillis()))
    }
}
