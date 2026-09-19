package tv.nakash.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Xtream Codes / XUI player_api.php. Base URL is per account (e.g. https://ilvip.net/), set at runtime.
 * All endpoints take username/password as query params; the AuthInterceptor adds them.
 */
interface XtreamApi {
    @GET("player_api.php") suspend fun auth(): AuthResponse
    @GET("player_api.php?action=get_live_categories") suspend fun liveCategories(): List<CategoryDto>
    @GET("player_api.php?action=get_live_streams") suspend fun liveStreams(): List<LiveStreamDto>
    @GET("player_api.php?action=get_vod_categories") suspend fun vodCategories(): List<CategoryDto>
    @GET("player_api.php?action=get_vod_streams") suspend fun vodStreams(): List<VodStreamDto>
    @GET("player_api.php?action=get_vod_info") suspend fun vodInfo(@Query("vod_id") vodId: Int): VodInfoResponse
    @GET("player_api.php?action=get_series_categories") suspend fun seriesCategories(): List<CategoryDto>
    @GET("player_api.php?action=get_series") suspend fun series(): List<SeriesDto>
    @GET("player_api.php?action=get_series_info") suspend fun seriesInfo(@Query("series_id") seriesId: Int): SeriesInfoResponse
    @GET("player_api.php?action=get_short_epg") suspend fun shortEpg(@Query("stream_id") streamId: Int, @Query("limit") limit: Int = 6): ShortEpgResponse
}
