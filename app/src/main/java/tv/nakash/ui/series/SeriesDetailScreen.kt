package tv.nakash.ui.series

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import tv.nakash.data.local.EpisodeEntity
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.player.PlayRequest
import tv.nakash.ui.library.Action
import tv.nakash.ui.library.LibraryViewModel
import tv.nakash.ui.theme.NakashColors

@Composable
fun SeriesDetailScreen(nav:NavHostController,id:Int,series:Boolean=true,vm:LibraryViewModel=hiltViewModel()) {
    val detailEntry=remember(nav,id,series) {requireNotNull(nav.currentBackStackEntry)}
    val returnedToMenu by detailEntry.savedStateHandle.getStateFlow("returnToDetails",false).collectAsState()
    val show by remember(id,series) {if(series) vm.catalog.series(id) else flowOf(null)}.collectAsState(null)
    val movie by remember(id,series) {if(!series) vm.catalog.movie(id) else flowOf(null)}.collectAsState(null)
    val seasons by remember(id) {if(series) vm.catalog.seasons(id) else flowOf(emptyList())}.collectAsState(emptyList())
    var season by rememberSaveable(id) {mutableStateOf<Int?>(null)}
    val selectedSeason=season ?: seasons.firstOrNull()?.number ?: 1
    val episodes by remember(id,selectedSeason) {vm.catalog.episodes(id,selectedSeason)}.collectAsState(emptyList())
    val progress by remember(id) {vm.user.progressForSeries(id)}.collectAsState(emptyList())
    val favorite by remember(id) {vm.user.isFavorite(if(series) "series" else "movie",id.toString())}.collectAsState(false)
    var resume by remember(id) {mutableStateOf<WatchProgressEntity?>(null)}
    var chosen by remember(id) {mutableStateOf<EpisodeEntity?>(null)}
    var excerpt by remember(id) {mutableStateOf<EpisodeEntity?>(null)}
    var busy by remember(id) {mutableStateOf(true)}
    var error by remember(id) {mutableStateOf<String?>(null)}
    var attempt by remember(id) {mutableIntStateOf(0)}
    var panel by rememberSaveable(id) {mutableStateOf<String?>(null)}
    var menuVisible by remember(id) {mutableStateOf(true)}
    var interaction by remember(id) {mutableIntStateOf(0)}
    var consumeWake by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    var handoff by remember(id) {mutableStateOf(false)}
    var frame by remember(id) {mutableStateOf(false)}
    var started by remember(id) {mutableStateOf(false)}
    val playback by vm.player.state.collectAsState()
    var videoView by remember {mutableStateOf<PlayerView?>(null)}
    val heroFocus=remember {FocusRequester()}
    val panelFocus=remember {FocusRequester()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var active by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    val accessibility=LocalAccessibilityManager.current

    tv.nakash.ui.components.PlaybackFrameCache(videoView,vm.player,vm.thumbs,active && panel==null && started)
    val menuAlpha by animateFloatAsState(if(menuVisible || panel!=null) 1f else 0f,tween(if(menuVisible) 180 else 850),label="series menu")
    val videoAlpha by animateFloatAsState(if(frame) 1f else 0f,tween(700),label="series preview")

    DisposableEffect(lifecycle,id) {
        val listener=object:Player.Listener {
            override fun onRenderedFirstFrame() {frame=true}
        }
        vm.player.player.addListener(listener)
        val observer=LifecycleEventObserver {_,event ->
            if(event==Lifecycle.Event.ON_RESUME) active=true
            if(event==Lifecycle.Event.ON_PAUSE && !handoff) {active=false;vm.player.player.pause()}
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer);vm.player.player.removeListener(listener)
            if(!handoff) {vm.thumbs.stop();vm.player.stop()}
        }
    }
    LaunchedEffect(id,attempt,returnedToMenu) {
        busy=true;error=null
        try {
            if(series) vm.catalog.ensureSeriesDetail(id,attempt>0) else vm.catalog.ensureMovieInfo(id)
            resume=if(series) vm.user.latestForSeries(id) else vm.user.progress("movie",id.toString())
            val allSeasons=if(series) vm.catalog.seasons(id).first() else emptyList()
            excerpt=allSeasons.firstOrNull()?.let {vm.catalog.episodes(id,it.number).first().firstOrNull()}
            var last:EpisodeEntity?=null
            for(s in allSeasons) {
                last=vm.catalog.episodes(id,s.number).first().firstOrNull {it.id==resume?.refId}
                if(last!=null) break
            }
            chosen=if(last!=null && resume?.completed==true) vm.catalog.nextEpisode(id,last.season,last.number) ?: excerpt else last ?: excerpt
            if(season==null) season=chosen?.season
        } catch(cancel:kotlinx.coroutines.CancellationException) {throw cancel}
        catch(_:Exception) {error="לא הצלחנו לטעון את התוכן. אפשר לנסות שוב."}
        busy=false
    }
    LaunchedEffect(busy,panel) {
        delay(180)
        if(panel==null && !busy) runCatching {heroFocus.requestFocus()}
        else if(panel!=null) runCatching {panelFocus.requestFocus()}
    }
    fun request(e:EpisodeEntity):PlayRequest.Episode {
        val saved=progress.firstOrNull {it.refId==e.id} ?: resume?.takeIf {it.refId==e.id}
        return PlayRequest.Episode(e.id,e.seriesId,e.season,e.number,e.title,e.containerExt,saved?.takeUnless {it.completed}?.positionMs ?: 0)
    }
    fun openPlayer(quiet:Boolean=false) {
        handoff=true
        nav.navigate("player")
        nav.currentBackStackEntry?.savedStateHandle?.set("cinematicEntry",quiet)
    }
    fun play(e:EpisodeEntity) {
        val current=vm.player.state.value.request as? PlayRequest.Episode
        if(current?.episodeId!=e.id || vm.player.state.value.error!=null) vm.player.play(request(e))
        else vm.player.player.play()
        openPlayer()
    }
    fun playChosen() {
        if(series) chosen?.let(::play)
        else movie?.let {m ->
            if((vm.player.state.value.request as? PlayRequest.Movie)?.id!=m.id || vm.player.state.value.error!=null)
                vm.player.play(PlayRequest.Movie(m.id,m.title,m.containerExt,resume?.takeUnless {it.completed}?.positionMs ?: 0))
            else vm.player.player.play()
            openPlayer()
        }
    }
    // The same main player starts here and continues in fullscreen: no restart or preview limit.
    LaunchedEffect(busy,chosen?.id,movie?.id,active,panel,returnedToMenu) {
        menuVisible=true
        if(panel!=null) {vm.player.player.pause();return@LaunchedEffect}
        if(!returnedToMenu && !busy && active && (chosen!=null || movie!=null)) {
            delay(3_000)
            if(!started) {
                frame=false
                if(series) chosen?.let {vm.player.play(request(it))}
                else movie?.let {vm.player.play(PlayRequest.Movie(it.id,it.title,it.containerExt,resume?.takeUnless {r -> r.completed}?.positionMs ?: 0))}
                started=true
            } else vm.player.player.play()
        }
    }
    LaunchedEffect(returnedToMenu) {
        if(returnedToMenu) {menuVisible=true;frame=false;started=false;handoff=false;panel=null}
    }
    LaunchedEffect(frame,interaction,panel,active,playback.isPlaying,playback.error,returnedToMenu) {
        menuVisible=true
        if(!returnedToMenu && frame && active && panel==null && playback.isPlaying && playback.error==null) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(5_000,containsIcons=true,containsText=true,containsControls=true) ?: 5_000)
            menuVisible=false
            delay(850)
            openPlayer(quiet=true)
        }
    }
    BackHandler(panel!=null || !menuVisible) {if(panel!=null) panel=null else {menuVisible=true;interaction++}}
    Box(Modifier.fillMaxSize().clipToBounds().onPreviewKeyEvent {event ->
        if(event.type==KeyEventType.KeyUp && consumeWake) {consumeWake=false;true}
        else if(event.type==KeyEventType.KeyDown) {
            interaction++
            if(!menuVisible) {menuVisible=true;consumeWake=true;true} else false
        } else false
    }) {
        AsyncImage(movie?.backdrop ?: show?.backdrop ?: movie?.poster ?: show?.cover,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        AndroidView(factory={ctx -> (LayoutInflater.from(ctx).inflate(tv.nakash.R.layout.player_preview,null,false) as PlayerView).apply {player=vm.player.player;isFocusable=false;videoView=this}},
            modifier=Modifier.fillMaxSize().graphicsLayer {alpha=videoAlpha},onRelease={it.player=null})
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent,NakashColors.Bg.copy(alpha=.94f*menuAlpha)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.15f*menuAlpha),Color.Transparent,NakashColors.Bg.copy(.95f*menuAlpha)))))
        if(panel==null) Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.64f).padding(start=40.dp,end=16.dp).graphicsLayer {alpha=menuAlpha},verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(series) "NAKASH TV  ·  סדרה" else "NAKASH TV  ·  סרט",color=Color.White,style=MaterialTheme.typography.labelLarge)
            Text(movie?.title ?: show?.title ?: "טוענים…",style=MaterialTheme.typography.displayLarge.copy(fontSize=38.sp,lineHeight=44.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(listOfNotNull((movie?.year ?: show?.year)?.toString(),(movie?.rating ?: show?.rating)?.let {"★ %.1f".format(it)},seasons.takeIf {it.isNotEmpty()}?.let {"${it.size} עונות"},(movie?.genres ?: show?.genres)?.replace(","," · ")?.takeIf {it.isNotBlank()}).joinToString("   ·   "),color=NakashColors.Muted,style=MaterialTheme.typography.bodyLarge.copy(fontSize=14.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Text((movie?.plot ?: show?.plot)?.takeIf {it.isNotBlank()} ?: "בחר פרק והתחל לצפות",style=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp,lineHeight=23.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            Column(Modifier.width(290.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                val savedPosition=if(series) (progress.firstOrNull {it.refId==chosen?.id} ?: resume?.takeIf {it.refId==chosen?.id}) else resume
                val canResume=savedPosition!=null && !savedPosition.completed && savedPosition.positionMs>0
                CinematicAction(if(canResume) "▶  המשך צפייה" else "▶  התחל לנגן",::playChosen,Modifier.focusRequester(heroFocus),enabled=chosen!=null || movie!=null)
                if(canResume) {
                    val position=savedPosition!!.positionMs
                    val duration=savedPosition.durationMs.takeIf {it>0} ?: ((if(series) chosen?.durationSec else movie?.durationSec) ?: 0)*1000L
                    Column(Modifier.fillMaxWidth().padding(start=27.dp,end=12.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        if(series) chosen?.let {episode -> Text("עונה ${episode.season} · פרק ${episode.number}",color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.bodyMedium.copy(fontSize=13.sp))}
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                            if(duration>0) Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha=.22f))) {
                                Box(Modifier.fillMaxWidth((position.toFloat()/duration).coerceIn(0f,1f)).fillMaxHeight().background(Color.White.copy(alpha=.9f)))
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(tv.nakash.util.fmtDuration(position/1000),color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall.copy(fontSize=11.sp))
                                if(duration>0) Text(tv.nakash.util.fmtDuration(duration/1000),color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall.copy(fontSize=11.sp))
                            }
                        }
                    }
                }
                if(series) CinematicAction("פרקים ועונות",{panel="episodes"})
                CinematicAction(if(favorite) "✓  ברשימה שלי" else "+  הרשימה שלי",{scope.launch {vm.user.toggleFavorite(if(series) "series" else "movie",id.toString())}})
                CinematicAction("מידע נוסף",{panel="details"})
            }
            if(busy) Text("טוענים…",color=NakashColors.Muted)
            error?.let {Text(it,color=NakashColors.Live);Action("ניסיון נוסף",{attempt++})}
            if(series && !busy && error==null && chosen==null) Text("אין פרקים זמינים כרגע",color=NakashColors.Muted)
            if(playback.isBuffering && started) Text("מתחילים לצפות…",color=NakashColors.Muted,style=MaterialTheme.typography.bodyMedium)
            playback.error?.let {
                Text(it,color=NakashColors.Muted,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                CinematicAction("ניסיון נוסף",{started=false;attempt++},Modifier.width(290.dp))
            }
        }
        if(panel!=null) {
            Column(Modifier.fillMaxSize().background(NakashColors.Bg.copy(.98f)).padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column {Text(movie?.title ?: show?.title ?: "",style=MaterialTheme.typography.headlineMedium);Text(if(panel=="episodes") "פרקים ועונות" else "על הסדרה",color=NakashColors.Muted)}
                    Action("חזרה",{panel=null},Modifier.focusRequester(panelFocus))
                }
                if(panel=="details") {
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(20.dp)) {
                        item {Text(movie?.plot ?: show?.plot ?: "אין תקציר זמין",style=MaterialTheme.typography.bodyLarge)}
                        (movie?.cast ?: show?.cast)?.takeIf {it.isNotBlank()}?.let {cast -> item {Text("בהשתתפות: $cast",color=NakashColors.Muted)}}
                        item {Action(if(favorite) "✓ ברשימה שלי" else "+ לרשימה שלי",{scope.launch {vm.user.toggleFavorite(if(series) "series" else "movie",id.toString())}})}
                    }
                } else {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(6.dp)) {items(seasons,key={it.id}) {s -> Action(if(s.number==selectedSeason) "✓ ${s.name}" else s.name,{season=s.number})}}
                    LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(busy) item {Text("טוענים פרקים…")}
                        if(!busy && episodes.isEmpty()) item {Text("לא נמצאו פרקים בעונה הזו",color=NakashColors.Muted)}
                        items(episodes,key={it.id}) {e ->
                            val saved=progress.firstOrNull {it.refId==e.id}
                            Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Surface(onClick={play(e)},modifier=Modifier.weight(1f),colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.S1,focusedContainerColor=NakashColors.S3)) {
                                    Row(Modifier.padding(10.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                        AsyncImage(e.image ?: movie?.backdrop ?: show?.backdrop ?: movie?.poster ?: show?.cover,null,Modifier.width(144.dp).height(81.dp),contentScale=ContentScale.Crop)
                                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                            Text("${if(saved?.completed==true) "✓ " else ""}${e.number}. ${e.title}",maxLines=1,overflow=TextOverflow.Ellipsis)
                                            e.plot?.takeIf {it.isNotBlank()}?.let {Text(it,maxLines=2,overflow=TextOverflow.Ellipsis,color=NakashColors.Muted,style=MaterialTheme.typography.bodySmall)}
                                            e.durationSec?.let {Text("${it/60} דקות",color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall)}
                                        }
                                    }
                                }
                                Action(if(saved?.completed==true) "לא נצפה" else "סמן כנצפה",{scope.launch {if(saved?.completed==true) vm.user.remove("episode",e.id) else vm.user.markWatched("episode",e.id,id,(e.durationSec ?: 0)*1000L)}})
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinematicAction(label:String,click:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    var focused by remember {mutableStateOf(false)}
    Surface(onClick=click,enabled=enabled,modifier=modifier.fillMaxWidth().height(44.dp).onFocusChanged {focused=it.isFocused},
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
        colors=ClickableSurfaceDefaults.colors(containerColor=Color.Transparent,focusedContainerColor=Color.White.copy(.14f),contentColor=Color.White,focusedContentColor=Color.White)) {
        Row(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(24.dp).background(if(focused) Color.White else Color.Transparent))
            Text(label,color=if(enabled) Color.White else NakashColors.Muted,style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp))
        }
    }
}
