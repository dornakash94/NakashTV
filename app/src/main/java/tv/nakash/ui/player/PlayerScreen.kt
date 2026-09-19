package tv.nakash.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import tv.nakash.data.local.EpisodeEntity
import tv.nakash.ui.library.Action
import androidx.tv.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.EpgEntity
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.data.repo.EpgRepository
import tv.nakash.player.PlayRequest
import tv.nakash.player.PlayerController
import tv.nakash.player.ThumbnailGenerator
import tv.nakash.ui.components.ChannelLogo
import tv.nakash.ui.components.ProgressBar
import tv.nakash.ui.theme.NakashColors
import tv.nakash.util.fmtDuration
import tv.nakash.util.fmtTime
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val controller: PlayerController, private val catalog: CatalogRepository, private val epg: EpgRepository, val thumbs: ThumbnailGenerator, val controls:tv.nakash.data.local.PlaybackPreferences,
    private val userRepo: tv.nakash.data.repo.UserRepository,
) : ViewModel() {
    val nowNext = MutableStateFlow<Pair<EpgEntity?, EpgEntity?>>(null to null)
    val zapList = controller.zapChannels
    val miniRows = MutableStateFlow<List<Triple<ChannelEntity, EpgEntity?, EpgEntity?>>>(emptyList())

    fun onChannel(ch: ChannelEntity) = viewModelScope.launch {
        nowNext.value = epg.nowAndNext(ch.epgChannelId)
        if (zapList.value.isEmpty()) run { zapList.value = catalog.channels().first() }
    }
    /** Up/Down zap within the current list (category the user came from, else all). */
    fun zap(current: ChannelEntity, dir: Int) {
        val list = zapList.value; if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val next = list[(i + dir + list.size) % list.size]
        controller.play(PlayRequest.Live(next)); onChannel(next)
    }
    fun loadMini(current: ChannelEntity) = viewModelScope.launch {
        val list = zapList.value; if (list.isEmpty()) return@launch
        val i = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        miniRows.value = (0..2).map { k -> val c = list[(i + k) % list.size]; val (n, x) = epg.nowAndNext(c.epgChannelId); Triple(c, n, x) }
    }
    val nextEpisode = MutableStateFlow<EpisodeEntity?>(null)
    fun loadNext(req: PlayRequest.Episode) = viewModelScope.launch { nextEpisode.value=catalog.nextEpisode(req.seriesId,req.season,req.number) }
    fun playNext(e: EpisodeEntity) { controller.play(PlayRequest.Episode(e.id,e.seriesId,e.season,e.number,e.title,e.containerExt)) }
    fun toggleFavorite(ch: ChannelEntity) = viewModelScope.launch { val on=userRepo.toggleFavorite("channel", ch.id.toString()); controller.toast(if(on) "${ch.displayName} נוסף למועדפים" else "${ch.displayName} הוסר מהמועדפים") }
    fun isFavorite(channelId: Int?): kotlinx.coroutines.flow.Flow<Boolean> = if(channelId==null) kotlinx.coroutines.flow.flowOf(false) else userRepo.isFavorite("channel", channelId.toString())
    fun jumpToNumber(n: Int) { zapList.value.firstOrNull { it.number == n }?.let { controller.play(PlayRequest.Live(it)); onChannel(it) } }
}

/**
 * Full-screen player. Overlay auto-hides after 4s; any key brings it back.
 * Live: ▲▼ zap · OK mini-EPG · ◀▶ start-over/archive · digits channel number · menu source
 * VOD:  OK play/pause · ◀▶ seek 10s with thumbnail strip · ▼ audio/subs · ▲ episodes
 */
@Composable
fun PlayerScreen(nav: NavHostController, vm: PlayerViewModel = hiltViewModel()) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {PlayerScreenContent(nav,vm)}
}
@Composable
private fun PlayerScreenContent(nav:NavHostController,vm:PlayerViewModel) {
    val st by vm.controller.state.collectAsState()
    val controls by vm.controls.state.collectAsState()
    var videoView by remember {mutableStateOf<PlayerView?>(null)}
    val nowNext by vm.nowNext.collectAsState()
    val mini by vm.miniRows.collectAsState()
    val quietEntry=remember {nav.currentBackStackEntry?.savedStateHandle?.remove<Boolean>("cinematicEntry") == true}
    val enteredAt=remember {System.currentTimeMillis()}
    var overlay by remember { mutableStateOf(!quietEntry) }
    var manuallyHidden by remember {mutableStateOf(false)}
    var showMini by remember { mutableStateOf(false) }
    var miniIndex by remember { mutableIntStateOf(0) }
    var showTracks by remember { mutableStateOf(false) }
    var showAspect by remember {mutableStateOf(false)}
    val subtitlesFocus=remember {FocusRequester()}
    val aspectFocus=remember {FocusRequester()}
    var autoNext by remember { mutableStateOf(true) }
    var consecutiveEpisodes by remember { mutableIntStateOf(0) }
    val ended by vm.controller.ended.collectAsState()
    val nextEpisode by vm.nextEpisode.collectAsState()
    var nearEnd by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var stillWatching by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    tv.nakash.ui.components.PlaybackFrameCache(videoView,vm.controller,vm.thumbs,enabled=scrubMs==null)
    var lastKey by remember { mutableStateOf(enteredAt) }
    val focus = remember { FocusRequester() }
    val req = st.request
    val live = req as? PlayRequest.Live
    val archive=req as? PlayRequest.Archive
    var playerFocused by remember { mutableStateOf(true) }
    val uiScope=androidx.compose.runtime.rememberCoroutineScope()
    fun focusPlayerMenu() {
        overlay=true;manuallyHidden=false
        uiScope.launch {delay(80);runCatching {subtitlesFocus.requestFocus()}}
    }
    fun returnToLive() { archive?.let { vm.controller.play(PlayRequest.Live(it.channel));scrubMs=null;showMini=false;focus.requestFocus() } }
    fun back() {
        when { showMini->showMini=false;showTracks->{showTracks=false;focus.requestFocus()};showAspect->{showAspect=false;focus.requestFocus()}
            archive!=null->returnToLive()
            else->{
                if(req is PlayRequest.Movie || req is PlayRequest.Episode)
                    nav.previousBackStackEntry?.savedStateHandle?.set("returnToDetails",true)
                vm.controller.stop();nav.popBackStack()
            } }
    }

    BackHandler { back() }
    DisposableEffect(Unit) { onDispose { vm.thumbs.stop();vm.controller.stop() } }
    LaunchedEffect(req) {
        if(req is PlayRequest.Episode) vm.loadNext(req)
        autoNext=true
        nearEnd=false
        while(true) {
            position=vm.controller.player.currentPosition+(archive?.offsetSeconds ?: 0)*1000
            val duration=vm.controller.player.duration
            nearEnd=req is PlayRequest.Episode && duration>0 && duration-position in 0..15_000
            delay(500)
        }
    }
    val thumbKey=when(val item=req) {is PlayRequest.Movie->"movie:${item.id}";is PlayRequest.Episode->"episode:${item.episodeId}";else->""}
    LaunchedEffect(thumbKey,(scrubMs ?: position)/10_000,st.durationMs,st.isBuffering) {
        val uri=vm.controller.player.currentMediaItem?.localConfiguration?.uri?.toString()
        if(uri!=null && !st.isBuffering) vm.thumbs.request(thumbKey,uri,(scrubMs ?: position)/1000,st.durationMs/1000)
    }
    LaunchedEffect(ended) {
        if(ended>0 && req is PlayRequest.Episode && autoNext) {
            val next=vm.nextEpisode.value
            if(next!=null) {
                consecutiveEpisodes++
                if(consecutiveEpisodes>=3) stillWatching=true else vm.playNext(next)
            }
        }
    }
    LaunchedEffect(lastKey) { delay(4*60*60*1000L);vm.controller.player.pause();stillWatching=true }
    LaunchedEffect(live?.channel?.id) { live?.let { vm.onChannel(it.channel) } }
    LaunchedEffect(lastKey, showMini, scrubMs,showTracks,showAspect) { if(manuallyHidden) {overlay=false;return@LaunchedEffect}; if(quietEntry && lastKey==enteredAt && !showMini && scrubMs==null) return@LaunchedEffect; overlay = true; delay(4_000); if (!showMini && !showTracks && !showAspect && playerFocused && scrubMs == null && st.error == null) overlay = false }
    LaunchedEffect(digits) { if (digits.isNotEmpty()) { delay(1_200); digits.toIntOrNull()?.let(vm::jumpToNumber); digits = "" } }
    LaunchedEffect(scrubMs) { if (scrubMs != null) { delay(1_500); scrubMs?.let(vm.controller::seekTo); scrubMs = null } }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if(controls.action(e.nativeKeyEvent.keyCode)!=tv.nakash.data.local.RemoteAction.CONTROLS) manuallyHidden=false
                lastKey = System.currentTimeMillis()
                val k = e.key
                if(k==Key.Back) return@onPreviewKeyEvent false
                if(k==Key.Escape) { back();return@onPreviewKeyEvent true }
                if(!playerFocused) return@onPreviewKeyEvent false
                if(archive!=null && k==Key.DirectionDown) { returnToLive();return@onPreviewKeyEvent true }
                if(showTracks || showAspect || stillWatching) return@onPreviewKeyEvent false
                if(controls.isBound(e.nativeKeyEvent.keyCode)) {
                    when(controls.action(e.nativeKeyEvent.keyCode)) {
                        tv.nakash.data.local.RemoteAction.PLAY_PAUSE -> vm.controller.togglePlayPause()
                        tv.nakash.data.local.RemoteAction.TRACKS -> showTracks=true
                        tv.nakash.data.local.RemoteAction.ASPECT -> showAspect=true
                        tv.nakash.data.local.RemoteAction.FAVORITE -> live?.let { vm.toggleFavorite(it.channel) }
                        tv.nakash.data.local.RemoteAction.SOURCE -> if(live!=null) vm.controller.switchSource((st.sourceIndex + 1) % st.sources.size.coerceAtLeast(1))
                        tv.nakash.data.local.RemoteAction.MINI_EPG -> live?.let { miniIndex=0;vm.loadMini(it.channel);showMini=true }
                        tv.nakash.data.local.RemoteAction.START_OVER -> live?.let { l -> nowNext.first?.let { p -> if (tv.nakash.domain.PlaybackPolicy.archiveAvailable(p.start,p.end,p.isFiller,l.channel.archiveDays,System.currentTimeMillis()/1000)) vm.controller.play(PlayRequest.Archive(l.channel, p)) } }
                        tv.nakash.data.local.RemoteAction.MENU -> focusPlayerMenu()
                        tv.nakash.data.local.RemoteAction.CONTROLS -> {manuallyHidden=overlay;overlay=!overlay}
                        tv.nakash.data.local.RemoteAction.NONE -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if(live == null && k==Key.DirectionDown) {focusPlayerMenu();return@onPreviewKeyEvent true}
                if(nearEnd && k==Key.DirectionDown) { autoNext=false;return@onPreviewKeyEvent true }
                if(nearEnd && k==Key.DirectionUp && nextEpisode!=null) { vm.playNext(nextEpisode!!);return@onPreviewKeyEvent true }
                if(st.error!=null && (k==Key.Enter || k==Key.DirectionCenter)) { req?.let(vm.controller::play);return@onPreviewKeyEvent true }
                if (e.nativeKeyEvent.keyCode in 7..16 && live != null) { digits = (digits + (e.nativeKeyEvent.keyCode - 7)).takeLast(3); return@onPreviewKeyEvent true }
                when {
                    
                    live != null && showMini -> when (k) {
                        Key.DirectionDown -> { miniIndex=(miniIndex+1).coerceAtMost(mini.lastIndex.coerceAtLeast(0));true }
                        Key.DirectionUp -> { miniIndex=(miniIndex-1).coerceAtLeast(0);true }
                        Key.DirectionCenter, Key.Enter -> { mini.getOrNull(miniIndex)?.first?.let { vm.controller.play(PlayRequest.Live(it)) };showMini = false; true }
                        else -> false
                    }
                    live != null -> when (k) {
                        Key.DirectionUp -> { vm.zap(live.channel, +1); true }
                        Key.DirectionDown -> { vm.zap(live.channel, -1); true }
                        Key.DirectionCenter, Key.Enter -> { if(controls.liveOkPauses) vm.controller.togglePlayPause() else {miniIndex=0;vm.loadMini(live.channel); showMini = true}; true }
                        Key.DirectionLeft -> { nowNext.first?.let { p -> if (tv.nakash.domain.PlaybackPolicy.archiveAvailable(p.start,p.end,p.isFiller,live.channel.archiveDays,System.currentTimeMillis()/1000)) vm.controller.play(PlayRequest.Archive(live.channel, p)) }; true }
                        Key.DirectionRight -> { vm.toggleFavorite(live.channel); true }
                        Key.Menu -> { vm.controller.switchSource((st.sourceIndex + 1) % st.sources.size.coerceAtLeast(1)); true }
                        else -> false
                    }
                    else -> when (k) { // VOD
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { if (scrubMs != null) { vm.controller.seekTo(scrubMs!!); scrubMs = null } else vm.controller.togglePlayPause(); true }
                        Key.DirectionLeft -> { scrubMs = ((scrubMs ?: position) - (if(archive!=null) 60 else controls.seekSeconds)*1000L).coerceAtLeast(0); true }
                        Key.DirectionRight -> { scrubMs = ((scrubMs ?: position) + (if(archive!=null) 60 else controls.seekSeconds)*1000L).coerceAtMost(if(archive!=null) ((minOf(archive.program.end,System.currentTimeMillis()/1000)-archive.program.start)*1000).coerceAtLeast(0) else vm.controller.player.duration.coerceAtLeast(0)); true }
                        else -> false
                    }
                }
            }.focusRequester(focus).onFocusChanged { playerFocused=it.isFocused }.focusable(),
    ) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; keepScreenOn = true; isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS; player = vm.controller.player;videoView=this } }, modifier = Modifier.fillMaxSize(),update={it.resizeMode=controls.aspectMode},onRelease={it.player=null})

        if(archive!=null) Action("● חזרה לשידור החי · ▼",::returnToLive,Modifier.align(Alignment.TopEnd).padding(24.dp))
        if(st.isBuffering) Text("טוענים שידור…", modifier=Modifier.align(Alignment.Center).background(NakashColors.Bg).padding(20.dp))
        if(nearEnd && nextEpisode!=null) Text("הפרק הבא: ${nextEpisode!!.title} · ▲ לצפייה", color=NakashColors.Accent, modifier=Modifier.align(Alignment.TopCenter).background(NakashColors.Bg).padding(20.dp))
        if(stillWatching) Dialog(onDismissRequest={stillWatching=false}) { Surface { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("עדיין צופים?")
            Action("המשך צפייה",{stillWatching=false;consecutiveEpisodes=0;lastKey=System.currentTimeMillis();if(vm.controller.player.playbackState==androidx.media3.common.Player.STATE_ENDED && nextEpisode!=null) vm.playNext(nextEpisode!!) else vm.controller.player.play()})
            Action("סיום צפייה",{stillWatching=false;vm.controller.stop();nav.popBackStack()})
        } } }
        AnimatedVisibility(overlay || st.error != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(.75f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(360.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.92f)))))

                if (live != null) LiveOverlay(live.channel, nowNext.first, nowNext.second, st.sources.getOrNull(st.sourceIndex)?.kind, false)
                else VodOverlay(st.request, scrubMs ?: position, if(archive!=null) ((minOf(archive.program.end,System.currentTimeMillis()/1000)-archive.program.start)*1000).coerceAtLeast(0) else vm.controller.player.duration.coerceAtLeast(0), scrubMs != null, st.isPlaying, vm)

                val isFav by vm.isFavorite(live?.channel?.id).collectAsState(initial=false)
                PlayerBar(Modifier.align(Alignment.BottomEnd).padding(horizontal=40.dp,vertical=18.dp), up={focus.requestFocus()}, buttons=buildList {
                    if(live!=null) {
                        add(BarButton(if(isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if(isFav) "במועדפים" else "הוסף למועדפים", tint=if(isFav) NakashColors.Live else null) { vm.toggleFavorite(live.channel) })
                        if(st.sources.size>1) add(BarButton(Icons.Outlined.Layers, "מקור: "+(st.sources.getOrNull(st.sourceIndex)?.kind?.let { sourceLabel(it) } ?: "ראשי")) { vm.controller.switchSource((st.sourceIndex + 1) % st.sources.size) })
                        if(live.channel.archiveDays>0) add(BarButton(Icons.Outlined.Replay, "מההתחלה") { nowNext.first?.let { p -> if (tv.nakash.domain.PlaybackPolicy.archiveAvailable(p.start,p.end,p.isFiller,live.channel.archiveDays,System.currentTimeMillis()/1000)) vm.controller.play(PlayRequest.Archive(live.channel, p)) } })
                    } else {
                        add(BarButton(if(st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(st.isPlaying) "השהה" else "נגן") { vm.controller.togglePlayPause() })
                        if(req is PlayRequest.Episode && nextEpisode!=null) add(BarButton(Icons.Filled.SkipNext, "הפרק הבא") { vm.playNext(nextEpisode!!) })
                    }
                    add(BarButton(Icons.Outlined.Subtitles, "כתוביות", focus=subtitlesFocus) { showTracks=true })
                    add(BarButton(Icons.Outlined.AspectRatio, "יחס תמונה", focus=aspectFocus) { showAspect=true })
                })
                if (showMini) MiniEpg(mini, mini.getOrNull(miniIndex)?.first?.id)
                st.toast?.let { Toast(it) { vm.controller.clearToast() } }
                if (digits.isNotEmpty()) Text(digits, style = MaterialTheme.typography.displayLarge, modifier = Modifier.align(Alignment.TopEnd).padding(60.dp).background(Color.Black.copy(.6f), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 16.dp))
                st.error?.let { Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text(it, style = MaterialTheme.typography.headlineMedium); Text(if(archive!=null) "חזרה או ▼ לשידור החי · OK לניסיון נוסף" else "OK לניסיון נוסף · חזרה ליציאה", color = NakashColors.Muted) } }
            }
        }
        if(showTracks) SubtitlePopover(vm.controller) {showTracks=false;lastKey=System.currentTimeMillis();focus.requestFocus()}
        if(showAspect) PlayerPopover("יחס תמונה",0,{showAspect=false;lastKey=System.currentTimeMillis();focus.requestFocus()}) {
            listOf(0 to "מקורי · ללא חיתוך",4 to "מילוי · עם חיתוך",3 to "מתיחה למסך").forEachIndexed {index,(mode,label) ->
                PopoverOption(label,controls.aspectMode==mode,index==0) {vm.controls.setAspect(mode);showAspect=false;lastKey=System.currentTimeMillis();focus.requestFocus()}
            }
        }

    }
}

@Composable
private fun LiveOverlay(ch: ChannelEntity, now: EpgEntity?, next: EpgEntity?, sourceKind: String?, archive: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.TopStart).padding(start = 80.dp, top = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ChannelLogo(ch, 84)
            Column {
                Text(ch.displayName, style = MaterialTheme.typography.headlineMedium)
                Text("ערוץ ${ch.number} · ${if (archive) "מהארכיון" else "● שידור חי"}${sourceKind?.takeIf { it != "PRIMARY" }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.titleLarge, color = if (archive) NakashColors.Accent else NakashColors.Muted)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 40.dp, vertical = 76.dp).fillMaxWidth()) {
            now?.let { p ->
                Text(p.title, style = MaterialTheme.typography.headlineMedium)
                Text(p.description, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD3D4D8), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                ProgressBar(((System.currentTimeMillis() / 1000 - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f), Color.White, 6)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(p.start) + if (ch.archiveDays > 0) "  ◀ מהתחלה" else "", color = NakashColors.Muted)
                    next?.let { Text("הבא: ${it.title} · ${fmtTime(it.start)}", color = NakashColors.Muted, maxLines = 1) }
                    Text(fmtTime(p.end), color = NakashColors.Muted)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun VodOverlay(req: PlayRequest?, posMs: Long, durMs: Long, scrubbing: Boolean, playing: Boolean, vm: PlayerViewModel) {
    val title = when (req) { is PlayRequest.Movie -> req.title; is PlayRequest.Episode -> req.title; is PlayRequest.Archive -> req.program.title; else -> "" }
    val sub = when (req) { is PlayRequest.Episode -> "עונה ${req.season} פרק ${req.number}"; is PlayRequest.Archive -> "${req.channel.displayName} · צפייה חוזרת · ${fmtTime(req.program.start+posMs/1000)}"; else -> "" }
    val key = when (req) { is PlayRequest.Movie -> "movie:${req.id}"; is PlayRequest.Episode -> "episode:${req.episodeId}"; else -> "" }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopStart).padding(start = 80.dp, top = 60.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(sub + if (!playing) " · מושהה" else "", style = MaterialTheme.typography.titleLarge, color = NakashColors.Muted)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 40.dp, vertical = 76.dp).fillMaxWidth()) {
            if (scrubbing && req !is PlayRequest.Archive) ThumbStrip(vm.thumbs, key, posMs / 1000)
            if (scrubbing && req is PlayRequest.Archive) Text("צפייה מ־${fmtTime(req.program.start+posMs/1000)}",color=Color.White,style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            ProgressBar(if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f, NakashColors.Live, 6)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtDuration(posMs / 1000), color = NakashColors.Muted)
                Text("-" + fmtDuration((durMs - posMs).coerceAtLeast(0) / 1000), color = NakashColors.Muted)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** Five frames around the scrub target: the center one large. Shows only frames that exist on disk so far. */
@Composable
private fun ThumbStrip(thumbs: ThumbnailGenerator, key: String, posSec: Long) {
    val revision by thumbs.revision.collectAsState()
    val unavailable by thumbs.unavailable.collectAsState()
    val frames=remember(key,posSec,revision) { (-2..2).map {i -> val second=(posSec+i*ThumbnailGenerator.STEP_SEC).coerceAtLeast(0); Triple(i,second,thumbs.existing(key,second))} }
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
        for ((i,t,f) in frames) { // LTR: earlier frames on the left
            if(f==null && i!=0) continue
            val center = i == 0
            Box(Modifier.size(if (center) 220.dp else 170.dp, if (center) 124.dp else 96.dp).clip(RoundedCornerShape(8.dp)).background(NakashColors.S2), contentAlignment = Alignment.BottomCenter) {
                if (f != null) AsyncImage(f, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Text(if(unavailable==key) "אין תמונה זמינה לקטע הזה" else "טוען תמונה מהווידאו…",modifier=Modifier.align(Alignment.Center),style=MaterialTheme.typography.labelMedium,color=NakashColors.Muted)
                Text(fmtDuration(t), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MiniEpg(rows: List<Triple<ChannelEntity, EpgEntity?, EpgEntity?>>, currentId: Int?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 80.dp).padding(bottom = 130.dp).align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (c, now, next) ->
            Row(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE614151A)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChannelLogo(c, 60)
                Column(Modifier.width(160.dp)) { Text(c.displayName, style = MaterialTheme.typography.titleLarge, color = if (c.id == currentId) NakashColors.Accent else NakashColors.Text); Text("ערוץ ${c.number}", style = MaterialTheme.typography.labelMedium, color = NakashColors.Muted) }
                Text(now?.let { "${it.title}  ${fmtTime(it.start)}–${fmtTime(it.end)}" } ?: "ללא לוח", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                next?.let { Text("הבא: ${it.title}", color = NakashColors.Muted, maxLines = 1, modifier = Modifier.width(220.dp)) }
            }
        }
    }
}


@Composable
private fun Toast(msg: String, onDone: () -> Unit) {
    LaunchedEffect(msg) { delay(2_500); onDone() }
    Row(Modifier.padding(60.dp).background(Color(0xF214151A), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(NakashColors.Ok)); Text(msg, style = MaterialTheme.typography.titleLarge)
    }
}

private fun sourceLabel(kind:String)=when(kind) {"BACKUP"->"גיבוי";"ACCESSIBLE"->"מונגש";"RUSSIAN"->"רוסית";else->"ראשי"}

data class BarButton(val icon:ImageVector,val label:String,val focus:FocusRequester?=null,val tint:Color?=null,val click:()->Unit)

/**
 * Netflix-style control bar: round, transparent icon buttons; the focused one becomes a white disc with a black
 * icon and shows its label above. No boxes, no permanent text. ▲ leaves the bar back to the video surface.
 */
@Composable
private fun PlayerBar(modifier:Modifier,up:()->Unit,buttons:List<BarButton>) {
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Bottom) {
        buttons.forEach {b ->
            var focused by remember {mutableStateOf(false)}
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                AnimatedVisibility(focused,enter=fadeIn(),exit=fadeOut()) {
                    Text(b.label,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=Color.White,
                        modifier=Modifier.padding(bottom=8.dp).background(Color.Black.copy(.6f),RoundedCornerShape(6.dp)).padding(horizontal=10.dp,vertical=4.dp))
                }
                Surface(onClick=b.click,modifier=(b.focus?.let {Modifier.focusRequester(it)} ?: Modifier).size(48.dp).onFocusChanged {focused=it.isFocused}
                    .onPreviewKeyEvent {event -> if(event.type==KeyEventType.KeyDown && event.key==Key.DirectionUp) {up();true} else false},
                    shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.1f),
                    colors=ClickableSurfaceDefaults.colors(containerColor=Color.Transparent,focusedContainerColor=Color.White,contentColor=b.tint ?: Color.White,focusedContentColor=b.tint ?: Color.Black)) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Icon(b.icon,contentDescription=b.label,modifier=Modifier.size(26.dp))}
                }
            }
        }
    }
}

@Composable
private fun PlayerPopover(title:String,offsetDp:Int,close:()->Unit,content:@Composable ()->Unit) {
    val density=LocalDensity.current
    Popup(alignment=Alignment.BottomEnd,offset=with(density) {IntOffset(-(40+offsetDp).dp.roundToPx(),-78.dp.roundToPx())},onDismissRequest=close,properties=PopupProperties(focusable=true)) {
        Column(Modifier.width(280.dp).heightIn(max=320.dp).background(Color(0xF2141519),RoundedCornerShape(14.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.labelLarge.copy(fontSize=13.sp),color=NakashColors.Muted,modifier=Modifier.padding(horizontal=10.dp,vertical=6.dp))
            content()
        }
    }
}

@Composable
private fun PopoverOption(label:String,selected:Boolean,initial:Boolean=false,click:()->Unit) {
    val focus=remember {FocusRequester()}
    LaunchedEffect(Unit) {if(initial) {delay(60);runCatching {focus.requestFocus()}}}
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().heightIn(min=34.dp).focusRequester(focus),
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
        colors=ClickableSurfaceDefaults.colors(containerColor=Color.Transparent,focusedContainerColor=Color.White.copy(.17f),contentColor=Color.White,focusedContentColor=Color.White)) {
        Text((if(selected) "✓  " else "")+label,modifier=Modifier.padding(horizontal=10.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp))
    }
}

@Composable
private fun SubtitlePopover(controller:PlayerController,close:()->Unit) {
    val player=controller.player
    val tracks=player.currentTracks.groups.filter {it.type==C.TRACK_TYPE_TEXT}.flatMap {group -> (0 until group.length).filter {group.isTrackSupported(it)}.map {group to it}}
    val disabled=player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || tracks.none {(group,index)->group.isTrackSelected(index)}
    PlayerPopover("כתוביות",132,close) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            item {PopoverOption("ללא כתוביות",disabled,true) {player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build();close()}}
            items(tracks) {(group,index) ->
                val format=group.getTrackFormat(index)
                val language=format.language?.let {java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.forLanguageTag("he"))}
                PopoverOption(format.label ?: language ?: "רצועה ${index+1}",group.isTrackSelected(index) && !disabled) {
                    player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index)).build();close()
                }
            }
            if(tracks.isEmpty()) item {Text("אין רצועת כתוביות נפרדת. כתוביות שמוטמעות בתמונה אינן ניתנות לכיבוי.",color=NakashColors.Muted,style=MaterialTheme.typography.bodySmall.copy(fontSize=12.sp),modifier=Modifier.padding(6.dp))}
        }
    }
}
