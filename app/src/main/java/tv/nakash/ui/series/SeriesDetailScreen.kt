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
    var videoView by remember {mutableStateOf<PlayerView?>(null)}
    val heroFocus=remember {FocusRequester()}
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
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent,.40f to Color.Transparent,.72f to Color.Black.copy(alpha=.62f),1f to Color.Black.copy(alpha=.86f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.6f to Color.Transparent,1f to Color.Black.copy(alpha=.55f))))
        if(panel==null) Column(Modifier.align(Alignment.TopStart).fillMaxWidth(.44f).padding(start=56.dp,end=8.dp,top=96.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.displayLarge.copy(fontSize=46.sp,lineHeight=52.sp),maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(meta,color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.titleLarge.copy(fontSize=17.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Text((movie?.plot ?: show?.plot)?.takeIf {it.isNotBlank()} ?: tmdb?.overview ?: "",style=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,lineHeight=24.sp),maxLines=3,overflow=TextOverflow.Ellipsis)
            val castNames=tmdb?.cast?.take(3)?.map {it.name}?.takeIf {it.isNotEmpty()} ?: (movie?.cast ?: show?.cast)?.split(',')?.map {it.trim()}?.filter {it.isNotEmpty()}?.take(3).orEmpty()
            if(castNames.isNotEmpty()) Text("בהשתתפות: "+castNames.joinToString(", "),color=Color.White.copy(alpha=.72f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
            movie?.director?.takeIf {it.isNotBlank() && it.split(',').size<=2}?.let {Text("במאי: $it",color=Color.White.copy(alpha=.72f),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),maxLines=1,overflow=TextOverflow.Ellipsis)}
            Spacer(Modifier.height(8.dp))
            val savedPosition=if(series) (progress.firstOrNull {it.refId==chosen?.id} ?: resume?.takeIf {it.refId==chosen?.id}) else resume
            val canResume=savedPosition!=null && !savedPosition.completed && savedPosition.positionMs>0
            val playLabel=when {
                series -> chosen?.let {(if(canResume) "המשך צפייה · " else "הפעל את ")+"עונה ${it.season} – פרק ${it.number}"} ?: "הפעל"
                canResume -> "המשך צפייה · נותרו ${((savedPosition!!.durationMs-savedPosition.positionMs).coerceAtLeast(0)/60000)} דק׳"
                else -> "הפעל"
            }
            Column(Modifier.width(400.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                DetailMenuItem(androidx.compose.material.icons.Icons.Filled.PlayArrow,playLabel,::playChosen,Modifier.focusRequester(heroFocus),enabled=chosen!=null || movie!=null)
                if(trailerKey!=null) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.Theaters,"טריילר",{fullTrailer=true})
                if(series) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.VideoLibrary,"פרקים נוספים",{panel="episodes"})
                if(similar.isNotEmpty()) DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.GridView,"כותרים דומים",{panel="similar"})
                DetailMenuItem(if(favorite) androidx.compose.material.icons.Icons.Filled.Check else androidx.compose.material.icons.Icons.Filled.Add,if(favorite) "ברשימה שלי" else "הוסף לרשימה שלי",{scope.launch {vm.user.toggleFavorite(if(series) "series" else "movie",id.toString())}})
                DetailMenuItem(androidx.compose.material.icons.Icons.Outlined.Info,"פרטים ושחקנים",{panel="details"})
            }
            error?.let {Text(it,color=NakashColors.Live);Action("ניסיון נוסף",{attempt++})}
            if(series && !busy && error==null && chosen==null) Text("אין פרקים זמינים כרגע",color=NakashColors.Muted)
        }
        if(panel=="similar") SimilarPanel(title,meta,similar,panelFocus) {nav.navigate(it)}
        if(fullTrailer && trailerKey!=null) FullTrailer(trailerKey,{fullTrailer=false;interaction++},{trailerFailed=true})
        if(panel=="episodes" || panel=="details") {
            Column(Modifier.fillMaxSize().background(NakashColors.Bg.copy(.98f)).padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column {Text(movie?.title ?: show?.title ?: "",style=MaterialTheme.typography.headlineMedium);Text(if(panel=="episodes") "פרקים ועונות" else if(series) "על הסדרה" else "על הסרט",color=NakashColors.Muted)}
                    Action("חזרה",{panel=null},Modifier.focusRequester(panelFocus))
                }
                if(panel=="details") {
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
@Composable
private fun DetailMenuItem(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,click:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Surface(onClick=click,enabled=enabled,modifier=modifier.fillMaxWidth().height(50.dp),
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
