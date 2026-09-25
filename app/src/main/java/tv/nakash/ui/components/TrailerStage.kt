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
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerStage(target: TrailerTarget?, modifier: Modifier = Modifier, onPlaying: (String?) -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    var firstKey by remember { mutableStateOf<String?>(null) }
    var lastBounds by remember { mutableStateOf(Rect.Zero) }
    var lastCorner by remember { mutableStateOf(10f) }
    if (target != null) { lastBounds = target.bounds; lastCorner = target.cornerDp }
    val alpha by animateFloatAsState(if (target != null && playing == target.key) 1f else 0f, tween(if (target != null) 400 else 120), label = "stage")

    LaunchedEffect(target?.key, ready) {
        playing = null; onPlaying(null)
        val v = view ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (target == null) v.evaluateJavascript("try{player.pauseVideo()}catch(e){}", null)
        else v.evaluateJavascript("try{player.loadVideoById({videoId:'${target.key}'});player.unMute();player.setVolume(55)}catch(e){}", null)
    }

    val b = lastBounds
    // The video is 16:9; to fill any card shape it is enlarged to cover the rectangle and clipped to the card's corners.
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
        AndroidView(
            modifier = Modifier.layout { m, c ->
                val w = coverW.roundToInt().coerceAtLeast(1); val h = coverH.roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - w) / 2, (c.maxHeight - h) / 2) }
            },
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Color.BLACK)
                    isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    settings.javaScriptEnabled = true; settings.mediaPlaybackRequiresUserGesture = false; settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(object {
                        @JavascriptInterface fun ready() { main.post { ready = true } }
                        @JavascriptInterface fun playing(id: String) { main.post { playing = id; onPlaying(id) } }
                        @JavascriptInterface fun stopped() { main.post { playing = null; onPlaying(null) } }
                    }, "Android")
                    loadDataWithBaseURL("https://github.com/dornakash94/NakashTV", html(), "text/html", "utf-8", null)
                    view = this
                }
            },
            onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy(); view = null },
        )
    }
}

private const val Zoom = 1.30f

private fun html() = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
/* The page is exactly the player's 16:9 box; the app enlarges and centres it (see TrailerStage). */
#w{position:absolute;left:0;top:0;width:100%;height:100%}#p{width:100%;height:100%}</style></head>
<body><div id="w"><div id="p"></div></div><script src="https://www.youtube.com/iframe_api"></script><script>
var player,shown=false,shownId='';
/* Captions: only a caption track uploaded in Hebrew by the channel. Never YouTube's automatic or auto-translated ones. */
function captions(){try{var t=player.getOption('captions','tracklist')||[];
  var he=t.filter(function(x){var c=(x.languageCode||'');var v=(x.vss_id||x.vssId||'');
    return (c=='he'||c=='iw')&&v.charAt(0)=='.'&&x.kind!='asr'&&!x.translationLanguage;})[0];
  if(he){player.setOption('captions','track',{languageCode:he.languageCode});}
  else{player.setOption('captions','track',{});player.unloadModule('captions');player.unloadModule('cc');}}catch(e){}}
/* Visible only while it really plays, from 3.5 s in and until 3 s before the end: YouTube draws its title, pause
   icon and end screen exactly at the start and the end. */
setInterval(function(){try{if(!player||!player.getPlayerState)return;
  var st=player.getPlayerState(),t=player.getCurrentTime(),d=player.getDuration(),id=(player.getVideoData()||{}).video_id||'';
  if(t<6)captions();
  var ok=st==1&&t>=3.6&&(d<=0||d-t>3);
  if(ok&&(!shown||shownId!=id)){shown=true;shownId=id;Android.playing(id);}
  if(!ok&&shown){shown=false;Android.stopped();}
  if(d>0&&d-t<=1.2&&st==1){player.pauseVideo();}}catch(e){}},200);
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',
  playerVars:{autoplay:1,cc_load_policy:0,controls:0,rel:0,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,modestbranding:1,origin:'https://github.com'},
  events:{onReady:function(e){e.target.unMute();e.target.setVolume(55);Android.ready();},onApiChange:captions,
    onError:function(e){shown=false;Android.stopped();}}});}
</script></body></html>""".trimIndent()
