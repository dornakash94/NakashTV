package tv.nakash.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.foundation.lazy.itemsIndexed as columnItemsIndexed
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.MovieEntity
import tv.nakash.data.local.SeriesEntity
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.player.PlayRequest
import tv.nakash.player.PlayerController
import tv.nakash.ui.components.ChannelCard
import tv.nakash.ui.components.NetflixRow
import tv.nakash.ui.components.PosterCard
import tv.nakash.ui.components.ProgressBar
import tv.nakash.ui.theme.NakashColors
import tv.nakash.util.fmtTime
import javax.inject.Inject

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(nav: NavHostController, vm: HomeViewModel = hiltViewModel(), playerVm: PlayerEntry = hiltViewModel()) {
    val rows by vm.rows.collectAsState()
    val hero by vm.hero.collectAsState()
    val nowMap by vm.nowMap.collectAsState()
    val nowSec = System.currentTimeMillis() / 1000
    val rowsFocus=remember { FocusRequester() }
    val shelfState=rememberLazyListState()
    var focusedShelf by rememberSaveable { mutableStateOf<String?>(null) }
    val focusedShelfIndex=rows.indexOfFirst { it.key==focusedShelf }
    LaunchedEffect(focusedShelfIndex) {
        if(focusedShelfIndex>=0) shelfState.animateScrollToItem(focusedShelfIndex)
    }
    val manualShelfScroll=remember { object:BringIntoViewSpec {
        override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float)=0f
    } }
    LaunchedEffect(rows.isNotEmpty()) {
        // The requester sits on the first card, which the lazy list may not have composed yet — retry briefly.
        if(rows.any { it.items.isNotEmpty() }) repeat(8) { kotlinx.coroutines.delay(150); if(runCatching { rowsFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    fun activate() { when(val h=hero) {
        is HeroItem.Channel -> { playerVm.play(PlayRequest.Live(h.channel));nav.navigate("player") }
        is HeroItem.Movie -> nav.navigate("movie/${h.movie.id}")
        is HeroItem.Series -> nav.navigate("seriesDetail/${h.series.id}")
        else -> {}
    } }
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }
    LaunchedEffect(rows) { if(hero == null) rows.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()?.let { vm.onFocus(it) }; vm.warmNow(rows.flatMap { it.items }.filterIsInstance<ChannelEntity>()) }

    val toast by vm.toast.collectAsState()
    val hasFrame by vm.previewFrame.collectAsState()
    fun openContinue(item: ContinueItem) = when (item.progress.kind) {
        "movie" -> nav.navigate("movie/${item.progress.refId}")
        "episode" -> item.progress.seriesId?.let { nav.navigate("seriesDetail/$it") }
        "channel" -> playerVm.playChannelId(item.progress.refId.toInt()) { nav.navigate("player") }
        else -> Unit
    }
    val shelves = rows.filter { it.items.isNotEmpty() }.map { row ->
        tv.nakash.ui.components.RowShelf(row.key, row.title, row.subtitle, row.items.map { item ->
            when (item) {
                is ChannelEntity -> {
                    val now = nowMap[item.epgChannelId]
                    tv.nakash.ui.components.RowCard("c${item.id}", tv.nakash.ui.components.CardKind.CHANNEL, item.displayName, channel = item,
                        nowTitle = now?.title, meta = listOfNotNull("ערוץ ${item.number}", now?.let { "${fmtTime(it.start)}–${fmtTime(it.end)}" }).joinToString("  ·  "), plot = now?.description,
                        progress = now?.takeIf { it.end > it.start }?.let { ((nowSec - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f) },
                        onFocus = { vm.onFocus(item) }, onClick = { playerVm.play(PlayRequest.Live(item)); nav.navigate("player") }, onLongClick = { vm.toggleFavorite(item) })
                }
                is MovieEntity -> tv.nakash.ui.components.RowCard("m${item.id}", tv.nakash.ui.components.CardKind.POSTER, item.title, item.poster, item.backdrop,
                    listOfNotNull(item.year?.toString(), item.genres.split(',').firstOrNull()?.takeIf { it.isNotBlank() }).joinToString("  ·  "), item.plot,
                    trailer = { vm.tmdb.movie(item.tmdbId, item.title, item.year)?.trailerKey }, onFocus = { vm.onFocus(item) }, onClick = { nav.navigate("movie/${item.id}") })
                is SeriesEntity -> tv.nakash.ui.components.RowCard("s${item.id}", tv.nakash.ui.components.CardKind.POSTER, item.title, item.cover, item.backdrop,
                    listOfNotNull(item.year?.toString(), item.genres.split(',').firstOrNull()?.takeIf { it.isNotBlank() }).joinToString("  ·  "), item.plot,
                    trailer = { vm.tmdb.tv(item.title, item.year)?.trailerKey }, onFocus = { vm.onFocus(item) }, onClick = { nav.navigate("seriesDetail/${item.id}") })
                is ContinueItem -> {
                    val p = item.progress
                    tv.nakash.ui.components.RowCard("w${p.key}", tv.nakash.ui.components.CardKind.WIDE, item.title, wide = item.image,
                        label = if (p.durationMs > 0) "נותרו ${((p.durationMs - p.positionMs).coerceAtLeast(0) / 60000)} דק׳" else null,
                        progress = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else null,
                        onFocus = { vm.onFocus(item) }, onClick = { openContinue(item) })
                }
                else -> tv.nakash.ui.components.RowCard(keyOf(item), tv.nakash.ui.components.CardKind.WIDE, "", onClick = {})
            }
        })
    }
    Box(Modifier.fillMaxSize()) {
        if (rows.all { it.items.isEmpty() }) Column(Modifier.padding(top = 90.dp, start = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ברוכים הבאים ל־NakashTV", style = MaterialTheme.typography.headlineMedium)
            Text("התכנים נטענים מהספק. אם הספרייה נשארת ריקה, אפשר לרענן אותה בהגדרות.", color = NakashColors.Muted)
            tv.nakash.ui.library.Action("מעבר להגדרות", { nav.navigate("settings") })
        } else tv.nakash.ui.components.NetflixRowsPage(shelves, vm.previewPlayer, hasFrame, rowsFocus, restoreKey = "home")
        toast?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2_200); vm.clearToast() }
            Text(msg, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.BottomStart).padding(28.dp).background(NakashColors.S1.copy(alpha = .95f), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

private fun keyOf(it: Any) = when (it) { is ChannelEntity -> "c${it.id}"; is MovieEntity -> "m${it.id}"; is SeriesEntity -> "s${it.id}"; is ContinueItem -> "w${it.progress.key}"; else -> it.hashCode().toString() }

/**
 * Hero: for a channel, the live preview fills the whole area under one gradient (no seam); text on the right.
 * For movies/series: backdrop (or blurred cover) + metadata. Crossfade only — no vertical motion.
 */
@Composable
private fun Hero(hero: HeroItem?, vm: HomeViewModel, height: androidx.compose.ui.unit.Dp, play: () -> Unit, guide: () -> Unit) {
    val muted by vm.previewMuted.collectAsState()
    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        if(hero is HeroItem.Channel) AndroidView(factory={ctx -> (android.view.LayoutInflater.from(ctx).inflate(tv.nakash.R.layout.player_preview,null,false) as PlayerView).apply {
            useController=false;isFocusable=false;descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            player=vm.previewPlayer;resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }},modifier=Modifier.fillMaxSize(),onRelease={it.player=null})
        Crossfade(hero, label = "hero") { h ->
            Box(Modifier.fillMaxSize()) {
                when (h) {
                    is HeroItem.Channel -> {}
                    is HeroItem.Movie -> AsyncImage(h.movie.backdrop ?: h.movie.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (h.movie.backdrop == null) .35f else 1f)
                    is HeroItem.Series -> AsyncImage(h.series.backdrop ?: h.series.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (h.series.backdrop == null) .35f else 1f)
                    null -> {}
                }
                // one fade layer above the media: opaque toward the text (right, RTL) and toward the rows (bottom)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent, .45f to NakashColors.Bg.copy(.2f), .7f to NakashColors.Bg.copy(.85f), 1f to NakashColors.Bg)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to NakashColors.Bg)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 78.dp).fillMaxWidth(0.56f)) {
                    when (h) {
                        is HeroItem.Channel -> {
                            Text("● שידור חי · ${h.channel.displayName}", style = MaterialTheme.typography.labelLarge.copy(fontSize=12.sp), color = NakashColors.Accent)
                            Text(h.now?.title ?: h.channel.displayName, style = MaterialTheme.typography.displayLarge.copy(fontSize=32.sp,lineHeight=36.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Meta(listOfNotNull(h.now?.let { "${fmtTime(it.start)}–${fmtTime(it.end)}" }, h.next?.let { "הבא: ${it.title}" }, h.channel.archiveDays.takeIf { it > 0 }?.let { "ארכיון $it ימים" }))
                            Text(h.now?.description ?: "ערוץ זה משדר ללא לוח שידורים.", style = MaterialTheme.typography.bodyLarge.copy(fontSize=14.sp,lineHeight=19.sp), color = Color(0xFFD3D4D8), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            h.now?.let { Spacer(Modifier.height(6.dp)); Box(Modifier.width(420.dp)) { ProgressBar(((System.currentTimeMillis() / 1000 - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f), NakashColors.Accent, 4) } }
                        }
                        is HeroItem.Movie -> {
                            Text(if (h.progress != null) "המשך צפייה" else "חדש בשירות", style = MaterialTheme.typography.labelLarge.copy(fontSize=12.sp), color = NakashColors.Accent)
                            Text(h.movie.title, style = MaterialTheme.typography.displayLarge.copy(fontSize=32.sp,lineHeight=36.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Meta(listOfNotNull(h.movie.year?.toString(), h.movie.genres.replace(",", ", ").ifBlank { null }, h.movie.runtimeMin?.let { "${it / 60}:${"%02d".format(it % 60)}" }, h.movie.rating?.let { "★ $it" }, h.movie.country))
                            h.movie.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize=14.sp,lineHeight=19.sp), color = Color(0xFFD3D4D8), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        is HeroItem.Series -> {
                            Text(if (h.progress != null) "המשך צפייה" else "פרקים חדשים", style = MaterialTheme.typography.labelLarge.copy(fontSize=12.sp), color = NakashColors.Accent)
                            Text(h.series.title, style = MaterialTheme.typography.displayLarge.copy(fontSize=32.sp,lineHeight=36.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Meta(listOfNotNull(h.series.year?.toString(), h.series.genres.replace(",", ", ").ifBlank { null }, h.series.rating?.let { "★ $it" }))
                            h.series.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize=14.sp,lineHeight=19.sp), color = Color(0xFFD3D4D8), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        null -> {}
                    }
                    if(h!=null) Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        HeroButton(if(h is HeroItem.Channel) "▶  צפה עכשיו" else "▶  פרטים וצפייה",true,play)
                        if(h is HeroItem.Channel) HeroButton("לוח שידורים",false,guide)
                    }
                }
            }
        }
        if(hero is HeroItem.Channel) Box(Modifier.align(Alignment.TopEnd).padding(top = tv.nakash.ui.nav.NavBarHeight + 8.dp, end = 20.dp)) {
            androidx.tv.material3.Surface(onClick=vm::togglePreviewMute,
                shape=androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                colors=androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor=Color.Black.copy(.55f),focusedContainerColor=NakashColors.S3)) {
                androidx.tv.material3.Icon(if(muted) androidx.compose.material.icons.Icons.Default.VolumeOff else androidx.compose.material.icons.Icons.Default.VolumeUp,if(muted) "הפעל קול לתצוגה המקדימה" else "השתק תצוגה מקדימה",Modifier.padding(9.dp).size(18.dp))
            }
        }
    }
}

@Composable
private fun Meta(parts: List<String>) {
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, p -> if (i > 0) Text("|", color = NakashColors.Dim); Text(p, style = MaterialTheme.typography.titleLarge.copy(fontSize=12.sp,lineHeight=16.sp), color = NakashColors.Muted, maxLines = 1) }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, onFocus: () -> Unit, onClick: () -> Unit) {
    val p=item.progress
    val fraction=if(p.durationMs>0) (p.positionMs.toFloat()/p.durationMs).coerceIn(0f,1f) else 0f
    androidx.tv.material3.Surface(onClick=onClick,
        modifier=Modifier.width(200.dp).height(112.dp).then(Modifier.onFocusChanged { if(it.isFocused) onFocus() }),
        shape=androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        colors=androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor=NakashColors.Tile,focusedContainerColor=NakashColors.S2),
        scale=androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale=1.05f)) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(item.image,null,Modifier.fillMaxSize(),contentScale=if(p.kind=="channel") ContentScale.Fit else ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(.95f)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp)) {
                Text(item.title,style=MaterialTheme.typography.titleLarge.copy(fontSize=12.sp,lineHeight=15.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(p.kind=="channel") "חזרה לשידור החי" else "נותרו ${(p.durationMs-p.positionMs).coerceAtLeast(0)/60000} דקות",color=NakashColors.Muted,style=MaterialTheme.typography.labelLarge.copy(fontSize=10.sp,lineHeight=13.sp))
                if(p.durationMs>0) { Spacer(Modifier.height(6.dp));ProgressBar(fraction,NakashColors.Live) }
            }
        }
    }
}

/** Small entry-point VM so screens can start playback without holding the controller directly. */
@dagger.hilt.android.lifecycle.HiltViewModel
class PlayerEntry @Inject constructor(private val controller: PlayerController, private val catalog: tv.nakash.data.repo.CatalogRepository) : androidx.lifecycle.ViewModel() {
    fun play(req: PlayRequest) = viewModelScope.launch { if(req is PlayRequest.Live) controller.zapChannels.value=catalog.channels().first();controller.play(req) }
    fun playChannelId(id: Int, then: () -> Unit) = viewModelScope.launch {
        controller.zapChannels.value=catalog.channels().first()
        catalog.channel(id)?.let { controller.play(PlayRequest.Live(it)); then() }
    }
}

@Composable
private fun HeroButton(label:String,primary:Boolean,onClick:()->Unit) {
    androidx.tv.material3.Surface(onClick=onClick,
        shape=androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        colors=androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor=if(primary) Color.White.copy(alpha=.24f) else Color.White.copy(alpha=.12f),contentColor=Color.White,focusedContainerColor=Color.White,focusedContentColor=Color.Black)) {
        Text(label,Modifier.padding(horizontal=18.dp,vertical=9.dp),style=MaterialTheme.typography.labelLarge.copy(fontSize=15.sp))
    }
}
