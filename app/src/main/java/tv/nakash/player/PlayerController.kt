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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.ChannelSourceEntity
import tv.nakash.data.local.EpgEntity
import tv.nakash.data.remote.ApiProvider
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.data.repo.UserRepository
import tv.nakash.domain.PlaybackPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** Player errors that mean the decoder, not the stream: worth trying the device's next decoder. */
private val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

/** What a provider answers for an episode id it no longer has: not a video, or not found. */
private val STALE_EPISODE = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
)

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
    /** Catch-up: the absolute time (epoch s) the current timeshift chunk starts at. Wall clock = this + position. */
    val archiveStart: Long = 0,
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
    private val profileSync: tv.nakash.data.profile.ProfileSync,
    private val playbackPrefs: tv.nakash.data.local.PlaybackPreferences,
    private val diagnostics: tv.nakash.data.diag.Diagnostics,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    val zapChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    /** The playing video's frame rate as the stream declares it (0 = unknown), for matching the TV's refresh rate. */
    val videoFrameRate = MutableStateFlow(0f)

    val player: ExoPlayer by lazy { build() }
    /** True while tunneled: frames never reach the app then, so nothing can be copied off the video surface. */
    val tunneled: Boolean get() = trackSelector.parameters.tunnelingEnabled
    private val trackSelector by lazy { androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx) }
    private var playJob: Job? = null
    private var stallJob: Job? = null
    private var progressJob: Job? = null
    private var m3u8Failures = 0
    // Decoders that failed on the current title; skipped when the player retries it (see handleError).
    private val failedDecoders = mutableSetOf<String>()
    /** The video decoder in use right now (null when none is). */
    @Volatile private var videoDecoder: String? = null
    /** Episodes (series:season:number) whose list was already reloaded after a failure: once each per app run. */
    private val staleRetried = mutableSetOf<String>()
    private var chunkStartedAt = 0L
    /** Broadcast time when the current catch-up chunk was requested: the provider serves recorded minutes only up to it. */
    private var chunkRequestedAt = 0L
    /** A notice for the next state (play() rebuilds the state, so a toast set right before it would be lost). */
    private var pendingToast: String? = null

    // Same interceptors (fixed User-Agent, auth), but its own request queue: catalog/EPG sync and image loads on
    // the shared client (5 requests per host) could hold the video's requests back. A dead route fails fast.
    private val videoClient: OkHttpClient by lazy { okHttp.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .apply { if (tv.nakash.BuildConfig.DEBUG) addNetworkInterceptor { chain ->
            // Debug builds only: host, status, range and timing of each video request (never the path: it holds the login).
            val t0 = System.nanoTime(); val r = chain.request(); val resp = chain.proceed(r)
            android.util.Log.i("NakashNet", "${r.url.scheme}://${r.url.host} ${resp.code} range=${r.header("Range")} ${(System.nanoTime() - t0) / 1_000_000}ms redirect=${resp.header("Location")?.let { java.net.URI(it).host }} len=${resp.header("Content-Length")}")
            resp
        } }
        .build() }

    /**
     * Called when a title page opens: opens the connections to the provider and its playback server ahead of the Play
     * press (they stay pooled for minutes), so the stream's first requests skip the connection and TLS setup. Reads one
     * byte. (Reusing the redirect target itself is not safe: the provider's playback URL is tied to the request.)
     */
    fun prewarm(req: PlayRequest) {
        val url = when (req) {
            is PlayRequest.Movie -> api.urls().movie(req.id, req.ext)
            is PlayRequest.Episode -> api.urls().episode(req.episodeId, req.ext)
            else -> return
        }
        scope.launch(Dispatchers.IO) {
            runCatching { videoClient.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().close() }
        }
    }

    /**
     * The player's internal thread, shared with the preload (a preloaded source must live on the player's playback
     * looper). Audio priority, as ExoPlayer's own playback thread.
     */
    private val playbackThread by lazy { android.os.HandlerThread("NakashPlayback", android.os.Process.THREAD_PRIORITY_AUDIO).apply { start() } }
    private val playbackHandler by lazy { android.os.Handler(playbackThread.looper) }
    private lateinit var loadControl: DefaultLoadControl

    // ---------- preload (title page) ----------
    // While the viewer is on a title page, the video that Play would start is opened ahead: the provider's redirect, the
    // MP4 index (1.5 s of the 3.2 s start measured on the emulator), the seek to the resume point and ~2 s of video. No
    // decoder is started, so the page's trailer is untouched. One at a time; dropped when the page closes, when another
    // video plays, or after 90 s unused, so it never holds a provider connection for long.
    private class Preloaded(val url: String, val startMs: Long, val source: androidx.media3.exoplayer.source.preload.PreloadMediaSource) { var used = false }
    private var preloaded: Preloaded? = null
    private var preloadExpiry: Job? = null
    private val preloadFactory by lazy {
        val p = player
        val bandwidth = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(ctx)
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx).also { it.init({}, bandwidth) }
        val control = object : androidx.media3.exoplayer.source.preload.PreloadMediaSource.PreloadControl {
            override fun onSourcePrepared(src: androidx.media3.exoplayer.source.preload.PreloadMediaSource) = true
            override fun onTracksSelected(src: androidx.media3.exoplayer.source.preload.PreloadMediaSource) = true
            override fun onContinueLoadingRequested(src: androidx.media3.exoplayer.source.preload.PreloadMediaSource, bufferedPositionUs: Long): Boolean {
                // Measured from the preload's start (0 at the resume point), not from the start of the file: ~2 s of
                // video, then loading stops. (Were it ever absolute, this stops at once: index only, never more.)
                val more = bufferedPositionUs < PRELOAD_VIDEO_US
                if (!more && tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPreload", "ready buffered=${bufferedPositionUs / 1000}ms")
                return more
            }
            override fun onUsedByPlayer(src: androidx.media3.exoplayer.source.preload.PreloadMediaSource) { if (tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPreload", "used by player") }
            override fun onPreloadError(e: androidx.media3.exoplayer.source.preload.PreloadException, src: androidx.media3.exoplayer.source.preload.PreloadMediaSource) {
                if (tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPreload", "error ${e.cause?.javaClass?.simpleName}")
            }
        }
        androidx.media3.exoplayer.source.preload.PreloadMediaSource.Factory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(videoClient)), control, selector, bandwidth,
            Array(p.rendererCount) { p.getRenderer(it).capabilities }, loadControl.allocator, playbackThread.looper)
    }

    private fun urlOf(req: PlayRequest): String? = when (req) {
        is PlayRequest.Movie -> api.urls().movie(req.id, req.ext)
        is PlayRequest.Episode -> api.urls().episode(req.episodeId, req.ext)
        else -> null
    }

    /** Opens [req] ahead of the Play press (movies and episodes only). Nothing when that video is already the player's. */
    fun preload(req: PlayRequest) {
        val url = runCatching { urlOf(req) }.getOrNull() ?: return
        val startMs = when (req) { is PlayRequest.Movie -> req.startMs; is PlayRequest.Episode -> req.startMs; else -> 0 }
        // The same video already in the player (back from it to the page, paused): nothing to open.
        if (_state.value.error == null && _state.value.request?.let { runCatching { urlOf(it) }.getOrNull() } == url) return
        preloaded?.let { if (it.url == url && it.startMs == startMs) return }
        dropPreload()
        val source = preloadFactory.createMediaSource(MediaItem.fromUri(url))
        val startUs = startMs * 1000
        preloaded = Preloaded(url, startMs, source)
        playbackHandler.post { source.preload(startUs) }
        preloadExpiry = scope.launch { delay(90_000); dropPreload() }
        if (tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPreload", "start at ${startMs}ms")
    }

    /** Lets an unused preload go (its connection closes). A preload the player took is the player's now. */
    fun dropPreload() {
        preloadExpiry?.cancel(); preloadExpiry = null
        val p = preloaded ?: return
        preloaded = null
        if (!p.used) { playbackHandler.post { p.source.releasePreloadMediaSource() }; if (tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPreload", "dropped") }
    }
    /** The preloaded source the player is playing, released once the player moves on to another video. */
    private var playingPreload: androidx.media3.exoplayer.source.preload.PreloadMediaSource? = null

    private fun build(): ExoPlayer {
        val dsf = OkHttpDataSource.Factory(videoClient)
        loadControl = DefaultLoadControl.Builder()
            // Continuous loading (min = max): the buffer is topped up all the time in small reads. With 15 s / 30 s it
            // paused at 30 s and then pulled 15 s of video in one burst, which showed as a small freeze every ~20-30 s
            // in movies and series on weak TV boxes. Start stays fast (playback begins after 0.75-1.5 s of video).
            .setBufferDurationsMs(30_000, 30_000, 750, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        // Decoder fallback: if the TV's first decoder for a format fails to start, try the next one instead of an error.
        // A decoder that starts but then fails on a file (some H.264 headers the TV's or emulator's decoder rejects)
        // is skipped on retry, so the next one, often the software decoder, plays it.
        val selector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
            androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filterNot { it.name in failedDecoders }
        }
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(ctx).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setPlaybackLooper(playbackThread.looper)
            .setHandleAudioBecomingNoisy(true)
            .build().also { p ->
                // Resume and seeks land on the nearest keyframe: no downloading and decoding up to a whole GOP
                // (several seconds of video on IPTV streams) before the first picture.
                p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
                    override fun onVideoDisabled(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, counters: androidx.media3.exoplayer.DecoderCounters) { videoDecoder = null }
                })
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(isPlaying = isPlaying) }
                        profileSync.hold(isPlaying)
                        if (isPlaying) watchFrames() else watchJob?.cancel()
                        if(tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPlayback","playing=$isPlaying")
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        videoFrameRate.value = p.videoFormat?.frameRate?.takeIf { it > 1f } ?: 0f
                    }
                    override fun onRenderedFirstFrame() {
                        if(tv.nakash.BuildConfig.DEBUG) {
                            val f = p.videoFormat
                            val a = p.audioFormat
                            android.util.Log.i("NakashPlayback","first_frame=${p.videoSize.width}x${p.videoSize.height} declared_fps=${f?.frameRate} mime=${f?.sampleMimeType} audio=${a?.sampleMimeType} ch=${a?.channelCount} rate=${a?.sampleRate} container=${f?.containerMimeType}")
                            // The real frame rate: frames the decoder rendered over 10 s of playback.
                            // Every 5 s for a minute: frames the decoder rendered / dropped / skipped (dropped = too late to show).
                            scope.launch {
                                var r0=0; var d0=0; var s0=0
                                repeat(13) { i ->
                                    val c=p.videoDecoderCounters; c?.ensureUpdated()
                                    val r=c?.renderedOutputBufferCount ?: 0; val d=c?.droppedBufferCount ?: 0; val sk=c?.skippedOutputBufferCount ?: 0
                                    if(i>0) android.util.Log.i("NakashPlayback","frames_5s rendered=${r-r0} dropped=${d-d0} skipped=${sk-s0} maxConsecDropped=${c?.maxConsecutiveDroppedBufferCount} buffer=${p.totalBufferedDuration/1000}s")
                                    r0=r; d0=d; s0=sk; delay(5_000)
                                }
                            }
                        }
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
        _ended.value=0L; recoveries=0; resumeOnReturn=null; failedDecoders.clear()
        // One video at a time: the card preview and the trailer web renderer give their decoders and memory back.
        videoFrameRate.value = 0f
        preview.stop(); tv.nakash.ui.components.TrailerPlayer.release(); saveNow(); stopProgress(); stallJob?.cancel(); playJob?.cancel(); m3u8Failures = 0
        // Speed: movies and episodes only; a new title starts at ×1, the next episode of the same series keeps it.
        val prev = _state.value.request
        val keepSpeed = req is PlayRequest.Episode && prev is PlayRequest.Episode && prev.seriesId == req.seriesId
        if (!keepSpeed) speed.value = 1f
        player.setPlaybackSpeed(if (req is PlayRequest.Movie || req is PlayRequest.Episode) speed.value else 1f)
        playJob = scope.launch {
            when (req) {
                is PlayRequest.Live -> {
                    val sources = catalog.sources(req.channel.id).ifEmpty { listOf(ChannelSourceEntity(req.channel.id, req.channel.id, "PRIMARY", 0, req.channel.archiveDays > 0, req.channel.archiveDays)) }
                    val sourceIndex = req.sourceIndex.coerceIn(0, sources.lastIndex)
                    _state.value = PlayerUiState(request = req, sources = sources, sourceIndex = sourceIndex, isLive = true, toast = pendingToast.also { pendingToast = null })
                    setUrl(api.urls().live(sources[sourceIndex].streamId), req.channel.displayName, live = true)
                    user.saveProgress("channel", req.channel.id.toString(), 0, 0)
                }
                is PlayRequest.Archive -> {
                    val sources = catalog.sources(req.channel.id)
                    val sid = (sources.firstOrNull { it.tvArchive } ?: sources.firstOrNull())?.streamId ?: req.channel.id
                    // Continuous catch-up: from any past minute up to the live edge, across guide program boundaries.
                    val now = System.currentTimeMillis() / 1000
                    val start = PlaybackPolicy.timeshiftStart(req.program.start + req.offsetSeconds, now, req.channel.archiveDays)
                    _state.value = PlayerUiState(request = req, isLive = false, archiveStart = start)
                    chunkStartedAt = System.currentTimeMillis(); chunkRequestedAt = now
                    setUrl(api.urls().timeshift(sid, start, PlaybackPolicy.timeshiftMinutes(start, now)), req.program.title, live = false)
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

    /** Playback speed for movies and episodes (×1 → ×1.25 → ×1.5 → ×2 → ×0.75). Kept for the next episode of the same series. */
    val speed = MutableStateFlow(1f)
    fun cycleSpeed() {
        // Tunneled audio cannot change speed (the TV's hardware paces it).
        if (playbackPrefs.state.value.tunneling) { toast("שינוי מהירות לא זמין כשניגון חלק (מנהרה) פעיל"); return }
        val next = SPEEDS[(SPEEDS.indexOf(speed.value) + 1) % SPEEDS.size]
        speed.value = next; player.setPlaybackSpeed(next); toast("מהירות ×${speedLabel(next)}")
    }

    fun stop() {
        resumeOnReturn=null; watchJob?.cancel()
        playJob?.cancel(); stallJob?.cancel(); stopProgress(); saveNow()
        player.stop(); player.clearMediaItems()
        _state.value = PlayerUiState()
    }

    // ---------- the app leaves / comes back to the screen ----------
    private var resumeOnReturn: PlayRequest.Live? = null
    /**
     * Home button or another app on top: nothing keeps playing behind the launcher. Live lets go of the stream and is
     * tuned again on return; a movie, episode or catch-up pauses where it is (progress saved).
     */
    fun background() {
        val st = _state.value; val req = st.request ?: return
        if (req is PlayRequest.Live) {
            resumeOnReturn = req.copy(sourceIndex = st.sourceIndex)
            playJob?.cancel(); stallJob?.cancel(); player.stop()
        } else { player.pause(); saveNow() }
    }
    fun foreground() {
        val req = resumeOnReturn ?: return; resumeOnReturn = null
        if ((_state.value.request as? PlayRequest.Live)?.channel?.id == req.channel.id) play(req)
    }

    /** Bumped to have the screen build a fresh video view (a new surface), see [watchFrames]. */
    val surfaceEpoch = MutableStateFlow(0)
    private var watchJob: Job? = null
    private var recoveries = 0
    /**
     * A picture that stands still while the clock runs on (TV boxes, right after the app opened itself at start-up):
     * the video stopped coming out of the decoder. Checked every 2 s while playing: no new frame for two checks in a
     * row while the position moved on means the video path is rebuilt (a new view and a fresh decoder, same spot).
     * At most twice per title. Not when tunneled: frames do not pass through the app then.
     */
    private fun watchFrames() {
        watchJob?.cancel()
        if (tunneled) return
        watchJob = scope.launch {
            var lastRendered = -1; var lastPos = player.currentPosition; var stalls = 0
            while (true) {
                delay(2_000)
                val counters = player.videoDecoderCounters
                if (player.videoFormat == null || counters == null) { lastRendered = -1; continue }
                counters.ensureUpdated()
                val rendered = counters.renderedOutputBufferCount; val pos = player.currentPosition
                stalls = if (lastRendered >= 0 && rendered == lastRendered && pos - lastPos >= 1_500) stalls + 1 else 0
                lastRendered = rendered; lastPos = pos
                if (stalls >= 2 && recoveries < 2) { recoveries++; recoverVideo(); return@launch }
            }
        }
    }
    private fun recoverVideo() {
        if (tv.nakash.BuildConfig.DEBUG) android.util.Log.w("NakashPlayback", "video_stalled recover=$recoveries")
        surfaceEpoch.value++
        when (val req = _state.value.request ?: return) {
            is PlayRequest.Live -> { val n = recoveries; play(req.copy(sourceIndex = _state.value.sourceIndex)); recoveries = n }
            else -> { val pos = player.currentPosition; player.stop(); player.seekTo(pos); player.prepare(); player.playWhenReady = true }
        }
    }

    // ---------- internals ----------
    private fun setUrl(url: String, title: String, live: Boolean, startMs: Long = 0) {
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        // A fresh decoder for every new stream. Reused across a resolution change (a 720p channel after a 1080p one),
        // the decoder's pictures were drawn unscaled in the corner over the old frame.
        player.stop()
        // "ניגון חלק (מנהרה)": movies/episodes go straight from the decoder to the display hardware, which paces every
        // frame against the screen (how the big streaming apps play on Android TV). Live stays untunneled (zapping).
        val tunnel = !live && playbackPrefs.state.value.tunneling
        if (trackSelector.parameters.tunnelingEnabled != tunnel)
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(tunnel))
        val pre = preloaded?.takeIf { !live && it.url == url && it.startMs == startMs }
        val previous = playingPreload
        if (pre != null) {
            pre.used = true; preloaded = null; preloadExpiry?.cancel()
            player.setMediaSource(pre.source, startMs); playingPreload = pre.source
        } else {
            dropPreload()
            player.setMediaItem(item, if (startMs > 0) startMs else C.TIME_UNSET); playingPreload = null
        }
        // Posted after the player's own switch on the same thread: the old source is released only once it let go.
        if (previous != null && previous !== playingPreload) playbackHandler.post { previous.releasePreloadMediaSource() }
        player.prepare(); player.playWhenReady = true
        _state.update { it.copy(error = null) }
        if (live) armStallTimer()
    }

    private fun armStallTimer() {
        stallJob?.cancel()
        // Catch-up that reached what the provider had recorded when it was requested: the stream is over even if
        // the connection lingers. Continue right away instead of sitting on a spinner until it closes.
        val archive = _state.value.request as? PlayRequest.Archive
        if (archive != null && _state.value.archiveStart + player.currentPosition / 1000 >= chunkRequestedAt - 90) {
            stallJob = scope.launch { delay(2_500); if (player.playbackState == Player.STATE_BUFFERING) continueTimeshift(archive) }
            return
        }
        stallJob = scope.launch {
            delay(if(_state.value.isLive) 10_000 else 25_000)
            if(player.playbackState == Player.STATE_BUFFERING) {
                if(_state.value.isLive) failover("timeout") else {
                    // Seconds of video already downloaded but nothing shown: the decoder is stuck or too slow (a software
                    // HEVC decoder on a weak box), not the provider. Try the next decoder from the same spot first.
                    val decoderStuck = player.totalBufferedDuration > 5_000
                    val dec = videoDecoder
                    report(IllegalStateException(if (decoderStuck) "buffered ${player.totalBufferedDuration / 1000}s but no frames for 25 s" else "no data from the provider for 25 s"), dec, if (decoderStuck) "DECODER_STALL" else "STALL_TIMEOUT")
                    if (decoderStuck && dec != null && failedDecoders.add(dec)) {
                        android.util.Log.w("NakashPlayback", "decoder $dec stalled with a full buffer, retrying with the next one")
                        val pos = player.currentPosition; player.stop(); player.seekTo(pos); player.prepare(); player.playWhenReady = true
                        return@launch
                    }
                    player.stop()
                    _state.update { it.copy(isBuffering=false,error = if (decoderStuck) "${cantDecode()}\n(קוד DECODER_STALL)" else "הספק לא מגיב כרגע. אפשר לנסות שוב.\n(קוד STALL)") }
                }
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val st = _state.value
        val chain = generateSequence<Throwable>(e) { it.cause }.take(12).toList()
        val decoder = chain.filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException>().firstOrNull()?.codecInfo?.name
            ?: chain.filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>().firstOrNull()?.codecInfo?.name
            ?: videoDecoder?.takeIf { e.errorCode in DECODER_ERRORS }
        android.util.Log.w("NakashPlayback", "error ${e.errorCodeName} decoder=$decoder", e)
        report(e, decoder)
        // Any decoder failure (it would not start, does not take this format, or failed mid-file): the next decoder
        // on the device gets the title, often the software one.
        if (e.errorCode in DECODER_ERRORS && decoder != null && failedDecoders.add(decoder)) {
            android.util.Log.w("NakashPlayback", "decoder $decoder failed, retrying with the next one")
            player.prepare(); return
        }
        // An episode the provider no longer serves under this id (it answers with something that is not a video, or
        // 404): the series' episode list on the TV is out of date. Reload it once and play the episode now listed at
        // the same season and number. (The father's S02E02 was re-uploaded as a new id; his TV kept the old one.)
        val ep = st.request as? PlayRequest.Episode
        if (ep != null && e.errorCode in STALE_EPISODE && staleRetried.add("${ep.seriesId}:${ep.season}:${ep.number}")) {
            android.util.Log.w("NakashPlayback", "episode ${ep.episodeId} unplayable (${e.errorCodeName}), reloading series ${ep.seriesId}")
            _state.update { it.copy(isBuffering = true, error = null) }
            scope.launch {
                val fresh = runCatching { withContext(Dispatchers.IO) {
                    catalog.ensureSeriesDetail(ep.seriesId, force = true); catalog.episode(ep.seriesId, ep.season, ep.number)
                } }.getOrNull()
                if (fresh != null && fresh.id != ep.episodeId) {
                    android.util.Log.w("NakashPlayback", "episode ${ep.episodeId} is now ${fresh.id}")
                    play(ep.copy(episodeId = fresh.id, ext = fresh.containerExt))
                } else showPlaybackError(e)
            }
            return
        }
        if (!st.isLive) { showPlaybackError(e); return }
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

    private fun showPlaybackError(e: PlaybackException) {
        val blockedHttp=generateSequence<Throwable>(e) {it.cause}.take(12).any {it.message?.contains("CLEARTEXT",ignoreCase=true)==true}
        // The code under the message lets a photo of the screen tell which failure it was.
        _state.update { it.copy(isBuffering=false,error = (if(blockedHttp) "שרת השידור דורש אישור לחיבור HTTP. הצפייה תתחיל לאחר אישור והתקנת העדכון." else when(e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "אין חיבור לשידור. בדוק את הרשת ונסה שוב."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "התוכן לא זמין כרגע אצל הספק. נסה שוב בעוד רגע."
            in DECODER_ERRORS -> cantDecode()
            in STALE_EPISODE -> "הספק לא מחזיר את הקובץ של התוכן הזה. אפשר לנסות שוב מאוחר יותר."
            else -> "לא הצלחנו להתחיל את הצפייה. אפשר לנסות שוב."
        }) + "\n(קוד ${e.errorCode})") }
    }

    /** "This device can't play this video", naming the format (HEVC is what weak boxes most often cannot). */
    private fun cantDecode(): String {
        val fmt = when (player.videoFormat?.sampleMimeType) {
            androidx.media3.common.MimeTypes.VIDEO_H265 -> " (HEVC)"; androidx.media3.common.MimeTypes.VIDEO_H264 -> " (H.264)"
            androidx.media3.common.MimeTypes.VIDEO_AV1 -> " (AV1)"; androidx.media3.common.MimeTypes.VIDEO_VP9 -> " (VP9)"; else -> ""
        }
        return "המכשיר לא מצליח לנגן את פורמט הווידאו של התוכן הזה$fmt."
    }

    /** Sends what failed and what was playing (the file's formats as the player saw them) to the error reports. */
    private fun report(e: Throwable, decoder: String?, code: String = (e as? PlaybackException)?.errorCodeName ?: "UNKNOWN") {
        val st = _state.value
        val what: Map<String, String?> = when (val r = st.request) {
            is PlayRequest.Episode -> mapOf("kind" to "episode", "title" to r.title, "id" to r.episodeId, "series" to "${r.seriesId}", "se" to "S${r.season}E${r.number}", "ext" to r.ext)
            is PlayRequest.Movie -> mapOf("kind" to "movie", "title" to r.title, "id" to "${r.id}", "ext" to r.ext)
            is PlayRequest.Live -> mapOf("kind" to "live", "title" to r.channel.displayName, "source" to "${st.sourceIndex}", "ts" to "${st.usedTsFallback}")
            is PlayRequest.Archive -> mapOf("kind" to "archive", "title" to r.channel.displayName)
            null -> mapOf("kind" to "none")
        }
        val v = player.videoFormat; val a = player.audioFormat
        diagnostics.playback(e, code, what + mapOf(
            "decoder" to decoder, "failedDecoders" to failedDecoders.joinToString().ifEmpty { null },
            "video" to v?.let { "${it.sampleMimeType} ${it.codecs} ${it.width}x${it.height}" },
            "audio" to a?.let { "${it.sampleMimeType} ${it.codecs} ch=${it.channelCount} ${it.sampleRate}" },
            "container" to (v ?: a)?.containerMimeType, "positionMs" to "${player.currentPosition}",
            "tunneling" to "${trackSelector.parameters.tunnelingEnabled}",
        ))
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

    /**
     * A catch-up chunk ended. Keep going like real TV: request the next chunk from where it stopped, or — once
     * caught up with the broadcast (or if the server returned an empty chunk) — switch to live instead of freezing.
     */
    private fun continueTimeshift(req: PlayRequest.Archive) {
        val now = System.currentTimeMillis() / 1000
        val reached = _state.value.archiveStart + player.currentPosition / 1000
        val playedMs = System.currentTimeMillis() - chunkStartedAt
        if (PlaybackPolicy.nearLive(reached, now) || playedMs < 20_000) {
            pendingToast = "הגעת לשידור החי"
            play(PlayRequest.Live(req.channel))
            return
        }
        play(req.copy(offsetSeconds = reached - req.program.start))
    }

    /** Series: ended -> mark watched; the UI observes `ended` to offer the next episode. */
    private val _ended = MutableStateFlow(0L)
    val ended: StateFlow<Long> = _ended
    private fun onEnded() {
        val req = _state.value.request
        if (req is PlayRequest.Archive) { continueTimeshift(req); return }
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

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
/** "1.5", "2", "0.75": no trailing zero. */
fun speedLabel(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()

/** How much video a title page opens ahead of the Play press. */
private const val PRELOAD_VIDEO_US = 2_000_000L
