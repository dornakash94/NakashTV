package tv.nakash.ui.components

import androidx.compose.runtime.*
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import tv.nakash.player.PlayRequest
import tv.nakash.player.PlayerController
import tv.nakash.player.ThumbnailGenerator

/**
 * Scrubbing thumbnails, made only while the video is NOT running: copying the frame off the video surface makes the
 * GPU sync and, on weak TV boxes, it showed as a small freeze when it ran every 10 s during playback.
 *  - while playing: nothing at all;
 *  - when a scrub starts: the frame on screen, at once (the picture stands still anyway);
 *  - after 2 s paused: that frame, then the neighbourhood around it from the file, so the next scrub is ready.
 * Frames along a scrub come from the player screen ([ThumbnailGenerator.request]).
 */
@Composable
fun PlaybackFrameCache(view:PlayerView?,controller:PlayerController,thumbs:ThumbnailGenerator,scrubbing:Boolean) {
    val state by controller.state.collectAsState()
    val key=when(val request=state.request) {
        is PlayRequest.Movie -> "movie:${request.id}"
        is PlayRequest.Episode -> "episode:${request.episodeId}"
        else -> ""
    }
    LaunchedEffect(view,key,state.isPlaying,scrubbing) {
        if(key.isBlank() || view==null) return@LaunchedEffect
        if(state.isPlaying && !scrubbing) return@LaunchedEffect
        if(!scrubbing) delay(2_000)
        val player=controller.player
        if(player.videoSize.width<=0) return@LaunchedEffect
        val seconds=player.currentPosition/1000
        thumbs.capture(view,key,seconds)
        if(!scrubbing) {
            val uri=player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return@LaunchedEffect
            val duration=player.duration.takeIf {it>0} ?: return@LaunchedEffect
            thumbs.request(key,uri,seconds,duration/1000)
        }
    }
}
