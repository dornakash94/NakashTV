package tv.nakash.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories", primaryKeys = ["id", "kind"])
data class CategoryEntity(
    val id: Int,
    val kind: String,          // live | vod | series
    val name: String,          // normalized (Hebrew when available)
    val rawName: String,
    val order: Int,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
)

@Entity(tableName = "channels", indices = [Index("epgChannelId"), Index("order")])
data class ChannelEntity(
    @PrimaryKey val id: Int,             // primary stream id
    val displayName: String,
    val logo: String?,
    val epgChannelId: String?,
    val hasEpg: Boolean,
    val categoryIds: String,             // comma-separated
    val order: Int,
    val number: Int,                     // user-visible channel number (editable later)
    val archiveDays: Int,
    val isRadio: Boolean = false,
    val isActive: Boolean = true,
)

@Entity(tableName = "channel_sources", indices = [Index("channelId")])
data class ChannelSourceEntity(
    @PrimaryKey val streamId: Int,
    val channelId: Int,
    val kind: String,                    // PRIMARY | BACKUP | ACCESSIBLE | RUSSIAN
    val rank: Int,
    val tvArchive: Boolean,
    val archiveDays: Int,
)

@Entity(tableName = "epg", indices = [Index("epgChannelId", "start")])
data class EpgEntity(
    @PrimaryKey val id: String,          // "$epgChannelId:$start"
    val epgChannelId: String,
    val start: Long,                     // epoch seconds UTC
    val end: Long,
    val title: String,
    val description: String,
    val isFiller: Boolean,
)

@Entity(tableName = "movies", indices = [Index("added")])
data class MovieEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genres: String,                  // comma-separated normalized
    val cast: String?,
    val director: String?,
    val rating: Double?,
    val runtimeMin: Int?,
    val durationSec: Int?,
    val country: String?,
    val tmdbId: Int?,
    val trailer: String?,
    val added: Long,
    val categoryIds: String,
    val containerExt: String,
    val infoLoaded: Boolean = false,
)

@Entity(tableName = "series", indices = [Index("lastModified")])
data class SeriesEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val year: Int?,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val genres: String,
    val cast: String?,
    val rating: Double?,
    val runtimeMin: Int?,
    val lastModified: Long,
    val categoryIds: String,
    val detailLoadedAt: Long = 0,
)

@Entity(tableName = "seasons", indices = [Index("seriesId")])
data class SeasonEntity(
    @PrimaryKey val id: String,          // "$seriesId:$number"
    val seriesId: Int,
    val number: Int,
    val name: String,
    val episodeCount: Int,
    val cover: String?,
)

@Entity(tableName = "episodes", indices = [Index("seriesId", "season", "number")])
data class EpisodeEntity(
    @PrimaryKey val id: String,          // server episode id (used in the stream URL)
    val seriesId: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val durationSec: Int?,
    val containerExt: String,
    val plot: String?,
    val image: String?,
    val added: Long,
)

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val key: String,         // movie:123 | episode:83122 | channel:44550
    val kind: String,
    val refId: String,
    val seriesId: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val key: String, val kind: String, val refId: String, val addedAt: Long)
