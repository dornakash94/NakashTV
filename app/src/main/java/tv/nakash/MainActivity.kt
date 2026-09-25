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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            NakashTheme {
                val account by accountStore.account.collectAsState()
                if (account == null) LoginScreen() else NakashNavHost()
            }
        }
    }
}
