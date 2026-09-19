package tv.nakash.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Keeps radio / audio playing in the background and exposes transport controls. */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var controller: PlayerController
    private var session: MediaSession? = null
    override fun onCreate() { super.onCreate(); session = MediaSession.Builder(this, controller.player).build() }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
