package tv.nakash.ui.series

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import tv.nakash.domain.TmdbDetails
import tv.nakash.domain.LibraryMatch
import tv.nakash.ui.components.YouTubeTrailer
import tv.nakash.ui.components.TrailerHandle
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.focusable

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val rating by remember(id) {vm.user.rating(if(series) "series" else "movie",id.toString())}.collectAsState(null)
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
    var videoView by remember {mutableStateOf<PlayerView?>(null)}
    val heroFocus=remember {FocusRequester()}
    var plotCut by remember {mutableStateOf(false)}
    var plotOpen by remember {mutableStateOf(false)}
    var upAt by remember {mutableStateOf(0L)}
    val panelFocus=remember {FocusRequester()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var active by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    val accessibility=LocalAccessibilityManager.current
    // TMDB extras: official trailer, cast, recommendations, and a backdrop / Hebrew overview when the provider lacks them.
    val tmdbKey by vm.tmdbPrefs.key.collectAsState()
    var tmdb by remember(id) {mutableStateOf<TmdbDetails?>(null)}
    var tmdbDone by remember(id,tmdbKey) {mutableStateOf(tmdbKey==null)}
    var trailerFailed by remember(id) {mutableStateOf(false)}
    var trailerVisible by remember(id) {mutableStateOf(false)}
    var fullTrailer by remember(id) {mutableStateOf(false)}
    var trailerPlaying by remember(id) {mutableStateOf(false)}
    val trailerAlpha by animateFloatAsState(if(trailerPlaying) 1f else 0f,tween(500),label="trailer")
    val trailerKey=tmdb?.trailerKey?.takeUnless {trailerFailed}

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
    // Where to resume: last episode watched (or the next one once finished), else the first episode. Cheap Room reads.
    suspend fun refreshResume() {
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
    }
    // The page shows at once from what is already stored; the provider refresh runs behind it with a deadline.
    // Only a series with no stored episodes waits for it (there is nothing to play yet).
    LaunchedEffect(id,attempt) {
        error=null
        runCatching {refreshResume()}
        busy=series && chosen==null
        val ok=runCatching {kotlinx.coroutines.withTimeoutOrNull(12_000) {if(series) vm.catalog.ensureSeriesDetail(id,attempt>0) else vm.catalog.ensureMovieInfo(id)}}
        runCatching {refreshResume()}
        if(series && chosen==null && (ok.isFailure || ok.getOrNull()==null)) error="לא הצלחנו לטעון את התוכן. אפשר לנסות שוב."
        busy=false
    }
    // TMDB extras in parallel with the provider (title and year are already stored).
    val tmdbTitle=movie?.title ?: show?.title
    LaunchedEffect(id,tmdbKey,tmdbTitle!=null) {
        if(tmdbKey==null || tmdbTitle==null) return@LaunchedEffect
        tmdb=kotlinx.coroutines.withTimeoutOrNull(15_000) {if(series) show?.let {vm.tmdb.tv(it.title,it.year)} else movie?.let {vm.tmdb.movie(it.tmdbId,it.title,it.year)}}
        tmdbDone=true
    }
    // Focus Play once when the page opens and whenever a panel closes; never pulled back while the user moves.
    LaunchedEffect(panel) {
        delay(120)
        if(panel==null) runCatching {heroFocus.requestFocus()} else runCatching {panelFocus.requestFocus()}
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
    // Warm the stream connection for what Play would start, while the user reads the page.
    val warmKey=if(series) chosen?.id else movie?.id?.toString()
    LaunchedEffect(warmKey) {
        if(series) chosen?.let {vm.player.prewarm(request(it))}
        else movie?.let {m -> vm.player.prewarm(PlayRequest.Movie(m.id,m.title,m.containerExt,0))}
    }
    LaunchedEffect(returnedToMenu) {
        if(returnedToMenu) {
            menuVisible=true;frame=false;started=false;handoff=false;panel=null
            detailEntry.savedStateHandle["returnToDetails"]=false
            runCatching {refreshResume()}
            runCatching {heroFocus.requestFocus()}
        }
    }
    // The official trailer plays full screen behind the page (Netflix): 2.5 s after the page settles, only while the
    // main page is showing. No trailer → the still backdrop; never a clip from the movie itself.
    var trailerOn by remember(id) {mutableStateOf(false)}
    var stagePlaying by remember(id) {mutableStateOf<String?>(null)}
    LaunchedEffect(trailerKey,panel,fullTrailer,handoff) {
        trailerOn=false
        if(trailerKey!=null && panel==null && !fullTrailer && !handoff) {delay(2_500);trailerOn=true}
    }
    val bgAlpha by animateFloatAsState(if(trailerOn && stagePlaying!=null && stagePlaying==trailerKey) 0f else 1f,tween(700),label="detailBg")
    val similarLists by produceState(Pair(emptyList<tv.nakash.data.local.MovieEntity>(),emptyList<tv.nakash.data.local.SeriesEntity>()),tmdb) {
        val recs=tmdb?.similar.orEmpty()
        if(recs.isEmpty()) return@produceState
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if(series) Pair(emptyList(),LibraryMatch.match(recs,vm.catalog.recentlyUpdatedSeries(Int.MAX_VALUE).first(),{it.title},{it.year}).filter {it.id!=id}.take(15))
            else Pair(LibraryMatch.match(recs,vm.catalog.newestMovies(Int.MAX_VALUE).first(),{it.title},{it.year}).filter {it.id!=id}.take(15),emptyList())
        }
    }
    val similarMovies=similarLists.first
    val similarSeries=similarLists.second
    val similar=similarMovies.map {SimilarItem("movie/${it.id}",it.title,it.backdrop ?: it.poster,listOfNotNull(it.year?.toString(),it.runtimeMin?.let {m -> "$m דקות"},it.genres.split(',').firstOrNull()?.takeIf {g -> g.isNotBlank()}).joinToString("  ·  "),it.plot)} +
        similarSeries.map {SimilarItem("seriesDetail/${it.id}",it.title,it.backdrop ?: it.cover,listOfNotNull(it.year?.toString(),it.genres.split(',').firstOrNull()?.takeIf {g -> g.isNotBlank()}).joinToString("  ·  "),it.plot)}
    val title=movie?.title ?: show?.title ?: "טוענים…"
    val meta=listOfNotNull(if(series) "סדרה" else "סרט",(movie?.year ?: show?.year)?.toString(),(movie?.genres ?: show?.genres)?.split(',')?.firstOrNull()?.takeIf {it.isNotBlank()},
        seasons.takeIf {it.isNotEmpty()}?.let {if(it.size==1) "עונה אחת" else "${it.size} עונות"},movie?.let {m -> (m.durationSec?.div(60) ?: m.runtimeMin)?.let {"$it דקות"}},(movie?.rating ?: show?.rating)?.takeIf {it>0}?.let {"★ %.1f".format(it)}).joinToString("  ·  ")
    BackHandler(panel!=null) {panel=null}
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        val density=androidx.compose.ui.platform.LocalDensity.current
        val screen=with(density) {androidx.compose.ui.geometry.Rect(0f,0f,maxWidth.toPx(),maxHeight.toPx())}
        tv.nakash.ui.components.TrailerStage(if(trailerOn && trailerKey!=null) tv.nakash.ui.components.TrailerTarget(trailerKey,screen,0f) else null,onPlaying={stagePlaying=it})
        DetailBackdrop(tmdb?.backdrop ?: movie?.backdrop ?: show?.backdrop ?: movie?.poster ?: show?.cover,Modifier.fillMaxSize().graphicsLayer {alpha=bgAlpha})
        // Text side (right, RTL) darkened; the rest of the picture stays clear.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent,.32f to Color.Transparent,.62f to Color.Black.copy(alpha=.66f),1f to Color.Black.copy(alpha=.86f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.6f to Color.Transparent,1f to Color.Black.copy(alpha=.55f))))
        // Two fixed regions: the menu at the bottom, sized for every button it can have (so it is always fully on
        // screen and Play keeps its place when "טריילר"/"כותרים דומים" arrive), and the text above it, fitted to the
        // space that is left: a long title or plot gets a smaller size / fewer lines instead of pushing the menu away.
        if(panel==null) Column(Modifier.align(Alignment.TopStart).fillMaxHeight().fillMaxWidth(.52f).padding(start=56.dp,end=8.dp,top=56.dp,bottom=28.dp)
            .onPreviewKeyEvent {e -> if(e.type==androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key==androidx.compose.ui.input.key.Key.DirectionUp) upAt=System.currentTimeMillis(); false}) {
            // The text from the top (Netflix TV), the menu right under it; the text takes only the height it needs.
            BoxWithConstraints(Modifier.weight(1f,fill=false).fillMaxWidth()) {
                val avail=maxHeight
                val boxW=maxWidth
                val roomy=avail>=290.dp
                val medium=!roomy && avail>=220.dp
                val titleStyle=MaterialTheme.typography.displayLarge.copy(fontSize=if(roomy) 46.sp else if(medium) 38.sp else 34.sp,lineHeight=if(roomy) 52.sp else if(medium) 44.sp else 40.sp)
                Column(Modifier.align(Alignment.TopStart),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val plot=(movie?.plot ?: show?.plot)?.takeIf {it.isNotBlank()} ?: tmdb?.overview ?: ""
                    // A long plot needs the room more than a second title line does.
                    val titleLines=if(roomy || medium) 2 else 1
                    // On one line the title shrinks until it fits whole, instead of being cut.
                    val measurer=androidx.compose.ui.text.rememberTextMeasurer()
                    val widthPx=with(density) {boxW.roundToPx()}
                    val fitted=if(titleLines==2) titleStyle else listOf(titleStyle.fontSize.value,32f,28f,25f,22f).map {titleStyle.copy(fontSize=it.sp,lineHeight=(it+6).sp)}
                        .firstOrNull {measurer.measure(title,it,maxLines=1,softWrap=false).size.width<=widthPx} ?: titleStyle.copy(fontSize=22.sp,lineHeight=28.sp)
                    Text(title,style=fitted,maxLines=titleLines,overflow=TextOverflow.Ellipsis)
                    Text(meta,color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.titleLarge.copy(fontSize=17.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    // As much of the plot as fits above the menu. When it is cut, ▲ from the menu lands on it and opens the
                    // whole of it (with the cast and director) over the picture.
                    Surface(onClick={plotOpen=true},enabled=plotCut,modifier=Modifier.weight(1f,fill=false).onFocusChanged {if(it.isFocused) {
                        // Only a real ▲ opens the whole plot; focus that lands here any other way goes back to Play.
                        if(System.currentTimeMillis()-upAt<600) plotOpen=true else runCatching {heroFocus.requestFocus()}
                    }},
                        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1f),
                        colors=ClickableSurfaceDefaults.colors(containerColor=Color.Transparent,contentColor=Color.White,focusedContainerColor=Color.White.copy(alpha=.12f),focusedContentColor=Color.White,
                            disabledContainerColor=Color.Transparent,disabledContentColor=Color.White)) {
                        Text(plot,style=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp,lineHeight=23.sp),overflow=TextOverflow.Ellipsis,onTextLayout={plotCut=it.hasVisualOverflow})
                    }
                    val castNames=tmdb?.cast?.take(3)?.map {it.name}?.takeIf {it.isNotEmpty()} ?: (movie?.cast ?: show?.cast)?.split(',')?.map {it.trim()}?.filter {it.isNotEmpty()}?.take(3).orEmpty()
                    if(castNames.isNotEmpty()) Text("בהשתתפות: "+castNames.joinToString(", "),color=Color.White.copy(alpha=.72f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(roomy) movie?.director?.takeIf {it.isNotBlank() && it.split(',').size<=2}?.let {Text("במאי: $it",color=Color.White.copy(alpha=.72f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),maxLines=1,overflow=TextOverflow.Ellipsis)}
                    error?.let {Text(it,color=NakashColors.Live);Action("ניסיון נוסף",{attempt++})}
                    if(series && !busy && error==null && chosen==null) Text("אין פרקים זמינים כרגע",color=NakashColors.Muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            // Netflix-style rating; it feeds the profile's recommendations (and syncs). Pressing the chosen one again clears it.
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                fun rate(v:Int) {scope.launch {vm.user.setRating(if(series) "series" else "movie",id.toString(),if(rating==v) null else v)}}
                RateButton("לא בשבילי",rating==-1,{rate(-1)}) {Icon(if(rating==-1) androidx.compose.material.icons.Icons.Filled.ThumbDown else androidx.compose.material.icons.Icons.Outlined.ThumbDown,null,Modifier.size(24.dp))}
                RateButton("אהבתי",rating==1,{rate(1)}) {Icon(if(rating==1) androidx.compose.material.icons.Icons.Filled.ThumbUp else androidx.compose.material.icons.Icons.Outlined.ThumbUp,null,Modifier.size(24.dp))}
                RateButton("ממש אהבתי!",rating==2,{rate(2)}) {
                    val icon=if(rating==2) androidx.compose.material.icons.Icons.Filled.ThumbUp else androidx.compose.material.icons.Icons.Outlined.ThumbUp
                    Box(Modifier.size(30.dp)) {
                        Icon(icon,null,Modifier.size(20.dp).align(Alignment.BottomStart))
                        Icon(icon,null,Modifier.size(20.dp).align(Alignment.TopEnd))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val savedPosition=if(series) (progress.firstOrNull {it.refId==chosen?.id} ?: resume?.takeIf {it.refId==chosen?.id}) else resume
            val canResume=savedPosition!=null && !savedPosition.completed && savedPosition.positionMs>0
            val playLabel=when {
                series -> chosen?.let {(if(canResume) "המשך צפייה · " else "הפעל את ")+"עונה ${it.season} – פרק ${it.number}"} ?: "הפעל"
                canResume -> "המשך צפייה · נותרו ${((savedPosition!!.durationMs-savedPosition.positionMs).coerceAtLeast(0)/60000)} דק׳"
                else -> "הפעל"
            }
            // The menu scrolls (Netflix TV): about three and a half rows show, the focused one is kept clear of the
            // edges and the rows beyond fade out, so the text above gets the room instead of a block sized for all.
            val menuScroll=androidx.compose.foundation.rememberScrollState()
            val fadePx=with(density) {30.dp.toPx()}
            val keepClear=remember(fadePx) {object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float):Float {
                    val lead=offset-fadePx; val trail=offset+size+fadePx-containerSize
                    return when {lead<0f -> lead; trail>0f -> trail; else -> 0f}
                }
            }}
            CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides keepClear) {
            // Room on the sides inside the scroller: the focused row grows a little and was cut at the edge.
            Column(Modifier.offset(x=(-12).dp).width(424.dp).height(MenuRowH*3.5f+MenuGap*3).fadingEdges(menuScroll,fadePx).verticalScroll(menuScroll).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(MenuGap)) {
                DetailMenuItem(androidx.compose.material.icons.Icons.Filled.PlayArrow,playLabel,::playChosen,Modifier.focusRequester(heroFocus),enabled=chosen!=null || movie!=null)
                // A series' trailer is under "פרקים ועוד" › "טריילרים ועוד" (Netflix); a movie keeps it here.
                if(!series && trailerKey!=null) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.Theaters,"טריילר",{fullTrailer=true})
                if(series) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.VideoLibrary,"פרקים ועוד",{panel="episodes"})
                if(similar.isNotEmpty()) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.GridView,"כותרים דומים",{panel="similar"})
                DetailMenuItem(if(favorite) androidx.compose.material.icons.Icons.Filled.Check else androidx.compose.material.icons.Icons.Filled.Add,if(favorite) "ברשימה שלי" else "הוסף לרשימה שלי",{scope.launch {vm.user.toggleFavorite(if(series) "series" else "movie",id.toString())}})
                // Only while there is something to continue here; the title then leaves "המשך צפייה" (finished episodes keep ✓).
                val inContinue=if(series) progress.any {!it.completed && it.positionMs>0} else resume?.let {!it.completed && it.positionMs>0} == true
                // Focus goes to Play first: when this row disappears, focus would otherwise fall onto the plot and open it.
                if(inContinue) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.RemoveCircleOutline,"הסרה מהמשך צפייה",{runCatching {heroFocus.requestFocus()};scope.launch {
                    vm.user.removeFromContinue(if(series) "episode" else "movie",id.toString(),if(series) id else null)
                    resume=if(series) vm.user.latestForSeries(id) else null
                }})
                DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.Info,"תיאור, שחקנים ופרטים",{panel="details"})
            }
            }
        }
        if(plotOpen && panel==null) PlotOverlay(title,meta,(movie?.plot ?: show?.plot)?.takeIf {it.isNotBlank()} ?: tmdb?.overview ?: "",
            tmdb?.cast?.map {it.name}?.takeIf {it.isNotEmpty()} ?: (movie?.cast ?: show?.cast)?.split(',')?.map {it.trim()}?.filter {it.isNotEmpty()}.orEmpty(),
            movie?.director?.takeIf {it.isNotBlank()}) {plotOpen=false;scope.launch {kotlinx.coroutines.delay(50);runCatching {heroFocus.requestFocus()}}}
        if(panel=="similar") SimilarPanel(title,meta,similar,panelFocus) {nav.navigate(it)}
        if(fullTrailer && trailerKey!=null) FullTrailer(trailerKey,{fullTrailer=false;interaction++},{trailerFailed=true})
        if(panel=="episodes") EpisodesPanel(title,meta,seasons,selectedSeason,{season=it},episodes,progress,chosen?.id,busy,show?.backdrop ?: show?.cover,
            trailerKey!=null,{fullTrailer=true},panelFocus,::play) {e,watched -> scope.launch {if(watched) vm.user.remove("episode",e.id) else vm.user.markWatched("episode",e.id,id,(e.durationSec ?: 0)*1000L)}}
        if(panel=="details") {
            Column(Modifier.fillMaxSize().background(NakashColors.Bg.copy(.98f)).padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column {Text(movie?.title ?: show?.title ?: "",style=MaterialTheme.typography.headlineMedium);Text(if(series) "על הסדרה" else "על הסרט",color=NakashColors.Muted)}
                    Action("חזרה",{panel=null},Modifier.focusRequester(panelFocus))
                }
                run {
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(20.dp)) {
                        item {Text((movie?.plot ?: show?.plot)?.takeIf {it.isNotBlank()} ?: tmdb?.overview ?: "אין תקציר זמין",style=MaterialTheme.typography.bodyLarge)}
                        val people=tmdb?.cast.orEmpty()
                        if(people.isNotEmpty()) item {
                            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text("שחקנים",style=MaterialTheme.typography.titleLarge)
                                LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                    items(people,key={it.name}) {c ->
                                        Column(Modifier.width(112.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                            Box(Modifier.size(96.dp).clip(androidx.compose.foundation.shape.CircleShape).background(NakashColors.S2),contentAlignment=Alignment.Center) {
                                                if(c.photo!=null) AsyncImage(c.photo,c.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                                                else Text(tv.nakash.domain.Normalizer.initials(c.name),color=NakashColors.Muted,style=MaterialTheme.typography.titleLarge)
                                            }
                                            Text(c.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                                            c.character?.let {Text(it,maxLines=1,overflow=TextOverflow.Ellipsis,color=NakashColors.Muted,style=MaterialTheme.typography.labelSmall.copy(fontSize=12.sp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                                        }
                                    }
                                }
                            }
                        }
                        else (movie?.cast ?: show?.cast)?.takeIf {it.isNotBlank()}?.let {cast -> item {Text("בהשתתפות: $cast",color=NakashColors.Muted)}}
                        if(similarMovies.isNotEmpty() || similarSeries.isNotEmpty()) item {
                            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text(if(series) "סדרות דומות" else "סרטים דומים",style=MaterialTheme.typography.titleLarge)
                                LazyRow(horizontalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=8.dp)) {
                                    items(similarMovies,key={"m${it.id}"}) {m -> tv.nakash.ui.components.PosterCard(m.title,m.year,m.poster,null,{},{nav.navigate("movie/${m.id}")},m.title,expandable=false)}
                                    items(similarSeries,key={"s${it.id}"}) {t -> tv.nakash.ui.components.PosterCard(t.title,t.year,t.cover,null,{},{nav.navigate("seriesDetail/${t.id}")},t.title,expandable=false)}
                                }
                            }
                        }
                        item {Action(if(favorite) "✓ ברשימה שלי" else "+ לרשימה שלי",{scope.launch {vm.user.toggleFavorite(if(series) "series" else "movie",id.toString())}})}
                    }
                }
            }
        }
    }
}

/**
 * "פרקים ועוד" (Netflix TV style): the title on the right with the seasons as a list (focus picks one) and
 * "טריילרים ועוד"; the season's episodes scroll on the left, each a big picture ("עונה S – פרק N" on it, the watched
 * bar, ✓) beside its name, plot and length. OK plays; long press marks watched / not watched.
 */
@Composable
private fun EpisodesPanel(title:String,meta:String,seasons:List<tv.nakash.data.local.SeasonEntity>,selectedSeason:Int,pickSeason:(Int)->Unit,
                          episodes:List<tv.nakash.data.local.EpisodeEntity>,progress:List<tv.nakash.data.local.WatchProgressEntity>,chosenId:String?,busy:Boolean,
                          fallbackImage:String?,hasTrailer:Boolean,openTrailer:()->Unit,firstFocus:FocusRequester,
                          play:(tv.nakash.data.local.EpisodeEntity)->Unit,toggleWatched:(tv.nakash.data.local.EpisodeEntity,Boolean)->Unit) {
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.95f)).padding(start=56.dp,end=40.dp,top=48.dp)) {
        Column(Modifier.weight(.34f).padding(top=40.dp,end=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.displayLarge.copy(fontSize=40.sp,lineHeight=46.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(meta,color=Color.White.copy(alpha=.8f),style=MaterialTheme.typography.titleLarge.copy(fontSize=16.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
            LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                items(seasons,key={it.id}) {s ->
                    SectionItem(if(seasons.size==1) "כל הפרקים" else s.name,"${s.episodeCount.takeIf {it>0} ?: ""} פרקים".trim(),s.number==selectedSeason,
                        onFocus={pickSeason(s.number)},click={pickSeason(s.number)})
                }
                if(hasTrailer) item("trailers") {SectionItem("טריילרים ועוד","סרטון אחד",false,onFocus={},click=openTrailer)}
            }
        }
        val anchor=episodes.indexOfFirst {it.id==chosenId}.coerceAtLeast(0)
        LazyColumn(Modifier.weight(.66f).fillMaxHeight(),state=rememberLazyListState(anchor),contentPadding=PaddingValues(top=24.dp,bottom=220.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(busy && episodes.isEmpty()) item {Text("טוענים פרקים…",color=NakashColors.Muted)}
            if(!busy && episodes.isEmpty()) item {Text("לא נמצאו פרקים בעונה הזו",color=NakashColors.Muted)}
            itemsIndexed(episodes,key={_,e -> e.id}) {i,e ->
                val saved=progress.firstOrNull {it.refId==e.id}
                val isNext=e.id==chosenId
                var focused by remember {mutableStateOf(false)}
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Surface(onClick={play(e)},onLongClick={toggleWatched(e,saved?.completed==true)},
                        modifier=Modifier.width(300.dp).height(169.dp).then(if(i==anchor) Modifier.focusRequester(firstFocus) else Modifier).onFocusChanged {f -> focused=f.isFocused},
                        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.04f),
                        colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.Tile,focusedContainerColor=NakashColors.Tile),
                        border=ClickableSurfaceDefaults.border(focusedBorder=Border(androidx.compose.foundation.BorderStroke(3.dp,Color.White),shape=RoundedCornerShape(10.dp)))) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(e.image ?: fallbackImage,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent,1f to Color.Black.copy(alpha=.75f))))
                            Text("עונה ${e.season} – פרק ${e.number}",style=MaterialTheme.typography.titleMedium.copy(fontSize=16.sp),modifier=Modifier.align(Alignment.BottomStart).padding(start=12.dp,bottom=12.dp))
                            if(saved?.completed==true) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(.6f)).padding(horizontal=8.dp,vertical=2.dp)) {Text("✓ נצפה",style=MaterialTheme.typography.labelSmall)}
                            if(saved!=null && saved.durationMs>0) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.White.copy(.25f))) {
                                    Box(Modifier.fillMaxWidth(if(saved.completed) 1f else (saved.positionMs.toFloat()/saved.durationMs).coerceIn(0f,1f)).fillMaxHeight().background(NakashColors.Live))
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            val name=e.title.takeIf {it.isNotBlank() && !it.matches(Regex(".*(פרק|Episode|E)\\s*0*${e.number}\\b.*"))}?.let {tv.nakash.util.isolate(it)} ?: "פרק ${e.number}"
                            Text(name,style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
                            if(isNext) Text(if(saved!=null && !saved.completed) "ממשיכים מכאן" else "הבא",color=NakashColors.Accent,style=MaterialTheme.typography.labelLarge)
                        }
                        e.plot?.takeIf {it.isNotBlank()}?.let {Text(it,color=Color.White.copy(alpha=if(focused) .92f else .7f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp,lineHeight=21.sp),maxLines=4,overflow=TextOverflow.Ellipsis)}
                        listOfNotNull(e.durationSec?.let {"(${it/60} דקות)"},saved?.takeIf {!it.completed && it.durationMs>0}?.let {"נותרו ${(it.durationMs-it.positionMs).coerceAtLeast(0)/60000} דק׳"}).takeIf {it.isNotEmpty()}?.let {
                            Text(it.joinToString("  ·  "),color=Color.White.copy(alpha=.7f),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp))
                        }
                    }
                }
            }
            item {Text("לחיצה ארוכה על פרק מסמנת אותו כנצפה / לא נצפה",color=NakashColors.Dim,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=4.dp))}
        }
    }
}

/** A section of the episodes panel's right column: name and count; the chosen one stays lit, focus is white. */
@Composable
private fun SectionItem(name:String,count:String,selected:Boolean,onFocus:()->Unit,click:()->Unit) {
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().height(48.dp).onFocusChanged {if(it.isFocused) onFocus()},
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.02f),
        colors=ClickableSurfaceDefaults.colors(containerColor=if(selected) Color.White.copy(alpha=.22f) else Color.Transparent,contentColor=Color.White,focusedContainerColor=Color.White,focusedContentColor=Color.Black)) {
        Row(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(name,style=MaterialTheme.typography.titleLarge.copy(fontSize=17.sp),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            Text(count,style=MaterialTheme.typography.titleLarge.copy(fontSize=15.sp),modifier=Modifier.alpha(.75f))
        }
    }
}

/** The trailer full screen with sound, in YouTube's own player. OK pauses, ◀ ▶ skip 10 s, Back closes. */
@Composable
private fun FullTrailer(key:String,close:()->Unit,failed:()->Unit) {
    val handle=remember {TrailerHandle()}
    val focus=remember {FocusRequester()}
    LaunchedEffect(Unit) {delay(100);runCatching {focus.requestFocus()}}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent {e ->
            if(e.type!=KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when(e.key) {
                Key.DirectionCenter,Key.Enter,Key.MediaPlayPause -> {handle.togglePlay();true}
                Key.DirectionLeft -> {handle.seekBy(-10);true}
                Key.DirectionRight -> {handle.seekBy(10);true}
                else -> false
            }
        }.focusable()) {
            YouTubeTrailer(key,muted=false,modifier=Modifier.fillMaxSize(),handle=handle,onError={failed();close()},onEnded=close)
        }
    }
}

private data class SimilarItem(val route:String,val title:String,val image:String?,val meta:String,val plot:String?)

/** Netflix-style menu line: icon and label; the focused one becomes a white pill. */
private val MenuRowH=46.dp
private val MenuGap=2.dp

@Composable
private fun DetailMenuItem(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,click:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Surface(onClick=click,enabled=enabled,modifier=modifier.fillMaxWidth().height(MenuRowH),
        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(25.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.02f),
        colors=ClickableSurfaceDefaults.colors(containerColor=Color.Transparent,focusedContainerColor=Color.White,contentColor=Color.White,focusedContentColor=Color.Black,disabledContainerColor=Color.Transparent,disabledContentColor=NakashColors.Muted)) {
        Row(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,contentDescription=null,modifier=Modifier.size(24.dp))
            Text(label,style=MaterialTheme.typography.titleLarge.copy(fontSize=19.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** "כותרים דומים": the title stays on the right; the list scrolls on the left with a wide image and details. */
@Composable
private fun SimilarPanel(title:String,meta:String,items:List<SimilarItem>,firstFocus:FocusRequester,open:(String)->Unit) {
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.94f)).padding(start=56.dp,end=40.dp,top=48.dp)) {
        Column(Modifier.weight(.38f).padding(top=40.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.displayLarge.copy(fontSize=40.sp,lineHeight=46.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(meta,color=Color.White.copy(alpha=.8f),style=MaterialTheme.typography.titleLarge.copy(fontSize=16.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha=.22f)).padding(horizontal=22.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Text("כותרים דומים",style=MaterialTheme.typography.titleLarge.copy(fontSize=18.sp))
                Text("${items.size} כותרים",color=Color.White.copy(alpha=.75f),style=MaterialTheme.typography.titleLarge.copy(fontSize=16.sp))
            }
        }
        LazyColumn(Modifier.weight(.62f).fillMaxHeight(),contentPadding=PaddingValues(top=24.dp,bottom=200.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            items(items.size) {i ->
                val it=items[i]
                var focused by remember {mutableStateOf(false)}
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Surface(onClick={open(it.route)},modifier=Modifier.width(300.dp).height(169.dp).then(if(i==0) Modifier.focusRequester(firstFocus) else Modifier).onFocusChanged {f -> focused=f.isFocused},
                        shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),scale=ClickableSurfaceDefaults.scale(focusedScale=1.04f),
                        colors=ClickableSurfaceDefaults.colors(containerColor=NakashColors.Tile,focusedContainerColor=NakashColors.Tile),
                        border=ClickableSurfaceDefaults.border(focusedBorder=Border(androidx.compose.foundation.BorderStroke(3.dp,Color.White),shape=RoundedCornerShape(10.dp)))) {
                        AsyncImage(it.image,it.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(it.title,style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(it.meta.isNotEmpty()) Text(it.meta,color=Color.White.copy(alpha=.75f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp))
                        it.plot?.let {p -> Text(p,color=Color.White.copy(alpha=if(focused) .9f else .65f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp,lineHeight=21.sp),maxLines=3,overflow=TextOverflow.Ellipsis)}
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


/**
 * The page backdrop. Keeps showing the picture it has while a better one (TMDB) loads, then crossfades to it, so the
 * page never flashes black; decoded at screen size.
 */
@Composable
private fun DetailBackdrop(url:String?,modifier:Modifier) {
    var shown by remember {mutableStateOf(url)}
    val ctx=androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(url) {
        if(url==null || url==shown) return@LaunchedEffect
        if(shown==null) {shown=url;return@LaunchedEffect}
        val r=coil3.SingletonImageLoader.get(ctx).execute(coil3.request.ImageRequest.Builder(ctx).data(url).size(1920,1080).build())
        if(r is coil3.request.SuccessResult) shown=url
    }
    androidx.compose.animation.Crossfade(shown,modifier,animationSpec=tween(450),label="backdrop") {u ->
        if(u!=null) AsyncImage(coil3.request.ImageRequest.Builder(ctx).data(u).size(1920,1080).build(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
    }
}

/**
 * The whole plot over the picture (opened by ▲ from the menu when the plot is cut): title, details, every line of the
 * plot, cast and director. Any key closes it (▼, Back, OK, ◄ / ►) and returns to the menu; a very long plot shrinks
 * to fit instead of scrolling.
 */
@Composable
private fun PlotOverlay(title:String,meta:String,plot:String,cast:List<String>,director:String?,close:()->Unit) {
    val focus=remember {FocusRequester()}
    var size by remember(plot) {mutableStateOf(18f)}
    var held by remember {mutableStateOf(false)}
    BackHandler(onBack=close)
    // Take the focus from the plot under it (retry until attached); if focus ever leaves, the overlay closes.
    LaunchedEffect(Unit) {repeat(10) {if(runCatching {focus.requestFocus()}.isSuccess && held) return@LaunchedEffect;kotlinx.coroutines.delay(40)}}
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha=.55f),.3f to Color.Black.copy(alpha=.8f),.55f to Color.Black.copy(alpha=.94f),1f to Color.Black.copy(alpha=.97f)))
        .onPreviewKeyEvent {e -> if(e.type==androidx.compose.ui.input.key.KeyEventType.KeyUp) {if(e.key!=androidx.compose.ui.input.key.Key.DirectionUp) close()}; true}
        .onFocusChanged {if(it.hasFocus) held=true else if(held) close()}
        .focusRequester(focus).focusable()) {
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.66f).fillMaxHeight().padding(start=56.dp,end=24.dp,top=36.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.displayLarge.copy(fontSize=36.sp,lineHeight=42.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(meta,color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.titleLarge.copy(fontSize=17.sp),maxLines=1)
            Text(plot,Modifier.weight(1f,fill=false),color=Color.White,style=MaterialTheme.typography.bodyLarge.copy(fontSize=size.sp,lineHeight=(size*1.45f).sp),
                overflow=TextOverflow.Ellipsis,onTextLayout={if(it.hasVisualOverflow && size>12f) size-=1f})
            if(cast.isNotEmpty()) Text("בהשתתפות: "+cast.take(8).joinToString(", "),color=Color.White.copy(alpha=.75f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp,lineHeight=21.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            director?.let {Text("במאי: $it",color=Color.White.copy(alpha=.75f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),maxLines=1)}
            Spacer(Modifier.height(6.dp))
            // Looks like the menu's focused button, so it is clear where any key takes you.
            Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,null,tint=Color.Black,modifier=Modifier.size(22.dp))
                Text("חזרה",color=Color.Black,style=MaterialTheme.typography.titleLarge.copy(fontSize=17.sp))
            }
        }
    }
}

/** Fades the content out at the top edge once scrolled and at the bottom edge while more follows. */
private fun Modifier.fadingEdges(scroll:androidx.compose.foundation.ScrollState,fadePx:Float)=this
    .graphicsLayer {compositingStrategy=androidx.compose.ui.graphics.CompositingStrategy.Offscreen}
    .drawWithContent {
        drawContent()
        val f=(fadePx/size.height).coerceIn(0f,.5f)
        if(scroll.value>0) drawRect(Brush.verticalGradient(0f to Color.Transparent,f to Color.Black),blendMode=androidx.compose.ui.graphics.BlendMode.DstIn)
        if(scroll.canScrollForward) drawRect(Brush.verticalGradient(1f-f to Color.Black,1f to Color.Transparent),blendMode=androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/** A round rating button; its name shows in a bubble above it while focused (Netflix TV). */
@Composable
private fun RateButton(label:String,selected:Boolean,click:()->Unit,icon:@Composable ()->Unit) {
    var focused by remember {mutableStateOf(false)}
    Box {
        Surface(onClick=click,modifier=Modifier.size(52.dp).onFocusChanged {focused=it.isFocused},
            shape=ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),scale=ClickableSurfaceDefaults.scale(focusedScale=1.1f),
            colors=ClickableSurfaceDefaults.colors(containerColor=if(selected) Color.White.copy(alpha=.24f) else Color.White.copy(alpha=.08f),contentColor=Color.White,
                focusedContainerColor=Color.White,focusedContentColor=Color.Black)) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {icon()}
        }
        if(focused) Text(label,color=Color.Black,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),maxLines=1,
            modifier=Modifier.align(Alignment.TopCenter).offset(y=(-44).dp).wrapContentSize(unbounded=true)
                .background(Color.White,RoundedCornerShape(8.dp)).padding(horizontal=12.dp,vertical=6.dp))
    }
}
