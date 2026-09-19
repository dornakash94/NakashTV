package tv.nakash.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Xtream / XUI.one DTOs. The server (ilvip.net, XUI 1.5.13) is inconsistent:
 *  - numbers arrive as "88019" or 88019, rating as "8" or 7.3 or 0
 *  - missing fields arrive as null OR "" depending on the item
 *  - tv_archive is int 0/1, category_ids is an int array
 * Every field below is tolerant. Nothing in the UI depends on raw DTOs; the repository maps them.
 */

/** Accepts string, number or null -> String? ("" becomes null). */
object LenientString : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonNull -> null
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
    override fun serialize(encoder: Encoder, value: String?) = encoder.encodeString(value ?: "")
}

/** Accepts "7.3", 7.3, "0", 0, "", null -> Double? (0 becomes null: the server uses 0 for "unknown"). */
object LenientRating : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientRating", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double? {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        val d = (el as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        return d?.takeIf { it > 0.0 }
    }
    override fun serialize(encoder: Encoder, value: Double?) = encoder.encodeDouble(value ?: 0.0)
}

/** Accepts "115", 115, "", null -> Int? */
object LenientInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return (el as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
    }
    override fun serialize(encoder: Encoder, value: Int?) = encoder.encodeInt(value ?: 0)
}

object LenientLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long? {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return (el as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }
    }
    override fun serialize(encoder: Encoder, value: Long?) = encoder.encodeLong(value ?: 0L)
}

/** backdrop_path arrives as an array of strings; sometimes missing. */
object LenientStringList : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientStringList", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): List<String> {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            is JsonPrimitive -> listOfNotNull(el.contentOrNull?.takeIf { it.isNotBlank() })
            else -> emptyList()
        }
    }
    override fun serialize(encoder: Encoder, value: List<String>) = encoder.encodeString(value.joinToString(","))
}

@Serializable
data class AuthResponse(
    @SerialName("user_info") val userInfo: UserInfo,
    @SerialName("server_info") val serverInfo: ServerInfo,
)

@Serializable
data class UserInfo(
    val username: String,
    val password: String,
    @Serializable(LenientString::class) val message: String? = null,
    @Serializable(LenientInt::class) val auth: Int? = 0,
    @Serializable(LenientString::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(LenientLong::class) val expDate: Long? = null,
    @SerialName("is_trial") @Serializable(LenientString::class) val isTrial: String? = null,
    @SerialName("active_cons") @Serializable(LenientInt::class) val activeCons: Int? = null,
    @SerialName("max_connections") @Serializable(LenientInt::class) val maxConnections: Int? = null,
    @SerialName("allowed_output_formats") val allowedOutputFormats: List<String> = emptyList(),
) { val isActive get() = auth == 1 && status.equals("Active", ignoreCase = true) }

@Serializable
data class ServerInfo(
    val url: String,
    @Serializable(LenientString::class) val port: String? = null,
    @SerialName("https_port") @Serializable(LenientString::class) val httpsPort: String? = null,
    @SerialName("server_protocol") @Serializable(LenientString::class) val serverProtocol: String? = null,
    @Serializable(LenientString::class) val version: String? = null,
    @SerialName("timestamp_now") @Serializable(LenientLong::class) val timestampNow: Long? = null,
    @Serializable(LenientString::class) val timezone: String? = null,
    val xui: Boolean = false,
)

@Serializable
data class CategoryDto(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") @Serializable(LenientInt::class) val parentId: Int? = 0,
)

@Serializable
data class LiveStreamDto(
    @Serializable(LenientInt::class) val num: Int? = null,
    val name: String,
    @SerialName("stream_id") val streamId: Int,
    @SerialName("stream_icon") @Serializable(LenientString::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(LenientString::class) val epgChannelId: String? = null,
    @Serializable(LenientLong::class) val added: Long? = null,
    @SerialName("tv_archive") @Serializable(LenientInt::class) val tvArchive: Int? = 0,
    @SerialName("tv_archive_duration") @Serializable(LenientInt::class) val tvArchiveDuration: Int? = 0,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @SerialName("category_ids") val categoryIds: List<Int> = emptyList(),
    @SerialName("direct_source") @Serializable(LenientString::class) val directSource: String? = null,
)

@Serializable
data class VodStreamDto(
    val name: String,
    @Serializable(LenientString::class) val title: String? = null,
    @Serializable(LenientString::class) val year: String? = null,
    @SerialName("stream_id") val streamId: Int,
    @SerialName("stream_icon") @Serializable(LenientString::class) val streamIcon: String? = null,
    @Serializable(LenientRating::class) val rating: Double? = null,
    @Serializable(LenientLong::class) val added: Long? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientString::class) val cast: String? = null,
    @Serializable(LenientString::class) val director: String? = null,
    @Serializable(LenientString::class) val genre: String? = null,
    @SerialName("release_date") @Serializable(LenientString::class) val releaseDate: String? = null,
    @SerialName("youtube_trailer") @Serializable(LenientString::class) val youtubeTrailer: String? = null,
    @SerialName("episode_run_time") @Serializable(LenientInt::class) val runTimeMin: Int? = null,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @SerialName("category_ids") val categoryIds: List<Int> = emptyList(),
    @SerialName("container_extension") @Serializable(LenientString::class) val containerExtension: String? = "mp4",
)

@Serializable
data class VodInfoResponse(val info: VodInfoDto? = null, @SerialName("movie_data") val movieData: VodStreamDto? = null)

@Serializable
data class VodInfoDto(
    @SerialName("tmdb_id") @Serializable(LenientInt::class) val tmdbId: Int? = null,
    @SerialName("cover_big") @Serializable(LenientString::class) val coverBig: String? = null,
    @SerialName("movie_image") @Serializable(LenientString::class) val movieImage: String? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringList::class) val backdropPath: List<String> = emptyList(),
    @SerialName("duration_secs") @Serializable(LenientInt::class) val durationSecs: Int? = null,
    @Serializable(LenientString::class) val country: String? = null,
    @Serializable(LenientString::class) val genre: String? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientString::class) val cast: String? = null,
    @Serializable(LenientString::class) val director: String? = null,
    @SerialName("youtube_trailer") @Serializable(LenientString::class) val youtubeTrailer: String? = null,
    @Serializable(LenientRating::class) val rating: Double? = null,
    @Serializable(LenientInt::class) val bitrate: Int? = null,
)

@Serializable
data class SeriesDto(
    val name: String,
    @Serializable(LenientString::class) val title: String? = null,
    @Serializable(LenientString::class) val year: String? = null,
    @SerialName("series_id") val seriesId: Int,
    @Serializable(LenientString::class) val cover: String? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientString::class) val cast: String? = null,
    @Serializable(LenientString::class) val director: String? = null,
    @Serializable(LenientString::class) val genre: String? = null,
    @SerialName("release_date") @Serializable(LenientString::class) val releaseDate: String? = null,
    @SerialName("last_modified") @Serializable(LenientLong::class) val lastModified: Long? = null,
    @Serializable(LenientRating::class) val rating: Double? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringList::class) val backdropPath: List<String> = emptyList(),
    @SerialName("youtube_trailer") @Serializable(LenientString::class) val youtubeTrailer: String? = null,
    @SerialName("episode_run_time") @Serializable(LenientInt::class) val runTimeMin: Int? = null,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @SerialName("category_ids") val categoryIds: List<Int> = emptyList(),
)

/** The details endpoint omits series_id; identity comes from the requested ID. */
@Serializable
data class SeriesDetailsDto(
    val name: String,
    @Serializable(LenientString::class) val title: String? = null,
    @Serializable(LenientString::class) val year: String? = null,
    @Serializable(LenientString::class) val cover: String? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientString::class) val cast: String? = null,
    @Serializable(LenientString::class) val director: String? = null,
    @Serializable(LenientString::class) val genre: String? = null,
    @SerialName("release_date") @Serializable(LenientString::class) val releaseDate: String? = null,
    @SerialName("last_modified") @Serializable(LenientLong::class) val lastModified: Long? = null,
    @Serializable(LenientRating::class) val rating: Double? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringList::class) val backdropPath: List<String> = emptyList(),
    @SerialName("youtube_trailer") @Serializable(LenientString::class) val youtubeTrailer: String? = null,
    @SerialName("episode_run_time") @Serializable(LenientInt::class) val runTimeMin: Int? = null,
    @SerialName("category_id") @Serializable(LenientString::class) val categoryId: String? = null,
    @SerialName("category_ids") val categoryIds: List<Int> = emptyList(),
)

@Serializable
data class SeriesInfoResponse(
    val seasons: List<SeasonDto> = emptyList(),
    val info: SeriesDetailsDto? = null,
    /** Keyed by season number as a string ("1", "2"...). Do not rely on key order. */
    val episodes: Map<String, List<EpisodeDto>> = emptyMap(),
)

@Serializable
data class SeasonDto(
    @SerialName("season_number") @Serializable(LenientInt::class) val seasonNumber: Int? = null,
    @Serializable(LenientString::class) val name: String? = null,
    @SerialName("episode_count") @Serializable(LenientInt::class) val episodeCount: Int? = null,
    @SerialName("air_date") @Serializable(LenientString::class) val airDate: String? = null,
    @Serializable(LenientString::class) val cover: String? = null,
    @SerialName("cover_big") @Serializable(LenientString::class) val coverBig: String? = null,
)

@Serializable
data class EpisodeDto(
    val id: String,
    @SerialName("episode_num") @Serializable(LenientInt::class) val episodeNum: Int? = null,
    val title: String,
    @SerialName("container_extension") @Serializable(LenientString::class) val containerExtension: String? = "mp4",
    @Serializable(LenientInt::class) val season: Int? = null,
    val info: EpisodeInfoDto? = null,
    @Serializable(LenientLong::class) val added: Long? = null,
)

@Serializable
data class EpisodeInfoDto(
    @SerialName("duration_secs") @Serializable(LenientInt::class) val durationSecs: Int? = null,
    @Serializable(LenientString::class) val plot: String? = null,
    @Serializable(LenientInt::class) val bitrate: Int? = null,
    @SerialName("tmdb_id") @Serializable(LenientString::class) val tmdbId: String? = null,
    @SerialName("movie_image") @Serializable(LenientString::class) val movieImage: String? = null,
    @SerialName("release_date") @Serializable(LenientString::class) val releaseDate: String? = null,
)

@Serializable
data class ShortEpgResponse(@SerialName("epg_listings") val listings: List<EpgListingDto> = emptyList())

/** title and description are Base64 (UTF-8) — decoded in the mapper. */
@Serializable
data class EpgListingDto(
    val id: String,
    val title: String,
    val description: String = "",
    @SerialName("channel_id") @Serializable(LenientString::class) val channelId: String? = null,
    @SerialName("start_timestamp") @Serializable(LenientLong::class) val startTs: Long? = null,
    @SerialName("stop_timestamp") @Serializable(LenientLong::class) val stopTs: Long? = null,
)
