package tv.nakash.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Lets the screen that shows a trailer drive it with the remote. */
class TrailerHandle { internal var view: WebView? = null
    fun togglePlay() { view?.evaluateJavascript("if(window.player&&player.getPlayerState){player.getPlayerState()==1?player.pauseVideo():player.playVideo()}", null) }
    fun seekBy(sec: Int) { view?.evaluateJavascript("if(window.player&&player.getCurrentTime){player.seekTo(player.getCurrentTime()+($sec),true)}", null) }
}

/**
 * A YouTube trailer in YouTube's official embedded player (IFrame API), the only allowed way to play it. Nothing is
 * drawn on top of the player. Never takes focus: the remote keeps driving the Compose screen around it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeTrailer(videoKey: String, muted: Boolean, modifier: Modifier = Modifier, handle: TrailerHandle? = null, onError: () -> Unit = {}, onEnded: () -> Unit = {}, onPlaying: () -> Unit = {}) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val bridge = remember(videoKey) { object {
        @JavascriptInterface fun error(code: Int) { main.post(onError) }
        @JavascriptInterface fun ended() { main.post(onEnded) }
        @JavascriptInterface fun playing() { main.post(onPlaying) }
    } }
    val html = remember(videoKey, muted) { """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
        <body><div id="p"></div><script src="https://www.youtube.com/iframe_api"></script><script>
        var player;
        /* Captions only from a real uploaded Hebrew track: never YouTube's auto-generated or auto-translated captions. */
        function hebrewCaptions(){try{var t=player.getOption('captions','tracklist')||[];var he=t.filter(function(x){var c=(x.languageCode||'');var v=(x.vss_id||x.vssId||'');return (c=='he'||c=='iw')&&v.charAt(0)=='.'&&x.kind!='asr'&&!x.translationLanguage;})[0];if(he){player.setOption('captions','track',{languageCode:he.languageCode});}else{player.setOption('captions','track',{});player.unloadModule('captions');player.unloadModule('cc');}}catch(e){}}
        function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$videoKey',
          playerVars:{autoplay:1,cc_load_policy:0,mute:${if (muted) 1 else 0},controls:0,rel:0,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,origin:'https://github.com'},
          events:{onReady:function(e){e.target.playVideo();},onApiChange:hebrewCaptions,onError:function(e){Android.error(e.data);},onStateChange:function(e){if(e.data==0)Android.ended();if(e.data==1)Android.playing();}}});}
        </script></body></html>""".trimIndent() }
    DisposableEffect(videoKey) { onDispose { handle?.view = null } }
    AndroidView(modifier = modifier, factory = { ctx ->
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            // If the system reclaims the web renderer (low memory on the TV), end the trailer instead of the whole app.
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    if (handle?.view === view) handle.view = null
                    main.post(onError); return true
                }
            }
            addJavascriptInterface(bridge, "Android")
            // YouTube requires an identifying referrer for embeds; the app's public repository page serves as its origin.
            loadDataWithBaseURL("https://github.com/dornakash94/NakashTV", html, "text/html", "utf-8", null)
            handle?.view = this
        }
    }, onRelease = { runCatching { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() } })
}
