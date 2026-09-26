package tv.nakash.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE isActive = 1 ORDER BY `order`") fun channels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE isActive = 1 AND (',' || categoryIds || ',') LIKE '%,' || :categoryId || ',%' ORDER BY `order`")
    fun channelsInCategory(categoryId: Int): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: Int): ChannelEntity?
    @Query("SELECT * FROM channels WHERE id IN (:ids)") fun channelsByIds(ids: List<Int>): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE isActive = 1 AND displayName LIKE '%' || :q || '%' ORDER BY `order` LIMIT 40") suspend fun search(q: String): List<ChannelEntity>
    @Query("SELECT * FROM channel_sources WHERE channelId = :channelId ORDER BY rank") suspend fun sources(channelId: Int): List<ChannelSourceEntity>
    @Query("SELECT * FROM categories WHERE kind = :kind AND hidden = 0 ORDER BY pinned DESC, `order`") fun categories(kind: String): Flow<List<CategoryEntity>>
    @Upsert suspend fun upsertChannels(list: List<ChannelEntity>)
    @Upsert suspend fun upsertSources(list: List<ChannelSourceEntity>)
    @Upsert suspend fun upsertCategories(list: List<CategoryEntity>)
    @Query("UPDATE channels SET isActive = 0") suspend fun deactivateAll()
    /**
     * Channels missing from the provider's list end up inactive: all are switched off, then the current list is
     * written back as active (one transaction, so readers never see an empty list). A `NOT IN (:ids)` with ~12k ids
     * exceeds the 999-variable limit of SQLite on Android 11 and older, which most TV streamers run.
     */
    @Transaction
    suspend fun replaceLive(cats: List<CategoryEntity>, chans: List<ChannelEntity>, srcs: List<ChannelSourceEntity>) {
        upsertCategories(cats); deactivateAll(); upsertChannels(chans.map { if (it.isActive) it else it.copy(isActive = true) }); upsertSources(srcs)
    }
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg WHERE epgChannelId = :epgId AND start <= :now AND `end` > :now LIMIT 1") suspend fun nowPlaying(epgId: String, now: Long): EpgEntity?
    @Query("SELECT * FROM epg WHERE epgChannelId = :epgId AND `end` > :from AND start < :to ORDER BY start") fun range(epgId: String, from: Long, to: Long): Flow<List<EpgEntity>>
    @Query("SELECT * FROM epg WHERE epgChannelId IN (:epgIds) AND `end` > :from AND start < :to ORDER BY epgChannelId, start") suspend fun rangeMany(epgIds: List<String>, from: Long, to: Long): List<EpgEntity>
    @Query("SELECT * FROM epg WHERE isFiller = 0 AND title LIKE '%' || :q || '%' AND `end` > :from ORDER BY start LIMIT 40") suspend fun search(q: String, from: Long): List<EpgEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<EpgEntity>)
    @Query("DELETE FROM epg WHERE `end` < :before") suspend fun purgeBefore(before: Long)
}

@Dao
interface VodDao {
    @Query("SELECT * FROM movies WHERE infoLoaded = 1") suspend fun enrichedMovies(): List<MovieEntity>
    @Query("SELECT * FROM series WHERE detailLoadedAt > 0") suspend fun detailedSeries(): List<SeriesEntity>
    @Query("SELECT * FROM movies ORDER BY added DESC LIMIT :limit") fun newest(limit: Int): Flow<List<MovieEntity>>
    // Only what search needs (no plots/backdrops): far less to read each time the table changes.
    @Query("SELECT id, title, year, poster, `cast`, director, genres FROM movies ORDER BY added DESC") fun searchMovies(): Flow<List<MovieSearchRow>>
    @Query("SELECT id, title, year, cover, `cast`, genres FROM series ORDER BY lastModified DESC") fun searchSeries(): Flow<List<SeriesSearchRow>>
    @Query("SELECT * FROM movies WHERE rating >= 7 AND year >= :minYear ORDER BY rating DESC LIMIT 40") fun topRated(minYear: Int): Flow<List<MovieEntity>>
    @Query("SELECT * FROM movies WHERE (',' || genres || ',') LIKE '%,' || :genre || ',%' ORDER BY added DESC LIMIT 40") fun byGenre(genre: String): Flow<List<MovieEntity>>
    @Query("SELECT * FROM movies WHERE (',' || categoryIds || ',') LIKE '%,' || :categoryId || ',%' ORDER BY added DESC") fun byCategory(categoryId: Int): Flow<List<MovieEntity>>
    @Query("SELECT * FROM movies WHERE id = :id") fun movie(id: Int): Flow<MovieEntity?>
    @Query("SELECT * FROM movies WHERE id = :id") suspend fun movieOnce(id: Int): MovieEntity?
    @Query("SELECT * FROM movies WHERE title LIKE '%' || :q || '%' OR `cast` LIKE '%' || :q || '%' OR director LIKE '%' || :q || '%' ORDER BY added DESC LIMIT 40") suspend fun search(q: String): List<MovieEntity>
    @Upsert suspend fun upsert(list: List<MovieEntity>)
    @Upsert suspend fun upsert(m: MovieEntity)
    @Query("SELECT COUNT(*) FROM movies") suspend fun count(): Int

    @Query("SELECT * FROM series ORDER BY lastModified DESC LIMIT :limit") fun recentlyUpdated(limit: Int): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE (',' || genres || ',') LIKE '%,' || :genre || ',%' ORDER BY lastModified DESC LIMIT 40") fun seriesByGenre(genre: String): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE id = :id") fun series(id: Int): Flow<SeriesEntity?>
    @Query("SELECT * FROM series WHERE id = :id") suspend fun seriesOnce(id: Int): SeriesEntity?
    @Query("SELECT * FROM series WHERE title LIKE '%' || :q || '%' OR `cast` LIKE '%' || :q || '%' ORDER BY lastModified DESC LIMIT 40") suspend fun searchSeries(q: String): List<SeriesEntity>
    @Upsert suspend fun upsertSeries(list: List<SeriesEntity>)
    @Upsert suspend fun upsertSeries(s: SeriesEntity)
    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY number") fun seasons(seriesId: Int): Flow<List<SeasonEntity>>
    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1") suspend fun episodeById(id:String): EpisodeEntity?
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND season = :season ORDER BY number") fun episodes(seriesId: Int, season: Int): Flow<List<EpisodeEntity>>
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND season = :season AND number = :number LIMIT 1") suspend fun episode(seriesId: Int, season: Int, number: Int): EpisodeEntity?
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND (season > :season OR (season = :season AND number > :number)) ORDER BY season, number LIMIT 1")
    suspend fun nextEpisode(seriesId: Int, season: Int, number: Int): EpisodeEntity?
    @Upsert suspend fun upsertSeasons(list: List<SeasonEntity>)
    @Upsert suspend fun upsertEpisodes(list: List<EpisodeEntity>)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM watch_progress p WHERE p.kind = :kind AND p.completed = 0 AND (p.kind != 'episode' OR NOT EXISTS (SELECT 1 FROM watch_progress newer WHERE newer.seriesId = p.seriesId AND newer.updatedAt > p.updatedAt)) ORDER BY p.updatedAt DESC LIMIT 100")
    fun libraryContinueWatching(kind:String): Flow<List<WatchProgressEntity>>
    @Query("SELECT * FROM watch_progress WHERE completed = 0 ORDER BY updatedAt DESC LIMIT :limit") fun continueWatching(limit: Int): Flow<List<WatchProgressEntity>>
    @Query("SELECT * FROM watch_progress WHERE `key` = :key") suspend fun progress(key: String): WatchProgressEntity?
    @Query("SELECT * FROM watch_progress WHERE seriesId = :seriesId ORDER BY updatedAt DESC LIMIT 1") suspend fun latestForSeries(seriesId: Int): WatchProgressEntity?
    @Query("SELECT * FROM watch_progress WHERE seriesId = :seriesId") fun progressForSeries(seriesId: Int): Flow<List<WatchProgressEntity>>
    @Upsert suspend fun upsert(p: WatchProgressEntity)
    @Query("DELETE FROM watch_progress WHERE `key` = :key") suspend fun remove(key: String)
    @Query("SELECT * FROM favorites ORDER BY addedAt") fun favorites(): Flow<List<FavoriteEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE `key` = :key)") fun isFavorite(key: String): Flow<Boolean>
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE `key` = :key)") suspend fun isFavoriteOnce(key: String): Boolean
    @Upsert suspend fun addFavorite(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE `key` = :key") suspend fun removeFavorite(key: String)
}

data class MovieSearchRow(val id: Int, val title: String, val year: Int?, val poster: String?, val cast: String?, val director: String?, val genres: String)
data class SeriesSearchRow(val id: Int, val title: String, val year: Int?, val cover: String?, val cast: String?, val genres: String)
