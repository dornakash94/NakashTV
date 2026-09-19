package tv.nakash.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.nakash.data.local.*
import tv.nakash.player.PlayRequest
import tv.nakash.ui.theme.NakashColors

@Composable
fun DetailScreen(nav: NavHostController, id: Int, series: Boolean, vm: LibraryViewModel = hiltViewModel()) {
    val movie by remember(id, series) { if (series) flowOf<MovieEntity?>(null) else vm.catalog.movie(id) }.collectAsState(null)
    val show by remember(id, series) { if (series) vm.catalog.series(id) else flowOf<SeriesEntity?>(null) }.collectAsState(null)
    val seasons by remember(id, series) { if(series) vm.catalog.seasons(id) else flowOf(emptyList()) }.collectAsState(emptyList())
    var season by remember(id) { mutableStateOf<Int?>(null) }
    val selectedSeason = season ?: seasons.firstOrNull()?.number ?: 1
    val episodes by remember(id, selectedSeason) { vm.catalog.episodes(id, selectedSeason) }.collectAsState(emptyList())
    val progress by remember(id) { vm.user.progressForSeries(id) }.collectAsState(emptyList())
    val favorite by remember(id, series) { vm.user.isFavorite(if(series) "series" else "movie", id.toString()) }.collectAsState(false)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var resumeEpisode by remember(id) { mutableStateOf<EpisodeEntity?>(null) }
    var busy by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var resume by remember(id) { mutableStateOf<WatchProgressEntity?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        busy = true; error = null
        runCatching { if(series) vm.catalog.ensureSeriesDetail(id, attempt > 0) else vm.catalog.ensureMovieInfo(id) }
            .onFailure { error = "לא ניתן לטעון את הפרקים כרגע. נסה שוב." }
        resume = if(series) vm.user.latestForSeries(id) else vm.user.progress("movie", id.toString())
        if(series && season == null && resume != null) {
            for (s in vm.catalog.seasons(id).first()) {
                val eps = vm.catalog.episodes(id,s.number).first()
                if(eps.any { it.id == resume?.refId }) { resumeEpisode=eps.first { it.id==resume?.refId };season = s.number; break }
            }
        }
        busy = false
    }
    fun playEpisode(e: EpisodeEntity, start: Long = 0) {
        vm.player.play(PlayRequest.Episode(e.id,e.seriesId,e.season,e.number,e.title,e.containerExt,start))
        nav.navigate("player")
    }
    val title = movie?.title ?: show?.title ?: "טוענים…"
    Box(Modifier.fillMaxSize()) {
        AsyncImage(movie?.backdrop ?: show?.backdrop ?: movie?.poster ?: show?.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NakashColors.Bg.copy(.35f), NakashColors.Bg))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,NakashColors.Bg.copy(.9f)))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Spacer(Modifier.height(16.dp)) }
            item { Text(title, style = MaterialTheme.typography.displayLarge.copy(fontSize=32.sp,lineHeight=38.sp),modifier=Modifier.fillMaxWidth(.65f)) }
            item { Text(listOfNotNull((movie?.year ?: show?.year)?.toString(), (movie?.rating ?: show?.rating)?.let { "★ %.1f".format(it) }, movie?.runtimeMin?.let { "$it דקות" }, (movie?.genres ?: show?.genres)?.replace(",", " · ")).filter { it.isNotBlank() }.joinToString("  ·  "), color = NakashColors.Accent) }
            item {
                var expanded by remember { mutableStateOf(false) }
                val plot=movie?.plot ?: show?.plot ?: "אין תקציר זמין"
                Text(plot,style=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp,lineHeight=22.sp),modifier=Modifier.fillMaxWidth(.65f),maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if(plot.length>180) Action(if(expanded) "פחות" else "קרא עוד",{expanded=!expanded})
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
                    if (!series && movie != null) {
                        item { Action(if(resume != null && resume?.completed == false) "המשך צפייה" else "צפייה", { val m=movie!!; vm.player.play(PlayRequest.Movie(m.id,m.title,m.containerExt,resume?.takeUnless { it.completed }?.positionMs ?: 0));nav.navigate("player") }) }
                        if(resume != null) item { Action("צפייה מהתחלה", { val m=movie!!;vm.player.play(PlayRequest.Movie(m.id,m.title,m.containerExt));nav.navigate("player") }) }
                    }
                    if(series && episodes.isNotEmpty()) item { Action(if(resume != null) "המשך צפייה" else "התחל צפייה", {
                        scope.launch {
                            val last = resumeEpisode
                            val next = if(last != null && resume?.completed == true) vm.catalog.nextEpisode(id,last.season,last.number) else last
                            val chosen = next ?: episodes.first()
                            playEpisode(chosen, resume?.takeIf { it.refId == chosen.id && !it.completed }?.positionMs ?: 0)
                        }
                    }) }
                    movie?.trailer?.takeIf { it.isNotBlank() }?.let { trailer ->
                        item { Action("טריילר", {
                            val uri = if(trailer.matches(Regex("[A-Za-z0-9_-]{11}"))) "https://www.youtube.com/watch?v=$trailer" else trailer
                            if(uri.startsWith("https://") || uri.startsWith("http://")) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(uri))) }.onFailure { error="אין אפליקציה לפתיחת הטריילר במכשיר" }
                        }) }
                    }
                    item { Action(if(favorite) "✓ ברשימה שלי" else "+ לרשימה שלי", { scope.launch { vm.user.toggleFavorite(if(series) "series" else "movie",id.toString()) } }) }
                }
            }
            (movie?.cast ?: show?.cast)?.takeIf { it.isNotBlank() }?.let { cast -> item { Text("בהשתתפות: $cast", color = NakashColors.Muted) } }
            if(!series) error?.let { item { Text(it,color=NakashColors.Live) } }
            if(series) {
                if(busy) item { Text("טוענים פרקים…", color = NakashColors.Muted) }
                error?.let { item { Text(it, color = NakashColors.Live); Action("ניסיון נוסף", { attempt++ }) } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) { items(seasons) { s -> Action(if(s.number == selectedSeason) "✓ ${s.name}" else s.name, { season = s.number }) } } }
                if (!busy && error == null && episodes.isEmpty()) item { Text("לא נמצאו פרקים בעונה הזו", color = NakashColors.Muted) }
                items(episodes, key = { it.id }) { e ->
                    val p=progress.firstOrNull { it.refId == e.id }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("${if(p?.completed == true) "✓ " else ""}${e.number}. ${e.title}", { playEpisode(e,p?.takeUnless { it.completed }?.positionMs ?: 0) }, Modifier.weight(1f))
                            Action(if(p?.completed == true) "לא נצפה" else "סמן כנצפה", { scope.launch { if(p?.completed == true) vm.user.remove("episode",e.id) else vm.user.markWatched("episode",e.id,id,(e.durationSec ?: 0)*1000L) } })
                        }
                        e.plot?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, color = NakashColors.Muted) }
                    }
                }
            }
        }
    }
}
