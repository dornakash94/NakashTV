package tv.nakash.ui.components

import androidx.compose.runtime.*
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import tv.nakash.player.PlayRequest
import tv.nakash.player.PlayerController
import tv.nakash.player.ThumbnailGenerator

@Composable
fun PlaybackFrameCache(view:PlayerView?,controller:PlayerController,thumbs:ThumbnailGenerator,enabled:Boolean) {
    val state by controller.state.collectAsState()
    val key=when(val request=state.request) {
        is PlayRequest.Movie -> "movie:${request.id}"
        is PlayRequest.Episode -> "episode:${request.episodeId}"
        else -> ""
    }
    LaunchedEffect(view,key,enabled,state.isPlaying) {
        if(!enabled || !state.isPlaying || key.isBlank() || view==null) return@LaunchedEffect
        while(true) {
            val player=controller.player
            val seconds=player.currentPosition/1000
            if(player.videoSize.width>0 && player.isPlaying) {
                thumbs.capture(view,key,seconds)
                player.currentMediaItem?.localConfiguration?.uri?.toString()?.let {thumbs.request(key,it,seconds,player.duration/1000)}
            }
            delay(2_000)
        }
    }
}
