package tv.nakash.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.nakash.data.local.TmdbPreferences
import tv.nakash.domain.Normalizer
import tv.nakash.domain.TmdbDetails
import tv.nakash.domain.TmdbParser
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB extras (trailer, cast, recommendations, backdrop, Hebrew overview). Uses the user's own key; the provider's
 * credentials never reach this host (its client drops the provider auth interceptor). Responses are cached on disk
 * for a week so the detail page opens instantly the second time.
 */
@Singleton
class TmdbRepository @Inject constructor(okHttp: OkHttpClient, private val prefs: TmdbPreferences, @ApplicationContext ctx: Context) {
    private val client = okHttp.newBuilder().apply { interceptors().removeAll { it is AuthInterceptor } }.build()
    private val cacheDir = File(ctx.cacheDir, "tmdb").apply { mkdirs() }
    private val base = "https://api.themoviedb.org/3/".toHttpUrl()
    private val append = "videos,credits,recommendations,similar"

    val enabled get() = prefs.key.value != null

    suspend fun movie(tmdbId: Int?, title: String, year: Int?): TmdbDetails? = withContext(Dispatchers.IO) {
        val key = prefs.key.value ?: return@withContext null
        val id = tmdbId ?: search("movie", title, year, key) ?: return@withContext null
        details("movie", id, key)
    }

    suspend fun tv(title: String, year: Int?): TmdbDetails? = withContext(Dispatchers.IO) {
        val key = prefs.key.value ?: return@withContext null
        val id = search("tv", title, year, key) ?: return@withContext null
        details("tv", id, key)
    }

    /** True when TMDB accepts the key. */
    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { client.newCall(Request.Builder().url(url("configuration", key)).build()).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private fun details(kind: String, id: Int, key: String): TmdbDetails? {
        val body = get(url("$kind/$id", key) { addQueryParameter("append_to_response", append); addQueryParameter("include_video_language", "he,en,null") }) ?: return null
        return runCatching { TmdbParser.details(body, kind == "tv") }.getOrNull()
    }

    private fun search(kind: String, title: String, year: Int?, key: String): Int? {
        val q = Normalizer.titleWithoutYear(title).ifBlank { return null }
        fun run(withYear: Boolean) = get(url("search/$kind", key) {
            addQueryParameter("query", q)
            if (withYear && year != null) addQueryParameter(if (kind == "tv") "first_air_date_year" else "year", year.toString())
        })?.let { runCatching { TmdbParser.searchId(it, year, kind == "tv") }.getOrNull() }
        return run(true) ?: run(false)
    }

    private fun url(path: String, key: String, extra: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().addEncodedPathSegments(path).addQueryParameter("api_key", key).addQueryParameter("language", "he-IL").apply(extra).build()

    /** Cached GET. The cache file name is derived from the URL without the key. */
    private fun get(url: HttpUrl): String? {
        val name = MessageDigest.getInstance("SHA-1").digest(url.newBuilder().removeAllQueryParameters("api_key").build().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "$name.json")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 7 * 86_400_000L) return file.readText()
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.string()?.also { file.writeText(it) }
            }
        }.getOrNull() ?: file.takeIf { it.isFile }?.readText()
    }
}
