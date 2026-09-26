package tv.nakash.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class TmdbCast(val name: String, val character: String?, val photo: String?)
data class TmdbTitle(val id: Int, val title: String, val original: String?, val year: Int?, val poster: String?)
data class TmdbDetails(val trailerKey: String?, val backdrop: String?, val overview: String?, val cast: List<TmdbCast>, val similar: List<TmdbTitle>)

/** Pure parsing of TMDB v3 responses (details with append_to_response=videos,credits,recommendations). */
object TmdbParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val IMG = "https://image.tmdb.org/t/p/"

    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }
    private fun JsonObject.arr(k: String): JsonArray = (this[k] as? JsonObject)?.get("results") as? JsonArray ?: JsonArray(emptyList())

    fun details(body: String, tv: Boolean): TmdbDetails {
        val o = json.parseToJsonElement(body).jsonObject
        val videos = o.arr("videos").mapNotNull { it as? JsonObject }
        val trailer = videos.filter { it.str("site") == "YouTube" && it.str("key") != null }
            .maxByOrNull { v ->
                val type = when (v.str("type")) { "Trailer" -> 30; "Teaser" -> 20; else -> 0 }
                val official = if (v["official"]?.jsonPrimitive?.booleanOrNull == true) 5 else 0
                val lang = when (v.str("iso_639_1")) { "he" -> 10; "en" -> 3; else -> 0 }
                type + official + lang
            }?.takeIf { (it.str("type") == "Trailer" || it.str("type") == "Teaser") }?.str("key")
        val cast = ((o["credits"] as? JsonObject)?.get("cast") as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }.take(15)
            .mapNotNull { c -> c.str("name")?.let { TmdbCast(it, c.str("character"), c.str("profile_path")?.let { p -> IMG + "w185" + p }) } }
        val similar = (o.arr("recommendations") + o.arr("similar")).mapNotNull { it as? JsonObject }.mapNotNull { r ->
            val id = r["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = (if (tv) r.str("name") else r.str("title")) ?: return@mapNotNull null
            TmdbTitle(id, title, if (tv) r.str("original_name") else r.str("original_title"),
                (if (tv) r.str("first_air_date") else r.str("release_date"))?.take(4)?.toIntOrNull(), r.str("poster_path")?.let { IMG + "w342" + it })
        }
        return TmdbDetails(trailer, o.str("backdrop_path")?.let { IMG + "w1280" + it }, o.str("overview"), cast, similar)
    }

    /** First search hit's id, preferring an exact year match. */
    fun searchId(body: String, year: Int?, tv: Boolean): Int? {
        val results = (json.parseToJsonElement(body).jsonObject["results"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val dated = results.firstOrNull { r -> year != null && (if (tv) r.str("first_air_date") else r.str("release_date"))?.take(4)?.toIntOrNull() == year }
        return (dated ?: results.firstOrNull())?.get("id")?.jsonPrimitive?.intOrNull
    }
}

/** Links TMDB recommendations to titles that are actually in the provider's library (by title and year). */
object LibraryMatch {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    fun key(title: String) = Normalizer.titleWithoutYear(title).lowercase().replace(NON_WORD, " ").trim()
    fun <T> match(recs: List<TmdbTitle>, library: List<T>, title: (T) -> String, year: (T) -> Int?): List<T> {
        val byKey = library.groupBy { key(title(it)) }
        return recs.mapNotNull { r ->
            listOfNotNull(r.title, r.original).map(::key).filter { it.isNotEmpty() }.firstNotNullOfOrNull { k ->
                byKey[k]?.firstOrNull { item -> val y = year(item); y == null || r.year == null || kotlin.math.abs(y - r.year) <= 1 }
            }
        }.distinct()
    }
}
