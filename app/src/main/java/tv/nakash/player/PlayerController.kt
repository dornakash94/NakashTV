package tv.nakash.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.ChannelSourceEntity
import tv.nakash.data.local.EpgEntity
import tv.nakash.data.remote.ApiProvider
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.data.repo.UserRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PlayRequest {
    data class Live(val channel: ChannelEntity, val sourceIndex: Int = 0) : PlayRequest
    data class Archive(val channel: ChannelEntity, val program: EpgEntity, val offsetSeconds:Long = 0) : PlayRequest
    data class Movie(val id: Int, val title: String, val ext: String, val startMs: Long = 0) : PlayRequest
    data class Episode(val episodeId: String, val seriesId: Int, val season: Int, val number: Int, val title: String, val ext: String, val startMs: Long = 0) : PlayRequest
}

data class PlayerUiState(
    val request: PlayRequest? = null,
    val sourceIndex: Int = 0,
    val sources: List<ChannelSourceEntity> = emptyList(),
    val isLive: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val toast: String? = null,
    val usedTsFallback: Boolean = false,
)

/**
 * One ExoPlayer for the main screen. Owns failover:
 *  live: on error or stall > 8s -> next source (backup 1 -> 2 -> 3); if m3u8 fails twice -> .ts for the same source.
 *  VOD: saves progress every 10s and on stop.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val okHttp: OkHttpClient,
    private val api: ApiProvider,
    private val catalog: CatalogRepository,
    private val user: UserRepository,
    private val preview: PreviewPlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    val zapChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())

    val player: ExoPlayer by lazy { build() }
    private var playJob: Job? = null
    private var stallJob: Job? = null
    private var progressJob: Job? = null
    private var m3u8Failures = 0

    private fun build(): ExoPlayer {
        val dsf = OkHttpDataSource.Factory(okHttp) // carries the fixed User-Agent + auth interceptor
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 750, 1_500) // fast zapping: start after 1.5s
            .setPrioritizeTimeOverSizeThresholds(true).build()
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(isPlaying = isPlaying) }
                        if(tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPlayback","playing=$isPlaying")
                    }
                    override fun onRenderedFirstFrame() {
                        if(tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPlayback","first_frame=${p.videoSize.width}x${p.videoSize.height}")
                    }
                    override fun onPlaybackStateChanged(s: Int) {
                        _state.update { it.copy(isBuffering = s == Player.STATE_BUFFERING, durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0) }
                        if (s == Player.STATE_BUFFERING) armStallTimer() else stallJob?.cancel()
                        if (s == Player.STATE_ENDED) onEnded()
                    }
                    override fun onPlayerError(error: PlaybackException) { handleError(error) }
                })
            }
    }

    // ---------- public API ----------
    fun play(req: PlayRequest) {
        _ended.value=0L
        preview.stop(); saveNow(); stopProgress(); stallJob?.cancel(); playJob?.cancel(); m3u8Failures = 0
        playJob = scope.launch {
            when (req) {
                is PlayRequest.Live -> {
                    val sources = catalog.sources(req.channel.id).ifEmpty { listOf(ChannelSourceEntity(req.channel.id, req.channel.id, "PRIMARY", 0, req.channel.archiveDays > 0, req.channel.archiveDays)) }
                    val sourceIndex = req.sourceIndex.coerceIn(0, sources.lastIndex)
                    _state.value = PlayerUiState(request = req, sources = sources, sourceIndex = sourceIndex, isLive = true)
                    setUrl(api.urls().live(sources[sourceIndex].streamId), req.channel.displayName, live = true)
                    user.saveProgress("channel", req.channel.id.toString(), 0, 0)
                }
                is PlayRequest.Archive -> {
                    val prim = catalog.sources(req.channel.id).firstOrNull { it.tvArchive } ?: catalog.sources(req.channel.id).firstOrNull()
                    val sid = prim?.streamId ?: req.channel.id
                    _state.value = PlayerUiState(request = req, isLive = false)
                    val offset=tv.nakash.domain.PlaybackPolicy.archiveOffsetSeconds(req.program.start,req.program.end,System.currentTimeMillis()/1000,req.offsetSeconds)
                    val archiveStart=req.program.start+offset
                    setUrl(api.urls().timeshift(sid, archiveStart, tv.nakash.domain.PlaybackPolicy.archiveMinutes(archiveStart,req.program.end)), req.program.title, live = false)
                }
                is PlayRequest.Movie -> {
                    _state.value = PlayerUiState(request = req, isLive = false)
                    setUrl(api.urls().movie(req.id, req.ext), req.title, live = false, startMs = req.startMs)
                    startProgress()
                }
                is PlayRequest.Episode -> {
                    _state.value = PlayerUiState(request = req, isLive = false)
                    setUrl(api.urls().episode(req.episodeId, req.ext), req.title, live = false, startMs = req.startMs)
                    startProgress()
                }
            }
        }
    }

    /** Manual source switch (menu: primary / backup / accessible / Russian). */
    fun switchSource(index: Int) {
        val st = _state.value; val src = st.sources.getOrNull(index) ?: return
        val ch = (st.request as? PlayRequest.Live)?.channel ?: return
        m3u8Failures = 0
        _state.update { it.copy(sourceIndex = index, error = null, usedTsFallback = false, toast = sourceLabel(src)) }
        setUrl(api.urls().live(src.streamId), ch.displayName, live = true)
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun seekBy(ms: Long) { player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0))) }
    fun seekTo(ms: Long) {
        val archive=_state.value.request as? PlayRequest.Archive
        if(archive!=null) {
            val offset=tv.nakash.domain.PlaybackPolicy.archiveOffsetSeconds(archive.program.start,archive.program.end,System.currentTimeMillis()/1000,ms/1000)
            play(archive.copy(offsetSeconds=offset))
        } else player.seekTo(ms)
    }
    fun clearToast() { _state.update { it.copy(toast = null) } }
    fun toast(msg: String) { _state.update { it.copy(toast = msg) } }

    fun stop() {
        playJob?.cancel(); stallJob?.cancel(); stopProgress(); saveNow()
        player.stop(); player.clearMediaItems()
        _state.value = PlayerUiState()
    }

    // ---------- internals ----------
    private fun setUrl(url: String, title: String, live: Boolean, startMs: Long = 0) {
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        player.setMediaItem(item, if (startMs > 0) startMs else C.TIME_UNSET)
        player.prepare(); player.playWhenReady = true
        _state.update { it.copy(error = null) }
        if (live) armStallTimer()
    }

    private fun armStallTimer() {
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(if(_state.value.isLive) 10_000 else 25_000)
            if(player.playbackState == Player.STATE_BUFFERING) {
                if(_state.value.isLive) failover("timeout") else {
                    player.stop()
                    _state.update { it.copy(isBuffering=false,error="הספק לא מגיב כרגע. אפשר לנסות שוב.") }
                }
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val st = _state.value
        if (!st.isLive) {
            val blockedHttp=generateSequence<Throwable>(e) {it.cause}.take(12).any {it.message?.contains("CLEARTEXT",ignoreCase=true)==true}
            _state.update { it.copy(isBuffering=false,error = if(blockedHttp) "שרת השידור דורש אישור לחיבור HTTP. הצפייה תתחיל לאחר אישור והתקנת העדכון." else when(e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "אין חיבור לשידור. בדוק את הרשת ונסה שוב."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "התוכן לא זמין כרגע אצל הספק. נסה שוב בעוד רגע."
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "המכשיר לא הצליח לפענח את הווידאו הזה."
            else -> "לא הצלחנו להתחיל את הצפייה. אפשר לנסות שוב."
        }) }; return }
        val src = st.sources.getOrNull(st.sourceIndex)
        // m3u8 -> ts fallback for the same source before moving on
        if (src != null && !st.usedTsFallback) {
            if (++m3u8Failures < 2) {
                setUrl(api.urls().live(src.streamId), (st.request as PlayRequest.Live).channel.displayName, live = true)
                return
            }
            _state.update { it.copy(usedTsFallback = true) }
            setUrl(api.urls().live(src.streamId, "ts"), (st.request as PlayRequest.Live).channel.displayName, live = true)
            return
        }
        failover(e.errorCodeName)
    }

    private fun failover(reason: String) {
        val st = _state.value
        val next = st.sourceIndex + 1
        val ch = (st.request as? PlayRequest.Live)?.channel ?: return
        if (next < st.sources.size) {
            m3u8Failures = 0
            _state.update { it.copy(sourceIndex = next, usedTsFallback = false, toast = "עברנו ל${sourceLabel(st.sources[next])}") }
            setUrl(api.urls().live(st.sources[next].streamId), ch.displayName, live = true)
        } else {
            player.stop(); _state.update { it.copy(isBuffering=false,error = "הערוץ לא זמין כרגע. נסה שוב או עבור לערוץ אחר.") }
        }
    }

    private fun sourceLabel(s: ChannelSourceEntity) = when (s.kind) {
        "BACKUP" -> "גיבוי ${s.rank}"; "ACCESSIBLE" -> "ערוץ מונגש"; "RUSSIAN" -> "רוסית"; else -> "מקור ראשי"
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(10_000)
                _state.update { it.copy(positionMs = player.currentPosition, durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0) }
                saveNow()
            }
        }
    }
    private fun stopProgress() { progressJob?.cancel(); progressJob = null }

    private fun saveNow() {
        val req = _state.value.request ?: return
        val pos = player.currentPosition; val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        scope.launch(Dispatchers.IO) {
            when (req) {
                is PlayRequest.Movie -> user.saveProgress("movie", req.id.toString(), pos, dur)
                is PlayRequest.Episode -> user.saveProgress("episode", req.episodeId, pos, dur, req.seriesId)
                else -> {}
            }
        }
    }

    /** Series: ended -> mark watched; the UI observes `ended` to offer the next episode. */
    private val _ended = MutableStateFlow(0L)
    val ended: StateFlow<Long> = _ended
    private fun onEnded() {
        val req = _state.value.request
        val duration = player.duration
        scope.launch(Dispatchers.IO) {
            when (req) {
                is PlayRequest.Movie -> user.markWatched("movie", req.id.toString(), null, duration)
                is PlayRequest.Episode -> user.markWatched("episode", req.episodeId, req.seriesId, duration)
                else -> {}
            }
        }
        _ended.value = System.currentTimeMillis()
    }
}
