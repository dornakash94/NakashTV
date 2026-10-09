package tv.nakash.data.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.nakash.data.local.AccountStore
import tv.nakash.data.local.FavoriteEntity
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.remote.AuthInterceptor
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs profiles and each profile's progress / favourites / searches with the NakashTV sync server (a Cloudflare
 * Worker, see /server). The account is identified by a SHA-256 of the provider login: the login never leaves the TV.
 * Newer wins per item; removals travel as tombstones. Runs at start and every 30 s while the app is open.
 */
@Singleton
class ProfileSync @Inject constructor(
    okHttp: OkHttpClient, private val accounts: AccountStore, private val profiles: ProfileStore, @ApplicationContext ctx: Context,
) {
    private val client = okHttp.newBuilder().apply { interceptors().removeAll { it is AuthInterceptor } }
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val base = tv.nakash.BuildConfig.SYNC_URL.trimEnd('/')
    private val prefs = ctx.getSharedPreferences("profile_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var loop: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    val enabled get() = base.isNotBlank()

    /** True while a video plays: the sync then runs once a minute instead of every 30 s. */
    @Volatile private var holding = false
    @Volatile private var lastSyncAt = 0L

    fun start() {
        if (!enabled || loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                // While playing, still once a minute: a TV switched off mid-episode must not keep its last minutes to
                // itself (it is frozen at once, before any "on stop" sync can run).
                if (!holding || System.currentTimeMillis() - lastSyncAt >= 60_000) runCatching { syncNow() }
                delay(30_000)
            }
        }
    }

    /**
     * The app is leaving the screen (Home, another input, the TV switched off): sync now, and also hand Android a job
     * that syncs once the network is there, which runs even if the app is frozen before this finishes.
     */
    fun syncOnLeave(ctx: Context) {
        if (!enabled) return
        scope.launch { runCatching { syncNow() } }
        val req = androidx.work.OneTimeWorkRequestBuilder<ProfileSyncWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork("profile-sync", androidx.work.ExistingWorkPolicy.REPLACE, req)
    }

    /** Called by the player: sync less while playing, and catch up as soon as playback stops or pauses. */
    fun hold(playing: Boolean) {
        if (holding == playing) return
        holding = playing
        if (!playing && enabled) scope.launch { runCatching { syncNow() } }
    }

    /** Push what changed here, then pull what changed elsewhere. True when the server answered. */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val key = accountKey() ?: return@withContext false
        lock.withLock { (push(key) && pull(key)).also { if (it) lastSyncAt = System.currentTimeMillis(); if (tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashSync", "sync ok=$it holding=$holding") } }
    }

    private fun accountKey(): String? {
        val a = accounts.current() ?: return null
        val raw = "${a.baseUrl.lowercase()}|${a.username}|${a.password}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun push(key: String): Boolean {
        val started = System.currentTimeMillis()
        val profSince = prefs.getLong("push_profiles", 0)
        val items = buildJsonArray {
            for (p in profiles.all.value) {
                val since = prefs.getLong("push_${p.id}", 0)
                val s = profiles.db(p.id).sync()
                s.progressSince(since).forEach { add(item(p.id, "progress", it.key, progressJson(it), false, it.updatedAt)) }
                s.favoritesSince(since).forEach { add(item(p.id, "fav", it.key, buildJsonObject { put("kind", it.kind); put("refId", it.refId) }, false, it.addedAt)) }
                s.searchesSince(since).forEach { add(item(p.id, "search", it.query, JsonObject(emptyMap()), false, it.at)) }
                s.ratingsSince(since).forEach { add(item(p.id, "rating", it.key, buildJsonObject { put("kind", it.kind); put("refId", it.refId); put("value", it.value) }, false, it.at)) }
                s.tombstonesSince(since).forEach { add(item(p.id, it.kind, it.key, null, true, it.at)) }
            }
        }
        val profs = profiles.all.value.filter { it.updatedAt > profSince }
        if (items.isEmpty() && profs.isEmpty()) return true
        val body = buildJsonObject {
            put("profiles", buildJsonArray { profs.forEach { add(json.encodeToJsonElement(Profile.serializer(), it)) } })
            put("items", items)
        }
        val ok = runCatching {
            client.newCall(Request.Builder().url("$base/v1/state").header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (ok) prefs.edit().apply { putLong("push_profiles", started); profiles.all.value.forEach { putLong("push_${it.id}", started) } }.apply()
        return ok
    }

    private suspend fun pull(key: String): Boolean {
        val since = prefs.getLong("pull", 0)
        val o = runCatching {
            client.newCall(Request.Builder().url("$base/v1/state?since=$since").header("Authorization", "Bearer $key").build()).execute().use { r ->
                if (!r.isSuccessful) null else json.parseToJsonElement(r.body?.string() ?: return@use null).jsonObject
            }
        }.getOrNull() ?: return false
        val remoteProfiles = (o["profiles"] as? JsonArray).orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement(Profile.serializer(), it) }.getOrNull() }
        if (remoteProfiles.isNotEmpty()) profiles.merge(remoteProfiles)
        for (e in (o["items"] as? JsonArray).orEmpty()) {
            val it = e as? JsonObject ?: continue
            val pid = it.str("profile") ?: continue; val kind = it.str("kind") ?: continue; val k = it.str("key") ?: continue
            val at = (it["updatedAt"] as? JsonPrimitive)?.longOrNull ?: continue
            val deleted = (it["deleted"] as? JsonPrimitive)?.booleanOrNull == true
            val s = profiles.db(pid).sync()
            val data = it["data"] as? JsonObject
            when (kind) {
                "progress" -> if (deleted) s.deleteProgressOlder(k, at) else if ((s.progressTime(k) ?: 0) < at && data != null) progressFrom(k, data, at)?.let { p -> s.putProgress(p) }
                "fav" -> if (deleted) s.deleteFavoriteOlder(k, at) else data?.let { d -> s.putFavorite(FavoriteEntity(k, d.str("kind") ?: k.substringBefore(':'), d.str("refId") ?: k.substringAfter(':'), at)) }
                "search" -> if (deleted) s.deleteSearchOlder(k, at) else s.addSearch(SearchEntity(k, at))
                "rating" -> if (deleted) s.deleteRatingOlder(k, at) else if ((s.ratingTime(k) ?: 0) < at) data?.let { d ->
                    (d["value"] as? JsonPrimitive)?.intOrNull?.let { v -> s.putRating(RatingEntity(k, d.str("kind") ?: k.substringBefore(':'), d.str("refId") ?: k.substringAfter(':'), v, at)) }
                }
            }
        }
        (o["now"] as? JsonPrimitive)?.longOrNull?.let { prefs.edit().putLong("pull", it).apply() }
        return true
    }

    fun reset() { prefs.edit().clear().apply() }

    private fun item(profile: String, kind: String, key: String, data: JsonElement?, deleted: Boolean, at: Long) = buildJsonObject {
        put("profile", profile); put("kind", kind); put("key", key); put("data", data ?: JsonNull); put("deleted", deleted); put("updatedAt", at)
    }
    private fun progressJson(p: WatchProgressEntity) = buildJsonObject {
        put("kind", p.kind); put("refId", p.refId); p.seriesId?.let { put("seriesId", it) }
        put("positionMs", p.positionMs); put("durationMs", p.durationMs); put("completed", p.completed)
    }
    private fun progressFrom(key: String, d: JsonObject, at: Long) = runCatching {
        WatchProgressEntity(key, d.str("kind") ?: return null, d.str("refId") ?: return null, (d["seriesId"] as? JsonPrimitive)?.intOrNull,
            (d["positionMs"] as JsonPrimitive).longOrNull ?: 0, (d["durationMs"] as JsonPrimitive).longOrNull ?: 0, at, (d["completed"] as? JsonPrimitive)?.booleanOrNull == true)
    }.getOrNull()
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
