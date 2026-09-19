package tv.nakash.player

import android.content.Context
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.nakash.data.remote.ApiProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second, muted ExoPlayer for the hero preview (Netflix-style). Starts 700ms after stable focus on a channel,
 * unmutes after 3s, stops when focus leaves. Series previews are isolated from watch progress.
 */
@Singleton
class PreviewPlayer @Inject constructor(@ApplicationContext ctx: Context, okHttp: OkHttpClient, private val api: ApiProvider) {
    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttp)))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 20_000, 1_000, 2_000).build())
        .build().apply {
            volume=0f
            addListener(object: androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() { hasFrame.value=true; if(tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashPreview","first_frame=${videoSize.width}x${videoSize.height}") }
                override fun onPlayerError(error:androidx.media3.common.PlaybackException) {
                    hasFrame.value=false
                    val id=current ?: return
                    if(!tsFallback) { tsFallback=true;setMediaItem(MediaItem.fromUri(api.urls().live(id,"ts")));prepare();playWhenReady=true }
                }
            })
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var current: Int? = null
    private var currentOwner:Any?=null
    private var tsFallback=false
    val hasFrame=kotlinx.coroutines.flow.MutableStateFlow(false)
    val muted=kotlinx.coroutines.flow.MutableStateFlow(true)
    private var userMuted=false
    fun toggleMute() { userMuted=!muted.value;muted.value=userMuted;player.volume=if(userMuted) 0f else .6f }

    fun focus(streamId: Int?, owner:Any?=null) {
        currentOwner=owner
        if (streamId == current) return
        job?.cancel(); current = streamId; tsFallback=false
        hasFrame.value=false; player.stop(); player.volume = 0f; muted.value=true
        if (streamId == null) return
        job = scope.launch {
            delay(700)
            player.setMediaItem(MediaItem.fromUri(api.urls().live(streamId)))
            player.prepare(); player.playWhenReady = true
            delay(3_000); player.volume = if(userMuted) 0f else .6f;muted.value=userMuted
        }
    }
    /** Muted opening excerpt, never recorded as a watched episode. */
    fun episode(episode:tv.nakash.data.local.EpisodeEntity, owner:Any) {
        job?.cancel();current=null;currentOwner=owner;hasFrame.value=false
        player.stop();player.clearMediaItems();player.volume=0f;muted.value=true
        job=scope.launch {
            player.setMediaItem(MediaItem.fromUri(api.urls().episode(episode.id,episode.containerExt)))
            player.prepare();player.playWhenReady=true
            // Bound both failed startup and the preview itself; no automatic episode completion.
            delay(15_000)
            if(!hasFrame.value) {stop(owner);return@launch}
            delay(45_000)
            stop(owner)
        }
    }
    fun stop(owner:Any?=null) { if(owner!=null && currentOwner!==owner) return;job?.cancel(); current=null;currentOwner=null;hasFrame.value=false;player.stop();player.clearMediaItems() }
}
