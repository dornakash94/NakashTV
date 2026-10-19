package tv.nakash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import tv.nakash.data.local.AccountStore
import tv.nakash.ui.nav.NakashNavHost
import tv.nakash.ui.login.LoginScreen
import tv.nakash.ui.theme.NakashTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accountStore: AccountStore
    @Inject lateinit var profiles: tv.nakash.data.profile.ProfileStore
    @Inject lateinit var profileSync: tv.nakash.data.profile.ProfileSync
    @Inject lateinit var updater: tv.nakash.data.update.AppUpdater
    @Inject lateinit var playback: tv.nakash.player.PlayerController
    @Inject lateinit var preview: tv.nakash.player.PreviewPlayer
    @Inject lateinit var recs: tv.nakash.data.repo.RecommendationRepository
    private var stopped = false
    private var stoppedAt = 0L
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Settings is learning a remote button: it gets every key first, before focus, dialogs or the media session.
        tv.nakash.data.local.KeyCapture.onKey?.let { listener ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) { tv.nakash.data.local.KeyCapture.swallowUp = event.keyCode; listener(event.keyCode) }
                return true
            }
        }
        if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == tv.nakash.data.local.KeyCapture.swallowUp) {
            tv.nakash.data.local.KeyCapture.swallowUp = -1; return true
        }
        // Hardware keyboards send ESC separately from the TV remote's BACK key.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    /** Leaving the app (home button, another input): stop every video, free the trailer renderer and decoded images. */
    override fun onStop() {
        super.onStop()
        stopped = true; stoppedAt = System.currentTimeMillis()
        playback.background(); preview.background()
        profileSync.syncOnLeave(this)
        tv.nakash.ui.components.TrailerPlayer.release()
        coil3.SingletonImageLoader.get(this).memoryCache?.clear()
    }

    /** Back on screen: the video gets a fresh surface (the old one went away with the window), live is tuned again. */
    override fun onStart() {
        super.onStart()
        if (!stopped) return
        stopped = false
        recs.refreshIfStale(System.currentTimeMillis() - stoppedAt)
        playback.surfaceEpoch.value++
        playback.foreground(); preview.foreground()
    }

    /**
     * The screen came on (TV boxes finish switching the HDMI output on after start-up, while the app may already be up):
     * a video surface made before that can stay frozen on some boxes, so the player builds a new one.
     */
    private val displays = object : android.hardware.display.DisplayManager.DisplayListener {
        private var on = true
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != android.view.Display.DEFAULT_DISPLAY) return
            val now = getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(id)?.state == android.view.Display.STATE_ON
            if (now && !on) playback.surfaceEpoch.value++
            on = now
        }
    }
    override fun onDestroy() {
        getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(displays)
        super.onDestroy()
    }

    /** TVs run with little memory: give back the heaviest things before the system has to kill the app. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tv.nakash.ui.components.TrailerPlayer.release()
            coil3.SingletonImageLoader.get(this).memoryCache?.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.core.splashscreen.SplashScreen.Companion.run { installSplashScreen() }
        super.onCreate(savedInstanceState)
        getSystemService(android.hardware.display.DisplayManager::class.java)?.registerDisplayListener(displays, null)
        // Debug builds only: `am start ... --es route seriesDetail/123` opens that screen after the profile is chosen.
        if (BuildConfig.DEBUG) intent?.getStringExtra("route")?.let { tv.nakash.ui.nav.DebugRoute.pending = it }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            NakashTheme {
                val account by accountStore.account.collectAsState()
                // After the provider login: "מי צופה?" on every start, then the app as that profile.
                val profile by profiles.current.collectAsState()
                when {
                    account == null -> LoginScreen()
                    profile == null -> tv.nakash.ui.profile.ProfilePickerScreen()
                    else -> androidx.compose.runtime.key(profile!!.id) { NakashNavHost() }
                }
                // "גרסה חדשה זמינה" over whatever screen is up (it waits while the player is on screen).
                androidx.compose.runtime.LaunchedEffect(Unit) { updater.start() }
                tv.nakash.ui.update.UpdateDialog(updater)
            }
        }
    }
}
