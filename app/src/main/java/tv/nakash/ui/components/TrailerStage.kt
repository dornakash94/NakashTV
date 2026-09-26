package tv.nakash.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where the shared trailer should play: a YouTube video key and the rectangle (px, relative to the stage). */
data class TrailerTarget(val key: String, val bounds: Rect, val cornerDp: Float = 10f)

/**
 * One YouTube player for a whole browse screen. It sits BEHIND the content: the card or billboard it plays in turns
 * its own image transparent once [onPlaying] reports this key, so the video shows through while buttons and focus
 * rings stay drawn on top. Moving to another title swaps the video in the same player (no reload), which is what
 * keeps focus changes smooth on a TV.
 */
/**
 * The app's single YouTube player. A second WebView running the IFrame API never became ready after the first one
 * existed, so every screen borrows this one: it is moved between screens, never destroyed, and stays warm.
 */
@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
object TrailerPlayer {
    private var web: WebView? = null
    private var pageReady = false
    private var pending: String? = null
    val ready = kotlinx.coroutines.flow.MutableStateFlow(true)
    val playing = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Bumped whenever the WebView is thrown away, so stages take the new one. */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** The trailer that was showing when the player was released: not rebuilt for it (the screen under a starting movie). */
    internal var releasedKey: String? = null
    private var currentKey: String? = null
    private const val REFERER = "https://github.com/dornakash94/NakashTV"
    private fun embed(key: String) = "https://www.youtube.com/embed/$key?autoplay=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&fs=0&disablekb=1&modestbranding=1&cc_load_policy=0&start=4&enablejsapi=1"

    fun view(ctx: android.content.Context): WebView = web ?: WebView(ctx.applicationContext).apply {
        val main = Handler(Looper.getMainLooper())
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        settings.javaScriptEnabled = true; settings.mediaPlaybackRequiresUserGesture = false; settings.domStorageEnabled = true
        webChromeClient = WebChromeClient()
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url?.contains("youtube.com/embed/") != true) return
                pageReady = true
                view.evaluateJavascript(HOOK, null)
                pending?.let { k -> pending = null; load(k) }
            }
            // The web renderer is a separate process the TV may reclaim under memory pressure. Without this the
            // system takes the whole app down with it; instead drop the WebView and build a fresh one on demand.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                if (tv.nakash.BuildConfig.DEBUG) android.util.Log.w("NakashTrailer", "renderer gone crash=${detail?.didCrash()}")
                if (web === view) release()
                return true
            }
        }
        addJavascriptInterface(object {
            @JavascriptInterface fun playing(id: String) { main.post { playing.value = id } }
            @JavascriptInterface fun stopped() { main.post { playing.value = null } }
        }, "Android")
        web = this
    }

    /** First trailer loads the embed page; later ones swap the video inside the same page (no reload). */
    private val main = Handler(Looper.getMainLooper())
    private val releaseLater = Runnable { release() }
    private var parked = false

    /** A screen let go of the player: stop the page's scripts now, free the renderer if nobody takes it within 8 s. */
    fun park() {
        pause()
        val w = web ?: return
        runCatching { w.onPause(); w.pauseTimers() }
        parked = true
        main.removeCallbacks(releaseLater); main.postDelayed(releaseLater, 8_000)
    }
    private fun unpark(w: WebView) {
        main.removeCallbacks(releaseLater)
        if (parked) { parked = false; runCatching { w.onResume(); w.resumeTimers() } }
    }

    fun load(key: String) {
        val w = web ?: return
        unpark(w)
        currentKey = key; releasedKey = null
        playing.value = null
        if (!pageReady) {
            if (w.url == null || pending == null) w.loadUrl(embed(key), mapOf("Referer" to REFERER))
            pending = null
            return
        }
        w.evaluateJavascript("(function(){var p=document.getElementById('movie_player');if(p&&p.loadVideoById){window.__want='$key';p.loadVideoById({videoId:'$key',startSeconds:4});return 1}return 0})()") { r ->
            if (r != "1") { pageReady = false; w.loadUrl(embed(key), mapOf("Referer" to REFERER)) }
        }
    }
    /**
     * Frees the WebView and its renderer process (~100-150 MB, plus a video decoder). Called when real playback
     * starts, when the app leaves the screen and when memory runs low; the next trailer builds a new one.
     */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { Handler(Looper.getMainLooper()).post { release() }; return }
        main.removeCallbacks(releaseLater)
        val w = web ?: return
        if (tv.nakash.BuildConfig.DEBUG) android.util.Log.i("NakashTrailer", "release webview")
        if (parked) { parked = false; runCatching { w.resumeTimers() } } // timers are global to all WebViews
        releasedKey = currentKey; currentKey = null
        web = null; pageReady = false; pending = null; playing.value = null
        runCatching { (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.loadUrl("about:blank"); w.destroy() }
        generation.value++
    }
    fun pause() { playing.value = null; web?.evaluateJavascript("try{document.getElementById('movie_player').pauseVideo()}catch(e){}", null) }

    /**
     * Injected into YouTube's own embed page: hides the player's overlays (title bar, pause/play bezel, controls,
     * spinner, end screen) so nothing but the picture shows, and reports playback the moment the video really runs.
     */
    private val HOOK = """
(function(){
  if(window.__nk)return;window.__nk=1;
  var css=document.createElement('style');
  css.textContent='html,body{background:#000!important}'+
   '.ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,.ytp-bezel,.ytp-bezel-text-wrapper,'+
   '.ytp-pause-overlay,.ytp-large-play-button,.ytp-spinner,.ytp-cued-thumbnail-overlay,.ytp-ce-element,.ytp-endscreen-content,'+
   '.ytp-show-cards-title,.ytp-paid-content-overlay,.ytp-watermark,.ytp-impression-link,.ytp-youtube-button,.ytp-title,'+
   '.ytPlayerControlsContainerHost,.ytmCustomControlHost,.ytmWatchPlayerControlsHost,.ytmCuedOverlayHost,.ytmVideoCoverHost,.ytp-unmute,.player-controls-content,.player-controls-background-container,.ytwPlayerTopControlsHost,.ytwPlayerMiddleControlsHost,.ytwPlayerBottomControlsHost,.ytmVideoInfoHost,.ytwPlayerSeekOverlayHost,.ytwPlayerUserEduTooltipHost,.ytp-popup{display:none!important;opacity:0!important}'+
   '.ytp-caption-window-container .ytp-caption-window-rollup{display:none!important;opacity:0!important}'+
   '.html5-video-player{background:#000!important}';
  var shown='',lastT=-1;
  function caps(p){try{var t=p.getOption('captions','tracklist')||[];
    var he=t.filter(function(x){var c=(x.languageCode||'');var v=(x.vss_id||x.vssId||'');
      return (c=='he'||c=='iw')&&v.charAt(0)=='.'&&x.kind!='asr'&&!x.translationLanguage;})[0];
    if(he){p.setOption('captions','track',{languageCode:he.languageCode});}else{p.unloadModule('captions');p.unloadModule('cc');}}catch(e){}}
  var HIDE='.ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,.ytp-bezel,.ytp-bezel-text-wrapper,.ytp-pause-overlay,.ytp-large-play-button,.ytp-spinner,.ytp-cued-thumbnail-overlay,.ytp-ce-element,.ytp-endscreen-content,.ytp-show-cards-title,.ytp-paid-content-overlay,.ytp-watermark,.ytp-impression-link,.ytp-youtube-button,.ytp-title,.ytp-overflow-button,.ytp-share-button,.ytPlayerControlsContainerHost,.ytmCustomControlHost,.ytmWatchPlayerControlsHost,.ytmCuedOverlayHost,.ytmVideoCoverHost,.ytp-unmute,.player-controls-content,.player-controls-background-container,.ytwPlayerTopControlsHost,.ytwPlayerMiddleControlsHost,.ytwPlayerBottomControlsHost,.ytmVideoInfoHost,.ytwPlayerSeekOverlayHost,.ytwPlayerUserEduTooltipHost,.ytp-popup';
  function hide(){try{if(!document.getElementById('nkcss')&&document.head){css.id='nkcss';document.head.appendChild(css);}
    var n=document.querySelectorAll(HIDE);for(var i=0;i<n.length;i++){n[i].style.setProperty('display','none','important');n[i].style.setProperty('opacity','0','important');}}catch(e){}}
  new MutationObserver(hide).observe(document.documentElement,{childList:true,subtree:true});
  setInterval(function(){try{
    hide();
    var p=document.getElementById('movie_player'),v=document.querySelector('video');if(!p||!v)return;
    if(p.unMute){p.unMute();p.setVolume(55);}
    var id=(p.getVideoData&&p.getVideoData().video_id)||'';
    var t=v.currentTime,d=v.duration||0;
    if(t<8)caps(p);
    var moving=!v.paused&&t>lastT+0.05;lastT=t;
    var ok=moving&&t>0.4&&(!d||d-t>3);
    if(ok&&shown!=id){shown=id;Android.playing(id);}
    if(!ok&&shown&&(v.paused||(d&&d-t<=3))){shown='';Android.stopped();}
    if(d&&d-t<=1.2&&!v.paused){v.pause();}
  }catch(e){}},150);
})();
""".trimIndent()
}

@Composable
fun TrailerStage(target: TrailerTarget?, modifier: Modifier = Modifier, onPlaying: (String?) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val gen by TrailerPlayer.generation.collectAsState()
    // Built only once a trailer is actually wanted: the web renderer costs ~130 MB, so pages without trailers
    // (channels) and pages after a memory release don't pay for it.
    var wanted by remember(gen) { mutableStateOf(false) }
    if (target != null && target.key != TrailerPlayer.releasedKey) wanted = true
    val web = if (wanted) remember(gen) { TrailerPlayer.view(ctx) } else null
    val ready by TrailerPlayer.ready.collectAsState()
    val playing by TrailerPlayer.playing.collectAsState()
    var lastBounds by remember { mutableStateOf(Rect.Zero) }
    var lastCorner by remember { mutableStateOf(10f) }
    if (target != null) { lastBounds = target.bounds; lastCorner = target.cornerDp }
    val mine = target != null && playing == target.key
    val alpha by animateFloatAsState(if (mine) 1f else 0f, tween(if (target != null) 400 else 120), label = "stage")
    LaunchedEffect(mine, playing) { onPlaying(if (mine) playing else null) }
    LaunchedEffect(target?.key, ready, gen) {
        if (!ready || web == null) return@LaunchedEffect
        if (target == null) TrailerPlayer.pause() else TrailerPlayer.load(target.key)
    }
    DisposableEffect(Unit) { onDispose { TrailerPlayer.park() } }

    val b = lastBounds
    var coverW: Float; var coverH: Float
    if (b.width / b.height.coerceAtLeast(1f) > 16f / 9f) { coverW = b.width; coverH = b.width * 9f / 16f } else { coverH = b.height; coverW = b.height * 16f / 9f }
    // Enlarged from the centre so YouTube's title bar and control strip fall outside the clip.
    coverW *= Zoom; coverH *= Zoom
    Box(modifier.fillMaxSize().layout { m, c ->
        // Absolute placement (layouts mirror in RTL; the target rectangle is in screen coordinates).
        val w = b.width.roundToInt().coerceAtLeast(1); val h = b.height.roundToInt().coerceAtLeast(1)
        val p = m.measure(Constraints.fixed(w, h))
        layout(c.maxWidth, c.maxHeight) { p.place(b.left.roundToInt(), b.top.roundToInt()) }
    }.graphicsLayer { this.alpha = alpha; clip = true; shape = androidx.compose.foundation.shape.RoundedCornerShape(lastCorner.dp) }) {
        if (web != null) androidx.compose.runtime.key(gen) { AndroidView(
            modifier = Modifier.layout { m, c ->
                val w = coverW.roundToInt().coerceAtLeast(1); val h = coverH.roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - w) / 2, (c.maxHeight - h) / 2) }
            },
            // Borrow the one player: each screen has its own frame and moves the player into it. On leaving, a screen
            // lets go only if the player is still in its own frame (the next screen may already have taken it).
            factory = { c -> android.widget.FrameLayout(c).apply { (web.parent as? ViewGroup)?.removeView(web); addView(web) } },
            onRelease = { frame -> if (web.parent === frame) frame.removeView(web) },
        ) }
    }
}

private const val Zoom = 1.24f

