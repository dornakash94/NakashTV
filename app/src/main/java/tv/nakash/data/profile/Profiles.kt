package tv.nakash.data.profile

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tv.nakash.data.local.FavoriteEntity
import tv.nakash.data.local.UserDao
import tv.nakash.data.local.WatchProgressEntity
import javax.inject.Inject
import javax.inject.Singleton

/** A viewer under the provider account: own continue watching, list and searches, synced across devices. */
@Serializable
data class Profile(val id: String, val name: String, val color: Int, val updatedAt: Long = System.currentTimeMillis(), val deleted: Boolean = false,
                   /** One of the 8 avatars (0–7), or -1 for the first letter on [color]. */
                   val avatar: Int = -1,
                   /** A cast photo picked from the avatar gallery (TMDB); wins over [avatar]. */
                   val avatarUrl: String? = null)

/** A recent search of a profile. */
@Entity(tableName = "searches")
data class SearchEntity(@PrimaryKey val query: String, val at: Long)

/** A removed item (favourite / progress / search), so the removal reaches the other devices too. */
@Entity(tableName = "tombstones")
data class TombstoneEntity(@PrimaryKey val key: String, val kind: String, val at: Long)

@Dao
interface ProfileSyncDao {
    @Query("SELECT * FROM searches ORDER BY at DESC LIMIT :limit") fun recentSearches(limit: Int): Flow<List<SearchEntity>>
    @Upsert suspend fun addSearch(s: SearchEntity)
    @Query("DELETE FROM searches WHERE `query` = :q") suspend fun removeSearch(q: String)
    @Query("DELETE FROM searches WHERE `query` NOT IN (SELECT `query` FROM searches ORDER BY at DESC LIMIT 30)") suspend fun trimSearches()

    @Upsert suspend fun tombstone(t: TombstoneEntity)
    @Query("DELETE FROM tombstones WHERE `key` = :key AND kind = :kind") suspend fun clearTombstone(key: String, kind: String)

    // Changes since a time, for pushing to the server.
    @Query("SELECT * FROM watch_progress WHERE updatedAt > :since") suspend fun progressSince(since: Long): List<WatchProgressEntity>
    @Query("SELECT * FROM favorites WHERE addedAt > :since") suspend fun favoritesSince(since: Long): List<FavoriteEntity>
    @Query("SELECT * FROM searches WHERE at > :since") suspend fun searchesSince(since: Long): List<SearchEntity>
    @Query("SELECT * FROM tombstones WHERE at > :since") suspend fun tombstonesSince(since: Long): List<TombstoneEntity>

    // Applying the server's changes (only when newer than what is here).
    @Query("SELECT updatedAt FROM watch_progress WHERE `key` = :key") suspend fun progressTime(key: String): Long?
    @Upsert suspend fun putProgress(p: WatchProgressEntity)
    @Query("DELETE FROM watch_progress WHERE `key` = :key AND updatedAt <= :at") suspend fun deleteProgressOlder(key: String, at: Long)
    @Upsert suspend fun putFavorite(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE `key` = :key AND addedAt <= :at") suspend fun deleteFavoriteOlder(key: String, at: Long)
    @Query("DELETE FROM searches WHERE `query` = :q AND at <= :at") suspend fun deleteSearchOlder(q: String, at: Long)
}

/** One database per profile: its watch progress, favourites, searches and removals. */
@Database(entities = [WatchProgressEntity::class, FavoriteEntity::class, SearchEntity::class, TombstoneEntity::class], version = 1, exportSchema = false)
abstract class ProfileDb : RoomDatabase() {
    abstract fun user(): UserDao
    abstract fun sync(): ProfileSyncDao
}

/**
 * The profiles of this device's provider account and the one watching now. Profiles are kept locally (they work
 * offline) and synced with the server. Nobody is chosen at app start: the "מי צופה?" screen asks every time.
 */
@Singleton
class ProfileStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow(read())
    /** Every profile, deleted ones included (their removal is synced); use [visible] to show. */
    val all: StateFlow<List<Profile>> = _all
    private val _current = MutableStateFlow<Profile?>(null)
    /** The profile watching now; null until chosen on the "מי צופה?" screen. */
    val current: StateFlow<Profile?> = _current
    val visible get() = _all.value.filter { !it.deleted }
    private val dbs = HashMap<String, ProfileDb>()

    init {
        // First run with profiles: the existing history and list become the first profile's ("ראשי").
        if (_all.value.isEmpty()) save(listOf(Profile("main", "ראשי", COLORS[0])))
    }

    fun choose(p: Profile) { _current.value = p; prefs.edit().putString("last", p.id).apply() }
    fun signOutProfile() { _current.value = null }
    val lastId: String? get() = prefs.getString("last", null)

    fun add(name: String, avatar: Int, avatarUrl: String? = null): Profile {
        val p = Profile(java.util.UUID.randomUUID().toString().take(8), name.trim().take(20).ifBlank { "צופה" }, COLORS[visible.size % COLORS.size], avatar = avatar, avatarUrl = avatarUrl)
        save(_all.value + p); return p
    }
    fun update(p: Profile, name: String, avatar: Int, avatarUrl: String? = null) {
        val u = p.copy(name = name.trim().take(20).ifBlank { p.name }, avatar = avatar, avatarUrl = avatarUrl, updatedAt = System.currentTimeMillis())
        upsert(u); if (_current.value?.id == p.id) _current.value = u
    }
    fun remove(p: Profile) { if (visible.size > 1) upsert(p.copy(deleted = true, updatedAt = System.currentTimeMillis())); if (_current.value?.id == p.id) _current.value = null }
    fun upsert(p: Profile) = save(_all.value.filterNot { it.id == p.id } + p)
    /** The server's list merged in (newer wins per profile). */
    fun merge(remote: List<Profile>) {
        val byId = _all.value.associateBy { it.id }.toMutableMap()
        remote.forEach { r -> val l = byId[r.id]; if (l == null || r.updatedAt > l.updatedAt) byId[r.id] = r }
        save(byId.values.sortedBy { it.name })
        _current.value?.let { c -> _current.value = byId[c.id]?.takeIf { !it.deleted } }
    }

    /** The database of a profile (opened once). */
    fun db(id: String): ProfileDb = synchronized(dbs) {
        dbs.getOrPut(id) { Room.databaseBuilder(ctx, ProfileDb::class.java, "profile_$id.db").fallbackToDestructiveMigration().build() }
    }

    fun clearAll() { prefs.edit().clear().apply(); _current.value = null; _all.value = emptyList(); synchronized(dbs) { dbs.values.forEach { it.close() }; dbs.clear() }
        ctx.databaseList().filter { it.startsWith("profile_") }.forEach { ctx.deleteDatabase(it) } }

    private fun save(list: List<Profile>) { prefs.edit().putString("list", json.encodeToString(ListSerializer(Profile.serializer()), list)).apply(); _all.value = list }
    private fun read(): List<Profile> = runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), prefs.getString("list", null) ?: return emptyList()) }.getOrDefault(emptyList())

    companion object {
        /** Profile colours (ARGB). */
        val COLORS = listOf(0xFFE3453A.toInt(), 0xFF3A7BE3.toInt(), 0xFF2FA86B.toInt(), 0xFFF0B441.toInt(), 0xFF8A5CF6.toInt(), 0xFFE35BA5.toInt(), 0xFF14A3B8.toInt())
    }
}
