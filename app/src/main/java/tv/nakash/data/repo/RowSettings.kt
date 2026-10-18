package tv.nakash.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.nakash.BuildConfig
import tv.nakash.data.remote.AuthInterceptor
import javax.inject.Inject
import javax.inject.Singleton

/** The themed rows as the sync server lists them (GET /v1/rows); empty = the app's built-in ones. */
@Serializable
data class RowsConfig(val movies: List<Curated.Spec> = emptyList(), val series: List<Curated.Spec> = emptyList())

/**
 * Themed rows from our server, so rows can be added, changed or retired without an app release. Kept on the device
 * (the tabs never wait for the network) and refreshed at most every 6 hours.
 */
@Singleton
class RowConfig @Inject constructor(okHttp: OkHttpClient, @ApplicationContext ctx: Context) {
    private val client = okHttp.newBuilder().apply { interceptors().removeAll { it is AuthInterceptor } }.build()
    private val prefs = ctx.getSharedPreferences("row_config", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val base = BuildConfig.SYNC_URL.trimEnd('/')

    /** The server's rows for this kind, or null for the built-in ones. */
    suspend fun specs(series: Boolean): List<Curated.Spec>? {
        if (System.currentTimeMillis() - prefs.getLong("at", 0) > 6 * 60 * 60_000L) withTimeoutOrNull(3_000) { refresh() }
        val cfg = prefs.getString("json", null)?.let { runCatching { json.decodeFromString(RowsConfig.serializer(), it) }.getOrNull() } ?: return null
        return (if (series) cfg.series else cfg.movies).takeIf { it.isNotEmpty() }
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        if (base.isBlank()) return@withContext
        runCatching {
            client.newCall(Request.Builder().url("$base/v1/rows").build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val body = r.body?.string() ?: return@use
                json.decodeFromString(RowsConfig.serializer(), body) // only a valid answer replaces what is kept
                prefs.edit().putString("json", body).putLong("at", System.currentTimeMillis()).apply()
            }
        }
    }
}

/**
 * How each profile treats each row: how often it scrolled to it, and how often it opened a title from it. A themed row
 * the profile keeps passing without opening anything slides down the tab; one it opens from moves up. On the device only.
 */
@Singleton
class RowStats @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("row_stats", Context.MODE_PRIVATE)
    private fun get(profile: String, row: String): Pair<Int, Int> =
        prefs.getString("$profile|$row", null)?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: (0 to 0)
    private fun put(profile: String, row: String, v: Pair<Int, Int>) = prefs.edit().putString("$profile|$row", "${v.first}:${v.second}").apply()

    fun seen(profile: String, row: String) { val v = get(profile, row); put(profile, row, (v.first + 1) to v.second) }
    fun opened(profile: String, row: String) { val v = get(profile, row); put(profile, row, v.first to (v.second + 1)) }

    /** The rows reordered: every 3 visits without an open push a row one place down, every open lifts it 1.5 places. */
    fun <T> order(profile: String?, rows: List<T>, key: (T) -> String): List<T> {
        if (profile == null) return rows
        return rows.withIndex().sortedBy { (i, r) -> val (seen, opened) = get(profile, key(r)); i + Recommender.rowShift(seen, opened) }.map { it.value }
    }
}
