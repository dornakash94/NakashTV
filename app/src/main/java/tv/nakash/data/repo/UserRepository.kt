package tv.nakash.data.repo

import kotlinx.coroutines.flow.Flow
import tv.nakash.data.local.FavoriteEntity
import tv.nakash.data.local.UserDao
import tv.nakash.data.local.WatchProgressEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(private val dao: UserDao) {
    fun continueWatching(limit: Int = 12): Flow<List<WatchProgressEntity>> = dao.continueWatching(limit)
    fun libraryContinueWatching(series:Boolean)=dao.libraryContinueWatching(if(series) "episode" else "movie")
    fun favorites() = dao.favorites()
    fun isFavorite(kind: String, refId: String) = dao.isFavorite("$kind:$refId")
    suspend fun toggleFavorite(kind: String, refId: String): Boolean {
        val key = "$kind:$refId"
        return if (dao.isFavoriteOnce(key)) { dao.removeFavorite(key); false } else { dao.addFavorite(FavoriteEntity(key, kind, refId, System.currentTimeMillis())); true }
    }
    suspend fun setFavorite(kind: String, refId: String, on: Boolean) {
        val key = "$kind:$refId"
        if (on) dao.addFavorite(FavoriteEntity(key, kind, refId, System.currentTimeMillis())) else dao.removeFavorite(key)
    }
    suspend fun progress(kind: String, refId: String) = dao.progress("$kind:$refId")
    suspend fun latestForSeries(seriesId: Int) = dao.latestForSeries(seriesId)
    fun progressForSeries(seriesId: Int) = dao.progressForSeries(seriesId)

    /** Preserve short viewing sessions too; resume must work before 2% of a long movie. */
    suspend fun saveProgress(kind: String, refId: String, positionMs: Long, durationMs: Long, seriesId: Int? = null) {
        val key = "$kind:$refId"
        if (durationMs <= 0) { if (kind == "channel") dao.upsert(WatchProgressEntity(key, kind, refId, null, 0, 0, System.currentTimeMillis())); return }
        val frac = positionMs.toDouble() / durationMs
        when {
            positionMs < 1_000 -> dao.remove(key)
            frac > 0.95 -> dao.upsert(WatchProgressEntity(key, kind, refId, seriesId, durationMs, durationMs, System.currentTimeMillis(), completed = true))
            else -> dao.upsert(WatchProgressEntity(key, kind, refId, seriesId, positionMs, durationMs, System.currentTimeMillis()))
        }
    }
    suspend fun markWatched(kind: String, refId: String, seriesId: Int?, durationMs: Long) =
        dao.upsert(WatchProgressEntity("$kind:$refId", kind, refId, seriesId, durationMs, durationMs, System.currentTimeMillis(), completed = true))
    suspend fun remove(kind: String, refId: String) = dao.remove("$kind:$refId")
}
