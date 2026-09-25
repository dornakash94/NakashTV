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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.History
import tv.nakash.domain.PlaybackPolicy
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
    /** The channel's recent programs: catch-up timeline, scrub snap points and the in-channel "earlier shows" list. */
    val timeline = MutableStateFlow<List<EpgEntity>>(emptyList())
    private var timelineFor: Int? = null
    private var timelineLoadedAt = 0L
    fun loadTimeline(ch: ChannelEntity) = viewModelScope.launch {
        val fresh = System.currentTimeMillis() - timelineLoadedAt < 5 * 60_000
        if (timelineFor == ch.id && fresh && timeline.value.isNotEmpty()) return@launch
        timelineFor = ch.id; timelineLoadedAt = System.currentTimeMillis()
        val now = System.currentTimeMillis() / 1000
        timeline.value = epg.programs(ch.epgChannelId, now - ch.archiveDays.coerceIn(1, 3) * 86400L, now + 6 * 3600).filter { !it.isFiller }
    }
    fun programAt(epoch: Long): EpgEntity? = timeline.value.lastOrNull { it.start <= epoch && it.end > epoch }
    fun snap(base: Long, target: Long): Long = PlaybackPolicy.snapToProgramStart(base, target, timeline.value.map { it.start })
    /** Play the channel from an absolute moment. Close to the broadcast that simply means live. */
    fun playAt(ch: ChannelEntity, epoch: Long) {
        val now = System.currentTimeMillis() / 1000
        if (PlaybackPolicy.nearLive(epoch, now)) { if (controller.state.value.request !is PlayRequest.Live) controller.play(PlayRequest.Live(ch)); return }
        val p = programAt(epoch) ?: EpgEntity("${ch.epgChannelId}:$epoch", ch.epgChannelId ?: "", epoch, epoch + 3600, ch.displayName, "", false)
        controller.play(PlayRequest.Archive(ch, p, epoch - p.start))
    }
    /**
     * What already aired on this channel and is still in its archive, newest first. The provider's guide sometimes
     * lists one show twice with overlapping slots; those merge into one row that starts at the earlier time, so
     * "from the beginning" does not miss the opening.
     */
    fun mergedTimeline(): List<EpgEntity> {
        val merged = mutableListOf<EpgEntity>()
        timeline.value.sortedBy { it.start }.forEach { p ->
            val last = merged.lastOrNull()
            when {
                last != null && last.title == p.title && p.start < last.end -> merged[merged.lastIndex] = last.copy(end = maxOf(last.end, p.end))
                // Another show listed inside the previous slot: it starts where the previous one ends (or is dropped).
                last != null && p.start < last.end -> if (p.end > last.end) merged += p.copy(start = last.end)
                else -> merged += p
            }
        }
        return merged
    }
    fun catchupRows(ch: ChannelEntity, now: Long): List<EpgEntity> =
        mergedTimeline().filter { PlaybackPolicy.archiveAvailable(it.start, it.end, it.isFiller, ch.archiveDays, now) }.sortedByDescending { it.start }

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
    // Channel catch-up (live rewind + archive): an absolute target time while the user moves through the timeline.
    var scrubEpoch by remember { mutableStateOf<Long?>(null) }
    var scrubStreak by remember { mutableIntStateOf(0) }
    var lastScrubAt by remember { mutableLongStateOf(0L) }
    var scrubPauseUntil by remember { mutableLongStateOf(0L) }
    var showPrograms by remember { mutableStateOf(false) }
    var programsIndex by remember { mutableIntStateOf(0) }
    tv.nakash.ui.components.PlaybackFrameCache(videoView,vm.controller,vm.thumbs,enabled=scrubMs==null)
    var lastKey by remember { mutableStateOf(enteredAt) }
    val focus = remember { FocusRequester() }
    val req = st.request
    val live = req as? PlayRequest.Live
    val archive=req as? PlayRequest.Archive
    val channel=live?.channel ?: archive?.channel
    val timeline by vm.timeline.collectAsState()
    var playerFocused by remember { mutableStateOf(true) }
    val uiScope=androidx.compose.runtime.rememberCoroutineScope()
    fun focusPlayerMenu() {
        overlay=true;manuallyHidden=false
        uiScope.launch {delay(80);runCatching {subtitlesFocus.requestFocus()}}
    }
    fun returnToLive() { archive?.let { vm.controller.play(PlayRequest.Live(it.channel));scrubMs=null;scrubEpoch=null;showPrograms=false;showMini=false;focus.requestFocus() } }
    fun nowSec() = System.currentTimeMillis() / 1000
    /** Where playback is on the broadcast clock: live = now; catch-up = chunk start + position. */
    fun wallClock(): Long = if (archive != null) st.archiveStart + position / 1000 else nowSec()
    fun noArchive() = vm.controller.toast("בערוץ הזה אין צפייה חוזרת")
    /** ◀▶ through the channel's timeline: taps move a minute, holding accelerates, and it pauses on each show start. */
    fun moveScrub(dir: Int) {
        val ch = channel ?: return
        if (ch.archiveDays <= 0) { noArchive(); return }
        val t = System.currentTimeMillis()
        if (t < scrubPauseUntil || t - lastScrubAt < 110) return
        scrubStreak = if (t - lastScrubAt < 650) scrubStreak + 1 else 0
        lastScrubAt = t
        val now = nowSec()
        val base = scrubEpoch ?: wallClock()
        val stepped = base + dir * PlaybackPolicy.scrubStepSeconds(scrubStreak)
        var target = vm.snap(base, stepped)
        if (target != stepped) { scrubPauseUntil = t + 450; scrubStreak = 0 }
        // Going back always lands where the archive exists: the first ◀ from live replays the last few minutes.
        if (dir < 0) target = minOf(target, PlaybackPolicy.rewindLimit(now))
        scrubEpoch = target.coerceIn(now - ch.archiveDays * 86400L + 60, now)
    }
    fun commitScrub() { val t = scrubEpoch ?: return; scrubEpoch = null; channel?.let { vm.playAt(it, t) } }
    fun openPrograms() {
        val ch = channel ?: return
        if (ch.archiveDays <= 0) { noArchive(); return }
        vm.loadTimeline(ch); scrubEpoch = null; programsIndex = 0; showMini = false; showPrograms = true
    }
    fun back() {
        when { showPrograms->showPrograms=false; scrubEpoch!=null->scrubEpoch=null; showMini->showMini=false;showTracks->{showTracks=false;focus.requestFocus()};showAspect->{showAspect=false;focus.requestFocus()}
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
            position=vm.controller.player.currentPosition
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
    LaunchedEffect(channel?.id) { channel?.let { vm.loadTimeline(it) } }
    LaunchedEffect(scrubEpoch) { if (scrubEpoch != null) { delay(1_600); commitScrub() } }
    LaunchedEffect(lastKey, showMini, scrubMs,showTracks,showAspect,scrubEpoch,showPrograms) { if(manuallyHidden) {overlay=false;return@LaunchedEffect}; if(quietEntry && lastKey==enteredAt && !showMini && scrubMs==null && scrubEpoch==null) return@LaunchedEffect; overlay = true; delay(4_000); if (!showMini && !showTracks && !showAspect && playerFocused && scrubMs == null && scrubEpoch == null && !showPrograms && st.error == null) overlay = false }
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
                if(showPrograms) {
                    val ch=channel
                    val rows=if(ch!=null) vm.catchupRows(ch,nowSec()) else emptyList()
                    val liveRow=if(archive!=null) 1 else 0          // in catch-up, row 0 is "back to live"
                    when(k) {
                        Key.DirectionDown -> programsIndex=(programsIndex+1).coerceAtMost((rows.size+liveRow-1).coerceAtLeast(0))
                        Key.DirectionUp -> programsIndex=(programsIndex-1).coerceAtLeast(0)
                        Key.DirectionCenter,Key.Enter -> {
                            if(liveRow==1 && programsIndex==0) returnToLive()
                            else rows.getOrNull(programsIndex-liveRow)?.let {p -> ch?.let {vm.controller.play(PlayRequest.Archive(it,p))}}
                            showPrograms=false
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
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
                        // While rewinding, ▲ opens the channel's earlier shows and ▼ cancels; otherwise they zap.
                        Key.DirectionUp -> { if (scrubEpoch != null) openPrograms() else vm.zap(live.channel, +1); true }
                        Key.DirectionDown -> { if (scrubEpoch != null) scrubEpoch = null else vm.zap(live.channel, -1); true }
                        Key.DirectionCenter, Key.Enter -> { if (scrubEpoch != null) commitScrub() else if(controls.liveOkPauses) vm.controller.togglePlayPause() else {miniIndex=0;vm.loadMini(live.channel); showMini = true}; true }
                        Key.DirectionLeft, Key.MediaRewind -> { moveScrub(-1); true }
                        Key.DirectionRight -> { if (scrubEpoch != null) moveScrub(+1) else vm.toggleFavorite(live.channel); true }
                        Key.MediaFastForward -> { if (scrubEpoch != null) moveScrub(+1); true }
                        Key.Menu -> { vm.controller.switchSource((st.sourceIndex + 1) % st.sources.size.coerceAtLeast(1)); true }
                        else -> false
                    }
                    archive != null -> when (k) { // channel catch-up
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { if (scrubEpoch != null) commitScrub() else vm.controller.togglePlayPause(); true }
                        Key.DirectionLeft, Key.MediaRewind -> { moveScrub(-1); true }
                        Key.DirectionRight, Key.MediaFastForward -> { moveScrub(+1); true }
                        Key.DirectionUp -> { openPrograms(); true }
                        else -> false
                    }
                    else -> when (k) { // VOD
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { if (scrubMs != null) { vm.controller.seekTo(scrubMs!!); scrubMs = null } else vm.controller.togglePlayPause(); true }
                        Key.DirectionLeft -> { scrubMs = ((scrubMs ?: position) - controls.seekSeconds*1000L).coerceAtLeast(0); true }
                        Key.DirectionRight -> { scrubMs = ((scrubMs ?: position) + controls.seekSeconds*1000L).coerceAtMost(vm.controller.player.duration.coerceAtLeast(0)); true }
                        else -> false
                    }
                }
            }.focusRequester(focus).onFocusChanged { playerFocused=it.isFocused }.focusable(),
    ) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; keepScreenOn = true; isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS; player = vm.controller.player;videoView=this } }, modifier = Modifier.fillMaxSize(),update={it.resizeMode=controls.aspectMode},onRelease={it.player=null})

        if(st.isBuffering) Text("טוענים שידור…", modifier=Modifier.align(Alignment.Center).background(NakashColors.Bg).padding(20.dp))
        if(nearEnd && nextEpisode!=null) Text("הפרק הבא: ${nextEpisode!!.title} · ▲ לצפייה", color=NakashColors.Accent, modifier=Modifier.align(Alignment.TopCenter).background(NakashColors.Bg).padding(20.dp))
        if(stillWatching) Dialog(onDismissRequest={stillWatching=false}) { Surface { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("עדיין צופים?")
            Action("המשך צפייה",{stillWatching=false;consecutiveEpisodes=0;lastKey=System.currentTimeMillis();if(vm.controller.player.playbackState==androidx.media3.common.Player.STATE_ENDED && nextEpisode!=null) vm.playNext(nextEpisode!!) else vm.controller.player.play()})
            Action("סיום צפייה",{stillWatching=false;vm.controller.stop();nav.popBackStack()})
        } } }
        AnimatedVisibility((overlay || st.error != null) && !showPrograms, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(.72f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(340.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.94f)))))

                val isFav by vm.isFavorite(live?.channel?.id).collectAsState(initial=false)
                val pillButtons = buildList {
                    if(live!=null) {
                        add(BarButton(if(isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if(isFav) "במועדפים" else "מועדפים", tint=if(isFav) NakashColors.Live else null) { vm.toggleFavorite(live.channel) })
                        if(st.sources.size>1) add(BarButton(Icons.Outlined.Layers, "מקור: "+(st.sources.getOrNull(st.sourceIndex)?.kind?.let { sourceLabel(it) } ?: "ראשי")) { vm.controller.switchSource((st.sourceIndex + 1) % st.sources.size) })
                        if(live.channel.archiveDays>0) add(BarButton(Icons.Outlined.Replay, "מההתחלה") { nowNext.first?.let { p -> if (PlaybackPolicy.archiveAvailable(p.start,p.end,p.isFiller,live.channel.archiveDays,System.currentTimeMillis()/1000)) vm.controller.play(PlayRequest.Archive(live.channel, p)) } })
                        if(live.channel.archiveDays>0) add(BarButton(Icons.Outlined.History, "תוכניות קודמות") { openPrograms() })
                    } else if(archive!=null) {
                        add(BarButton(if(st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(st.isPlaying) "השהה" else "המשך") { vm.controller.togglePlayPause() })
                        add(BarButton(Icons.Outlined.History, "תוכניות קודמות") { openPrograms() })
                    } else if(req is PlayRequest.Episode && nextEpisode!=null) add(BarButton(Icons.Filled.SkipNext, "הפרק הבא") { vm.playNext(nextEpisode!!) })
                    add(BarButton(Icons.Outlined.Subtitles, if(channel==null) "כתוביות ושמע" else "כתוביות", focus=subtitlesFocus) { showTracks=true })
                    add(BarButton(Icons.Outlined.AspectRatio, "יחס תמונה", focus=aspectFocus) { showAspect=true })
                }
                val pills: @Composable () -> Unit = { PillRow(up = { focus.requestFocus() }, buttons = pillButtons) }

                if (channel != null) {
                    if (!(live != null && showMini)) {
                        val mode = when { archive != null -> RibbonMode.CATCHUP; scrubEpoch != null -> RibbonMode.REWIND; else -> RibbonMode.LIVE }
                        val wall = if (mode == RibbonMode.LIVE) nowSec() else scrubEpoch ?: wallClock()
                        val program = vm.programAt(wall) ?: if (live != null) nowNext.first?.takeIf { wall >= it.start && wall < it.end } else archive?.program?.takeIf { wall >= it.start && wall < it.end }
                        val hint = when (mode) {
                            RibbonMode.LIVE -> if (channel.archiveDays > 0) "◀ חזרה אחורה בזמן · ▲ ▼ החלפת ערוץ" else "▲ ▼ החלפת ערוץ"
                            RibbonMode.REWIND -> "◀ ▶ זזים בזמן · החזקה לדילוג מהיר · OK צפייה · ▲ תוכניות קודמות · ▼ ביטול"
                            RibbonMode.CATCHUP -> if (scrubEpoch != null) "◀ ▶ זזים בזמן · החזקה לדילוג מהיר · OK צפייה · ▼ שידור חי" else "◀ ▶ זזים בזמן · ▲ תוכניות קודמות · ▼ שידור חי"
                        }
                        RibbonOverlay(channel, timeline.let { vm.mergedTimeline() }, program, if (mode == RibbonMode.LIVE) nowNext.second else null, wall, nowSec(), mode,
                            st.isPlaying || st.isBuffering, st.sources.getOrNull(st.sourceIndex)?.kind, hint, pills)
                    }
                } else {
                    val durMs = vm.controller.player.duration.coerceAtLeast(0)
                    val title = when (req) { is PlayRequest.Movie -> req.title; is PlayRequest.Episode -> req.title; else -> "" }
                    val sub = when (req) { is PlayRequest.Episode -> "עונה ${req.season} · פרק ${req.number}"; is PlayRequest.Movie -> if (durMs > 0) "סרט · ${fmtDuration(durMs / 1000)}" else "סרט"; else -> "" }
                    FilmstripOverlay(title, sub, scrubMs ?: position, durMs, vm.controller.player.bufferedPosition, scrubMs != null, st.isPlaying, vm.thumbs, thumbKey,
                        "◀ ▶ בין קטעים · OK השהה והמשך", playerFocused, pills)
                }
                if (showMini) MiniEpg(mini, mini.getOrNull(miniIndex)?.first?.id)
                st.toast?.let { Toast(it) { vm.controller.clearToast() } }
                if (digits.isNotEmpty()) Text(digits, style = MaterialTheme.typography.displayLarge, modifier = Modifier.align(Alignment.TopStart).padding(60.dp).background(Color.Black.copy(.6f), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 16.dp))
                st.error?.let { Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text(it, style = MaterialTheme.typography.headlineMedium); Text(if(archive!=null) "חזרה או ▼ לשידור החי · OK לניסיון נוסף" else "OK לניסיון נוסף · חזרה ליציאה", color = NakashColors.Muted) } }
            }
        }
        if(showPrograms && channel!=null) CatchupPanel(channel, vm.catchupRows(channel, nowSec()), programsIndex, archive != null, nowSec())
        if(showTracks) SubtitlePopover(vm.controller) {showTracks=false;lastKey=System.currentTimeMillis();focus.requestFocus()}
        if(showAspect) PlayerPopover("יחס תמונה",0,{showAspect=false;lastKey=System.currentTimeMillis();focus.requestFocus()}) {
            listOf(0 to "מקורי · ללא חיתוך",4 to "מילוי · עם חיתוך",3 to "מתיחה למסך").forEachIndexed {index,(mode,label) ->
                PopoverOption(label,controls.aspectMode==mode,index==0) {vm.controls.setAspect(mode);showAspect=false;lastKey=System.currentTimeMillis();focus.requestFocus()}
            }
        }

    }
}

private enum class RibbonMode { LIVE, REWIND, CATCHUP }

/** Hebrew copy flows right-to-left; the time axis (ribbon, filmstrip, progress) stays left-to-right like the player. */
@Composable
private fun Rtl(content: @Composable () -> Unit) = androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl, content = content)
@Composable
private fun Ltr(content: @Composable () -> Unit) = androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr, content = content)

@Composable
private fun BoxScope.ChannelIdentity(ch: ChannelEntity, subtitle: String, subtitleColor: Color) {
    Rtl {
        Row(Modifier.align(AbsoluteAlignment.TopRight).absolutePadding(top = 30.dp, right = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChannelLogo(ch, 56)
            Column {
                Text(ch.displayName, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp))
                Text(subtitle, style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp), color = subtitleColor)
            }
        }
    }
}

/** Not a focus stop: ▼ always returns to live. Shown with the controls, so the picture stays clean in catch-up. */
@Composable
private fun LivePill() {
    Rtl {
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC14151A)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(NakashColors.Live))
            Text("לשידור החי · ▼", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

/**
 * Live and catch-up controls ("program ribbon"): the channel's shows as blocks on a time axis, a red live line and,
 * while rewinding or in catch-up, a white head with the broadcast time. Clock times only.
 */
@Composable
private fun BoxScope.RibbonOverlay(ch: ChannelEntity, programs: List<EpgEntity>, program: EpgEntity?, next: EpgEntity?, wall: Long, now: Long,
                                   mode: RibbonMode, playing: Boolean, sourceKind: String?, hint: String, pills: @Composable () -> Unit) {
    val behindMin = ((now - wall) / 60).coerceAtLeast(0)
    val subtitle = when (mode) {
        RibbonMode.LIVE -> "ערוץ ${ch.number} · ● שידור חי" + (sourceKind?.takeIf { it != "PRIMARY" }?.let { " · ${sourceLabel(it)}" } ?: "")
        RibbonMode.REWIND -> "חזרה אחורה בזמן"
        RibbonMode.CATCHUP -> "צפייה חוזרת" + if (!playing) " · מושהה" else ""
    }
    ChannelIdentity(ch, subtitle, if (mode == RibbonMode.LIVE) NakashColors.Muted else NakashColors.Accent)
    if (mode != RibbonMode.LIVE) Box(Modifier.align(AbsoluteAlignment.TopLeft).padding(top = 38.dp, start = 48.dp)) { LivePill() }
    Rtl {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(program?.title ?: ch.displayName, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val range = program?.let { "${fmtTime(it.start)}–${fmtTime(it.end)}" }
                val line = if (mode == RibbonMode.LIVE) listOfNotNull(range, next?.let { "הבא: ${it.title} ב־${fmtTime(it.start)}" }).joinToString(" · ")
                    else listOfNotNull(range, "צופים מ־" + dayPrefix(wall) + fmtTime(wall), if (behindMin == 0L) "שידור חי" else "${behindText(behindMin)} מאחורי השידור החי").joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, lineHeight = 22.sp), color = NakashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (programs.isNotEmpty()) Ltr { ProgramRibbon(programs, now, wall, showHead = mode != RibbonMode.LIVE, archiveDays = ch.archiveDays) }
            Text(hint, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = NakashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            pills()
        }
    }
}

@Composable
private fun ProgramRibbon(programs: List<EpgEntity>, now: Long, wall: Long, showHead: Boolean, archiveDays: Int) {
    val span = 150 * 60L
    var winStart = wall - 95 * 60
    var winEnd = winStart + span
    if (winEnd > now + 45 * 60) { winEnd = now + 45 * 60; winStart = winEnd - span }
    val visible = programs.filter { it.end > winStart && it.start < winEnd }
    BoxWithConstraints(Modifier.fillMaxWidth().height(90.dp)) {
        val w = maxWidth
        fun x(t: Long) = w * ((t - winStart).toFloat() / span).coerceIn(0f, 1f)
        visible.forEach { p ->
            val left = x(maxOf(p.start, winStart)); val right = x(minOf(p.end, winEnd))
            val bw = (right - left - 5.dp).coerceAtLeast(0.dp)
            val selected = wall >= p.start && wall < p.end
            val future = p.start > now
            val airing = p.start <= now && p.end > now
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.offset(x = left, y = 20.dp).width(bw).height(62.dp).clip(shape)
                .background(when { selected -> Color.White.copy(alpha = .16f); future -> Color.White.copy(alpha = .04f); else -> Color.White.copy(alpha = .10f) })
                .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier.border(1.dp, Color.White.copy(alpha = .08f), shape))) {
                if (airing) Box(Modifier.align(Alignment.BottomStart).width(minOf(x(now) - left, bw)).height(4.dp).background(Color.White.copy(alpha = .45f)))
                if (bw > 56.dp) Rtl {
                    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 7.dp)) {
                        val note = when { airing -> "משודר עכשיו"; future -> "בהמשך"; PlaybackPolicy.archiveAvailable(p.start, p.end, p.isFiller, archiveDays, now) -> "צפייה חוזרת"; else -> "שודר" }
                        Text("${fmtTime(p.start)} · $note", style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 15.sp), color = NakashColors.Muted, maxLines = 1)
                        Text(p.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp, lineHeight = 19.sp), color = if (future) NakashColors.Dim else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val liveX = x(now)
        val headX = x(wall)
        Box(Modifier.offset(x = liveX - 1.5.dp, y = 12.dp).width(3.dp).height(76.dp).clip(RoundedCornerShape(2.dp)).background(NakashColors.Live))
        if (!showHead || kotlin.math.abs((headX - liveX).value) > 44f)
            Text("● חי", Modifier.offset(x = liveX - 16.dp, y = (-6).dp), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = NakashColors.Live)
        if (showHead) {
            Box(Modifier.offset(x = headX - 1.5.dp, y = 12.dp).width(3.dp).height(76.dp).background(Color.White))
            Text(fmtTime(wall), Modifier.offset(x = headX - 24.dp, y = (-8).dp).clip(RoundedCornerShape(7.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = Color.Black)
        }
    }
}

/**
 * Movies and episodes ("filmstrip"): the frames around you as the timeline — each ◀ ▶ press moves one frame —
 * with the play/pause disc on the left of the position line, like a streaming app. OK plays and pauses.
 */
@Composable
private fun BoxScope.FilmstripOverlay(title: String, sub: String, posMs: Long, durMs: Long, bufferedMs: Long, scrubbing: Boolean, playing: Boolean,
                                      thumbs: ThumbnailGenerator, key: String, hint: String, discHighlighted: Boolean, pills: @Composable () -> Unit) {
    Rtl {
        Column(Modifier.align(AbsoluteAlignment.TopRight).absolutePadding(top = 30.dp, right = 48.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp), color = NakashColors.Muted)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Ltr {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(fmtDuration(posMs / 1000), style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 38.sp))
                    if (durMs > 0) {
                        val left = (durMs - posMs).coerceAtLeast(0)
                        Text("נותרו ${behindText(left / 60_000)} · מסתיים ב־${fmtTime(System.currentTimeMillis() / 1000 + left / 1000)}" + if (!playing && !scrubbing) " · מושהה" else "",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), color = NakashColors.Muted)
                    }
                }
            }
            if (key.isNotEmpty()) Ltr { Filmstrip(thumbs, key, posMs / 1000, durMs / 1000) }
            Ltr {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PlayDisc(playing, discHighlighted)
                    ProgressTrack(Modifier.weight(1f), if (durMs > 0) posMs.toFloat() / durMs else 0f, if (durMs > 0) bufferedMs.toFloat() / durMs else 0f, scrubbing)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                pills()

            }
        }
    }
}

@Composable
private fun PlayDisc(playing: Boolean, highlighted: Boolean) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(if (highlighted) Color.White else Color.White.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "השהה" else "נגן",
            tint = if (highlighted) Color.Black else Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ProgressTrack(modifier: Modifier, position: Float, buffered: Float, thumb: Boolean) {
    BoxWithConstraints(modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .18f))) {
            Box(Modifier.fillMaxWidth(buffered.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = .32f)))
            Box(Modifier.fillMaxWidth(position.coerceIn(0f, 1f)).fillMaxHeight().background(NakashColors.Live))
        }
        if (thumb) Box(Modifier.offset(x = maxWidth * position.coerceIn(0f, 1f) - 8.dp).size(16.dp).clip(CircleShape).background(Color.White))
    }
}

/** Nine frames around the position, 10 s apart; the centre one is where you are. Slots before the start stay empty. */
@Composable
private fun Filmstrip(thumbs: ThumbnailGenerator, key: String, posSec: Long, durSec: Long) {
    val revision by thumbs.revision.collectAsState()
    val step = ThumbnailGenerator.STEP_SEC
    val center = posSec / step * step
    val frames = remember(key, center, revision, durSec) {
        (-4..4).map { i -> val second = center + i * step; val out = second < 0 || (durSec > 0 && second >= durSec); Triple(i, out, if (out) null else thumbs.existing(key, second)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        frames.forEach { (i, out, file) ->
            val centre = i == 0
            val shape = RoundedCornerShape(8.dp)
            Box(Modifier.size(if (centre) 124.dp else 82.dp, if (centre) 70.dp else 46.dp).clip(shape).background(if (out) Color.Transparent else NakashColors.S2)
                .then(if (centre) Modifier.border(2.5.dp, Color.White, shape) else Modifier)) {
                if (file != null) AsyncImage(file, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = if (centre) 1f else .75f)
            }
        }
    }
}

private fun dayPrefix(epoch: Long) = dayLabel(epoch).let { if (it == "היום") "" else "$it " }

private fun behindText(min: Long) = if (min < 60) "$min דק׳" else "${min / 60}:${"%02d".format(min % 60)} שע׳"

private fun dayLabel(epoch: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (d) { today -> "היום"; today.minusDays(1) -> "אתמול"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE dd.MM", java.util.Locale("he"))) }
}

/** In-channel catch-up: what already aired on this channel, newest first. Hebrew list, so it reads right-to-left. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CatchupPanel(ch: ChannelEntity, rows: List<EpgEntity>, index: Int, inCatchup: Boolean, now: Long) {
    val list = rememberLazyListState()
    LaunchedEffect(index) { runCatching { list.animateScrollToItem((index - 2).coerceAtLeast(0)) } }
    val offset = if (inCatchup) 1 else 0
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(660.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(.9f), Color.Black.copy(.96f))))
            .absolutePadding(left = 90.dp, right = 56.dp, top = 56.dp, bottom = 36.dp)) {
            Text("צפייה חוזרת בערוץ", style = MaterialTheme.typography.labelLarge, color = NakashColors.Accent)
            Text(ch.displayName, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(18.dp))
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inCatchup) item(key = "live") { CatchupRow("עכשיו", "חזרה לשידור החי", "", index == 0, NakashColors.Live) }
                itemsIndexed(rows, key = { _, p -> p.id }) { i, p ->
                    val airing = p.end > now
                    CatchupRow("${fmtTime(p.start)}–${fmtTime(p.end)}", p.title,
                        if (airing) "● משודר עכשיו · מההתחלה" else dayLabel(p.start),
                        index == i + offset, if (airing) NakashColors.Live else NakashColors.Muted)
                }
                if (rows.isEmpty()) item { Text("אין עדיין תוכניות בארכיון של הערוץ", color = NakashColors.Muted) }
            }
            Spacer(Modifier.height(12.dp))
            Text("▲ ▼ בחירה · OK צפייה מההתחלה · חזרה לסגירה", color = NakashColors.Muted, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CatchupRow(time: String, title: String, note: String, selected: Boolean, noteColor: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Color.White else Color.White.copy(alpha = .06f)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(time, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = if (selected) Color.Black else Color.White, modifier = Modifier.width(128.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp), color = if (selected) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black.copy(alpha = .65f) else noteColor, maxLines = 1)
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

/** Controls as pills with an icon and a label; the focused one turns white. ▲ leaves the row back to the video. */
@Composable
private fun PillRow(up:()->Unit,buttons:List<BarButton>) {
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        buttons.forEach {b ->
            Surface(onClick=b.click,modifier=(b.focus?.let {Modifier.focusRequester(it)} ?: Modifier).height(40.dp)
                .onPreviewKeyEvent {event -> if(event.type==KeyEventType.KeyDown && event.key==Key.DirectionUp) {up();true} else false},
                shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.05f),
                colors=ClickableSurfaceDefaults.colors(containerColor=Color.White.copy(alpha=.12f),focusedContainerColor=Color.White,contentColor=b.tint ?: Color.White,focusedContentColor=b.tint ?: Color.Black)) {
                Row(Modifier.fillMaxHeight().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(b.icon,contentDescription=null,modifier=Modifier.size(20.dp))
                    Text(b.label,style=MaterialTheme.typography.labelLarge.copy(fontSize=15.sp),maxLines=1)
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
