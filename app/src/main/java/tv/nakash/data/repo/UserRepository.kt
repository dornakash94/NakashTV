package tv.nakash.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tv.nakash.data.local.FavoriteEntity
import tv.nakash.data.local.UserDao
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.profile.ProfileStore
import tv.nakash.data.profile.SearchEntity
import tv.nakash.data.profile.TombstoneEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch progress, favourites and searches of the profile watching now (each profile has its own database). Lists
 * follow profile switches by themselves. Removals leave a tombstone so they sync to the other devices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UserRepository @Inject constructor(
    private val legacy: UserDao, private val profiles: ProfileStore, @ApplicationContext ctx: Context,
) {
    private val migrated = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    /** The current profile's database; until a profile is chosen, the first one (e.g. background sync). */
    private fun db() = profiles.db(profiles.current.value?.id ?: profiles.lastId ?: profiles.visible.firstOrNull()?.id ?: "main")
    private val dao get() = db().user()
    private val sync get() = db().sync()
    private fun <T> live(f: (UserDao) -> Flow<T>, empty: T): Flow<T> = profiles.current.flatMapLatest { p -> if (p == null) flowOf(empty) else f(profiles.db(p.id).user()) }

    /** Once: the history and list kept before profiles existed go to the first profile chosen (or created) here. */
    suspend fun migrateLegacy(profileId: String) {
        if (migrated.getBoolean("legacy_done", false)) return
        val target = profiles.db(profileId).user()
        runCatching {
            legacy.continueWatchingAll().forEach { target.upsert(it) }
            legacy.favorites().first().forEach { target.addFavorite(it) }
            // Moved, not copied: nothing is left outside the profiles to be moved again into another one.
            legacy.clearProgress(); legacy.clearFavorites()
        }
        migrated.edit().putBoolean("legacy_done", true).apply()
    }

    fun continueWatching(limit: Int = 12): Flow<List<WatchProgressEntity>> = live({ it.continueWatching(limit) }, emptyList())
    fun libraryContinueWatching(series: Boolean) = live({ it.libraryContinueWatching(if (series) "episode" else "movie") }, emptyList())
    fun favorites() = live({ it.favorites() }, emptyList())
    fun isFavorite(kind: String, refId: String) = live({ it.isFavorite("$kind:$refId") }, false)
    suspend fun toggleFavorite(kind: String, refId: String): Boolean {
        val key = "$kind:$refId"
        return if (dao.isFavoriteOnce(key)) { removeFavorite(key); false } else { addFavorite(key, kind, refId); true }
    }
    suspend fun setFavorite(kind: String, refId: String, on: Boolean) {
        val key = "$kind:$refId"
        if (on) addFavorite(key, kind, refId) else removeFavorite(key)
    }
    private suspend fun addFavorite(key: String, kind: String, refId: String) { sync.clearTombstone(key, "fav"); dao.addFavorite(FavoriteEntity(key, kind, refId, System.currentTimeMillis())) }
    private suspend fun removeFavorite(key: String) { dao.removeFavorite(key); sync.tombstone(TombstoneEntity(key, "fav", System.currentTimeMillis())) }

    suspend fun progress(kind: String, refId: String) = dao.progress("$kind:$refId")
    suspend fun latestForSeries(seriesId: Int) = dao.latestForSeries(seriesId)
    fun progressForSeries(seriesId: Int) = live({ it.progressForSeries(seriesId) }, emptyList())

    /** Preserve short viewing sessions too; resume must work before 2% of a long movie. */
    suspend fun saveProgress(kind: String, refId: String, positionMs: Long, durationMs: Long, seriesId: Int? = null) {
        when (val r = progressRecord(kind, refId, positionMs, durationMs, seriesId, System.currentTimeMillis())) {
            null -> if (positionMs < 1_000 && durationMs > 0) remove(kind, refId)
            else -> dao.upsert(r)
        }
    }

    companion object {
        /** What to store for a position: null = nothing (or remove, under a second into a video). Pure, for tests. */
        fun progressRecord(kind: String, refId: String, positionMs: Long, durationMs: Long, seriesId: Int?, now: Long): WatchProgressEntity? {
            val key = "$kind:$refId"
            if (durationMs <= 0) return if (kind == "channel") WatchProgressEntity(key, kind, refId, null, 0, 0, now) else null
            return when {
                positionMs < 1_000 -> null
                positionMs.toDouble() / durationMs > 0.95 -> WatchProgressEntity(key, kind, refId, seriesId, durationMs, durationMs, now, completed = true)
                else -> WatchProgressEntity(key, kind, refId, seriesId, positionMs, durationMs, now)
            }
        }
    }
    suspend fun markWatched(kind: String, refId: String, seriesId: Int?, durationMs: Long) =
        dao.upsert(WatchProgressEntity("$kind:$refId", kind, refId, seriesId, durationMs, durationMs, System.currentTimeMillis(), completed = true))
    suspend fun remove(kind: String, refId: String) {
        val key = "$kind:$refId"
        if (dao.progress(key) != null) { dao.remove(key); sync.tombstone(TombstoneEntity(key, "progress", System.currentTimeMillis())) }
    }

    // ---- searches
    fun recentSearches(limit: Int = 8) = profiles.current.filterNotNull().flatMapLatest { profiles.db(it.id).sync().recentSearches(limit) }
    suspend fun addSearch(q: String) {
        val t = q.trim(); if (t.length < 2) return
        sync.clearTombstone(t, "search"); sync.addSearch(SearchEntity(t, System.currentTimeMillis())); sync.trimSearches()
    }
    suspend fun removeSearch(q: String) { sync.removeSearch(q); sync.tombstone(TombstoneEntity(q, "search", System.currentTimeMillis())) }
}
