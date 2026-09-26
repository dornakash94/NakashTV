package tv.nakash.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.nakash.ui.components.PosterCard
import tv.nakash.ui.components.TrailerStage
import tv.nakash.ui.components.TrailerTarget
import tv.nakash.ui.theme.NakashColors

private data class ShelfTitle(val id: Int, val title: String, val year: Int?, val image: String?, val backdrop: String?, val plot: String?, val genres: String, val rating: Double?,
                              val categories: String, val tmdbId: Int? = null, val progress: Float? = null, val resumeLabel: String? = null)
private data class Shelf(val key: String, val title: String, val items: List<ShelfTitle>)

private val CardH = 264.dp
private val PosterW = 176.dp
private val WideW = 469.dp          // 16:9 at the poster's height
private const val CardMs = 320

/**
 * Movies / series browse, Netflix-TV style:
 *  - a billboard card for a random title among the 10 newest, "הפעל" (straight to the player) and "מידע נוסף";
 *    after 3 s its official trailer plays in the card while the buttons stay;
 *  - the page background takes the billboard image's colour;
 *  - rows: the focused title widens to 16:9 and, after a short dwell, plays its trailer in place.
 * One YouTube player serves the whole screen (TrailerStage) and swaps videos without reloading.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DiscoverScreen(nav: NavHostController, kind: String, vm: LibraryViewModel = hiltViewModel()) {
    val seriesMode = kind == "series"
    // Only the catalog this tab shows (the other one is thousands of rows held for nothing).
    val movies by remember(seriesMode) { if (seriesMode) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.movies }.collectAsState(emptyList())
    val series by remember(seriesMode) { if (seriesMode) vm.series else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    val saved by remember(seriesMode) { vm.user.libraryContinueWatching(seriesMode) }.collectAsState(emptyList())
    val categories by remember(kind) { vm.catalog.categories(if (seriesMode) "series" else "vod") }.collectAsState(emptyList())
    val loading by vm.loading.collectAsState()
    val status by vm.status.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val tmdbKey by vm.tmdbPrefs.key.collectAsState()
    var selectedCategory by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var focusedShelf by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var focusedId by remember(kind) { mutableStateOf<Int?>(null) }
    var billboardFocused by remember(kind) { mutableStateOf(true) }
    val list = rememberLazyListState()
    val rowState = rememberSaveableStateHolder()
    val endFocus = remember { mutableMapOf<String, FocusRequester>() }
    var returnToShelf by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun closeCategory() { returnToShelf = selectedCategory; selectedCategory = null }
    BackHandler(selectedCategory != null) { closeCategory() }

    val titles by produceState(emptyList<ShelfTitle>(), movies, series, kind) {
        value = withContext(Dispatchers.Default) {
            if (seriesMode) series.map { ShelfTitle(it.id, it.title, it.year, it.cover, it.backdrop, it.plot, it.genres, it.rating, it.categoryIds) }
            else movies.map { ShelfTitle(it.id, it.title, it.year, it.poster, it.backdrop, it.plot, it.genres, it.rating, it.categoryIds, it.tmdbId) }
        }
    }
    val continued by produceState(emptyList<ShelfTitle>(), titles, saved) {
        value = withContext(Dispatchers.Default) {
            val indexed = titles.associateBy { it.id }
            saved.distinctBy { if (seriesMode) it.seriesId?.toString() ?: it.refId else it.refId }.mapNotNull { p ->
                val id = if (seriesMode) p.seriesId else p.refId.toIntOrNull()
                indexed[id]?.let { title ->
                    val episode = if (seriesMode) vm.catalog.episodeById(p.refId) else null
                    title.copy(progress = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f,
                        resumeLabel = if (episode != null) "עונה ${episode.season} · פרק ${episode.number}" else "נותרו ${(p.durationMs - p.positionMs).coerceAtLeast(0) / 60000} דקות")
                }
            }
        }
    }
    val shelves by produceState(emptyList<Shelf>(), titles, categories, continued, favorites) {
        value = withContext(Dispatchers.Default) {
            buildList {
                if (continued.isNotEmpty()) add(Shelf("continue", "המשך צפייה", continued.take(12)))
                val indexed = titles.associateBy { it.id }
                val mine = favorites.filter { it.kind == (if (seriesMode) "series" else "movie") }.sortedByDescending { it.addedAt }.mapNotNull { indexed[it.refId.toIntOrNull()] }
                if (mine.isNotEmpty()) add(Shelf("mylist", "הרשימה שלי", mine.take(12)))
                if (titles.isNotEmpty()) add(Shelf("new", if (seriesMode) "פרקים חדשים" else "חדש בשירות", titles.take(12)))
                val rated = titles.filter { (it.rating ?: 0.0) >= 7.0 }.sortedByDescending { it.rating }.take(12)
                if (rated.isNotEmpty()) add(Shelf("rated", "שווה לראות", rated))
                // One pass over the titles (each title's category list split once), not one pass per category.
                val byCategory = HashMap<String, MutableList<ShelfTitle>>()
                for (t in titles) for (c in t.categories.split(',')) { val l = byCategory.getOrPut(c) { ArrayList(12) }; if (l.size < 12) l += t }
                categories.forEach { cat ->
                    val subset = byCategory[cat.id.toString()].orEmpty()
                    if (subset.isNotEmpty()) add(Shelf("cat${cat.id}", cat.name, subset))
                }
            }
        }
    }
    val filtered by produceState(emptyList<ShelfTitle>(), selectedCategory, titles, continued, favorites) {
        if (selectedCategory == null) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) {
            when (selectedCategory) {
                "continue" -> continued
                "mylist" -> favorites.filter { it.kind == (if (seriesMode) "series" else "movie") }.sortedByDescending { it.addedAt }.mapNotNull { f -> titles.firstOrNull { it.id == f.refId.toIntOrNull() } }
                "new" -> titles
                "rated" -> titles.filter { (it.rating ?: 0.0) >= 7.0 }.sortedByDescending { it.rating }
                else -> titles.filter { selectedCategory?.removePrefix("cat") in it.categories.split(',') }
            }
        }
    }

    // Billboard: one of the 10 newest, picked once per visit.
    val seed = rememberSaveable(kind) { (0..9).random() }
    val billboard = titles.take(10).let { it.getOrNull(seed % it.size.coerceAtLeast(1)) }

    // ---- trailers: TMDB key → YouTube key, cached per title ----
    // One TMDB lookup per title gives its trailer and a sharp wide image (the provider's are small, and its movie
    // list has no wide image at all).
    val extras = remember(kind) { mutableStateMapOf<Int, tv.nakash.domain.TmdbDetails?>() }
    suspend fun extrasFor(t: ShelfTitle): tv.nakash.domain.TmdbDetails? {
        if (tmdbKey == null) return null
        if (extras.containsKey(t.id)) return extras[t.id]
        val d = runCatching { if (seriesMode) vm.tmdb.tv(t.title, t.year) else vm.tmdb.movie(t.tmdbId, t.title, t.year) }.getOrNull()
        extras[t.id] = d
        return d
    }
    suspend fun trailerFor(t: ShelfTitle): String? = extrasFor(t)?.trailerKey
    fun wideOf(t: ShelfTitle): String? = extras[t.id]?.backdrop ?: t.backdrop
    // 1280 px is sharp on a 1080p TV; the "original" file is often 4K and several MB (slow to fetch and decode).
    fun bigOf(t: ShelfTitle): String? = extras[t.id]?.backdrop ?: t.backdrop ?: t.image
    LaunchedEffect(billboard?.id, tmdbKey) { billboard?.let { extrasFor(it) } }
    val billboardImage = billboard?.let { bigOf(it) }
    val billboardColor by produceState(Color(0xFF1B1D22), billboardImage) { value = tv.nakash.ui.components.dominantColor(ctx, billboardImage) ?: value }
    val pageTint = billboardColor
    var stageOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var billboardBounds by remember { mutableStateOf<Rect?>(null) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    // Positions update every frame while cards widen or the page scrolls: kept outside Compose state and copied in
    // only when a trailer is due (state writes there recomposed the whole screen every frame).
    val liveBounds = remember { arrayOfNulls<Rect>(2) }   // 0 = billboard, 1 = focused card
    var target by remember { mutableStateOf<TrailerTarget?>(null) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var wantKey by remember { mutableStateOf<Pair<String, Boolean>?>(null) }     // key, true = billboard
    LaunchedEffect(billboardFocused, focusedId, billboard?.id, tmdbKey, selectedCategory) {
        wantKey = null
        if (selectedCategory != null) return@LaunchedEffect
        if (billboardFocused) { val b = billboard ?: return@LaunchedEffect; delay(3_000); trailerFor(b)?.let { billboardBounds = liveBounds[0]; wantKey = it to true } }
        else { val t = titles.firstOrNull { it.id == focusedId } ?: return@LaunchedEffect; delay(350); trailerFor(t)?.let { cardBounds = liveBounds[1]; wantKey = it to false } }
    }
    target = wantKey?.let { (k, onBillboard) -> (if (onBillboard) billboardBounds else cardBounds)?.let { TrailerTarget(k, it.translate(-stageOrigin), if (onBillboard) 22f else 10f) } }
    val billboardPlaying = target != null && wantKey?.second == true && playingKey == target?.key
    val cardPlaying = target != null && wantKey?.second == false && playingKey == target?.key

    // Scroll: the billboard at the top, or the focused row just under the nav bar with the next row peeking.
    val focusedShelfIndex = shelves.indexOfFirst { it.key == focusedShelf }
    LaunchedEffect(focusedShelfIndex, billboardFocused, selectedCategory) {
        if (selectedCategory != null) return@LaunchedEffect
        if (billboardFocused) list.animateScrollToItem(0) else if (focusedShelfIndex >= 0) list.animateScrollToItem(focusedShelfIndex + 1, 0)
    }
    // Pre-load the wide images of the row you are on, so a card widens without an image popping in.
    // Look up the trailers of the whole row you are on (and the next one) ahead of time, so a card starts playing
    // right after the short dwell instead of waiting for TMDB.
    LaunchedEffect(focusedShelf, shelves, tmdbKey) {
        val i = shelves.indexOfFirst { it.key == focusedShelf }
        (listOfNotNull(billboard) + (shelves.getOrNull(i)?.items.orEmpty()) + (shelves.getOrNull(i + 1)?.items.orEmpty().take(6))).forEach { trailerFor(it) }
    }
    LaunchedEffect(focusedShelf, shelves) {
        shelves.firstOrNull { it.key == focusedShelf }?.items?.forEach { prefetch(ctx, wideOf(it)) }
    }
    val manualScroll = remember { object : BringIntoViewSpec { override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f } }
    val playFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(selectedCategory, titles.isNotEmpty(), filtered.isNotEmpty()) {
        if (titles.isEmpty()) return@LaunchedEffect
        delay(200)
        runCatching { if (selectedCategory != null) gridFocus.requestFocus() else (returnToShelf?.let { endFocus[it] } ?: playFocus).requestFocus() }
        returnToShelf = null
    }
    LaunchedEffect(kind) { if (if (seriesMode) vm.series.value.isEmpty() else vm.movies.value.isEmpty()) vm.refresh(seriesMode) }
    fun open(item: ShelfTitle) { nav.navigate(if (seriesMode) "seriesDetail/${item.id}" else "movie/${item.id}") }
    fun playNow(item: ShelfTitle) { scope.launch { if (vm.playTitle(item.id, seriesMode)) nav.navigate("player") else open(item) } }

    Box(Modifier.fillMaxSize().background(NakashColors.Bg)) {
        if (selectedCategory != null) {
            Column(Modifier.fillMaxSize().padding(top = tv.nakash.ui.nav.NavBarHeight)) {
                tv.nakash.ui.components.CategoryHeading(shelves.firstOrNull { it.key == selectedCategory }?.title ?: "כל התכנים", ::closeCategory, "${filtered.size} כותרים")
                LazyVerticalGrid(GridCells.Adaptive(142.dp), Modifier.weight(1f).focusGroup(), contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 10.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(filtered, key = { _, it -> it.id }) { i, item -> Box(if (i == 0) Modifier.focusRequester(gridFocus).focusGroup() else Modifier) {
                        PosterCard(item.title, item.year, item.image, item.progress, {}, { open(item) }, item.resumeLabel ?: item.title, expandable = false) } }
                }
            }
            return@Box
        }
        // Background in the billboard's colour, fading to the app background.
        // The billboard's colour, deepening as you scroll (near black by the third row, never plain black).
        val scrolled = tv.nakash.ui.components.rememberScrolledPx(list)
        tv.nakash.ui.components.ScrollTintBackground(pageTint, { scrolled.value })
        // The one trailer player, behind the content.
        Box(Modifier.fillMaxSize().onGloballyPositioned { stageOrigin = it.positionInRoot() }) {
            TrailerStage(target, onPlaying = { playingKey = it })
        }
        if (titles.isEmpty()) Column(Modifier.padding(top = 90.dp, start = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (loading) "מכינים את הספרייה שלך…" else status ?: "הספרייה עדיין לא נטענה", color = NakashColors.Muted)
            if (!loading) Action("טעינת הספרייה", { vm.refresh(seriesMode) })
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides manualScroll) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val billboardH = (maxHeight - tv.nakash.ui.nav.NavBarHeight) * .80f
                // The list lives below the nav bar and is clipped there: the row above never shows behind the bar.
                LazyColumn(Modifier.fillMaxSize().padding(top = tv.nakash.ui.nav.NavBarHeight).clipToBounds().focusGroup(), state = list, contentPadding = PaddingValues(top = 8.dp, bottom = maxHeight * .6f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "billboard") {
                        billboard?.let { b ->
                            Billboard(b.copy(backdrop = bigOf(b)), seriesMode, billboardH, billboardPlaying, playFocus,
                                onBounds = { liveBounds[0] = it; if (wantKey?.second == true) billboardBounds = it },
                                onFocus = { billboardFocused = true; focusedId = null; focusedShelf = null },
                                play = { playNow(b) }, info = { open(b) })
                        }
                    }
                    rowItemsIndexed(shelves, key = { _, sh -> sh.key }) { _, shelf ->
                        val rowFocused = !billboardFocused && focusedShelf == shelf.key
                        val focusedItem = if (rowFocused) shelf.items.firstOrNull { it.id == focusedId } else null
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(shelf.title, Modifier.padding(start = 40.dp), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                            rowState.SaveableStateProvider(shelf.key) {
                                val rowList = rememberLazyListState()
                                val focusIndex = if (rowFocused) shelf.items.indexOfFirst { it.id == focusedId }.let { if (it < 0 && focusedId == null) shelf.items.size else it } else -1
                                // The focused card always sits at the row start; the scroll is driven here, never by
                                // bring-into-view (which measured the card before it widened and left it half off-screen).
                                // Every card before the focused one is a poster, so the anchor offset is simply index × (poster + gap).
                                // Scrolling there with the same curve and duration as the widen/shrink keeps the row one smooth slide.
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                val stepPx = with(density) { (PosterW + 12.dp).toPx() }
                                // Cards before the focused one are posters: its start sits at index × step. Scrolling there on the
                                // same curve and duration as the widen/shrink makes the whole row one continuous slide.
                                val posterPx = with(androidx.compose.ui.platform.LocalDensity.current) { PosterW.toPx() }
                                // One smooth move per press, on the same curve and time as the widen/shrink. The target is measured
                                // from the screen at the moment of the press (not tracked), so quick presses can't make it drift:
                                // the focused card ends at the row start once every card before it is back to poster size.
                                LaunchedEffect(focusIndex) {
                                    if (focusIndex < 0) return@LaunchedEffect
                                    var info = rowList.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusIndex }
                                    if (info == null) { rowList.scrollToItem(focusIndex); androidx.compose.runtime.withFrameNanos { }; info = rowList.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusIndex } ?: return@LaunchedEffect }
                                    val shrinkBefore = rowList.layoutInfo.visibleItemsInfo.filter { it.index < focusIndex }.sumOf { (it.size - posterPx).coerceAtLeast(0f).toDouble() }.toFloat()
                                    val delta = info.offset - shrinkBefore
                                    if (kotlin.math.abs(delta) > 1f) rowList.animateScrollBy(delta, tween(CardMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                }
                                // Entering a row lands on the card you left it on, or its first card: never the card that happens to
                                // sit under the middle of the widened one above.
                                val rowFirst = remember(shelf.key) { FocusRequester() }
                                androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.focusRestorer { rowFirst }, state = rowList, contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    rowItemsIndexed(shelf.items, key = { _, it -> it.id }) { i, item ->
                                        val expanded = rowFocused && focusedId == item.id
                                        Box(if (i == 0) Modifier.focusRequester(rowFirst) else Modifier) {
                                        val wide = wideOf(item)
                                        ExpandingCard(remember(item, wide) { if (wide == item.backdrop) item else item.copy(backdrop = wide) }, expanded, playingHere = expanded && cardPlaying,
                                            onBounds = { if (expanded) { liveBounds[1] = it; if (wantKey?.second == false) cardBounds = it } },
                                            onFocus = { billboardFocused = false; focusedShelf = shelf.key; focusedId = item.id },
                                            click = { open(item) })
                                        }
                                    }
                                    item(key = "all") {
                                        Surface(onClick = { selectedCategory = shelf.key },
                                            modifier = Modifier.width(PosterW).height(CardH).focusRequester(endFocus.getOrPut(shelf.key) { FocusRequester() })
                                                .onFocusChanged { if (it.isFocused) { billboardFocused = false; focusedShelf = shelf.key; focusedId = null } },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = .08f), focusedContainerColor = Color.White.copy(alpha = .18f), contentColor = Color.White, focusedContentColor = Color.White)) {
                                            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("הצג הכול", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
                                                Text(shelf.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = NakashColors.Muted, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                            // Details of the focused title, under the row (Netflix).
                            Box(Modifier.padding(start = 40.dp, end = 40.dp).height(if (rowFocused) 58.dp else 0.dp)) {
                                focusedItem?.let { f ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(listOfNotNull(f.title, f.resumeLabel ?: f.year?.toString(), f.genres.split(',').firstOrNull()?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        f.plot?.let { Text(it, color = NakashColors.Muted, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The big card: image (then trailer) with title and plot; "הפעל" and "מידע נוסף" always on top. */
@Composable
private fun Billboard(b: ShelfTitle, seriesMode: Boolean, height: androidx.compose.ui.unit.Dp, trailerPlaying: Boolean, playFocus: FocusRequester,
                      onBounds: (Rect) -> Unit, onFocus: () -> Unit, play: () -> Unit, info: () -> Unit) {
    val imageAlpha by animateFloatAsState(if (trailerPlaying) 0f else 1f, tween(500), label = "bbImage")
    val textAlpha by animateFloatAsState(if (trailerPlaying) 0f else 1f, tween(if (trailerPlaying) 900 else 300), label = "bbText")
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.padding(horizontal = 40.dp).fillMaxWidth().height(height).clip(shape).border(1.dp, Color.White.copy(alpha = .10f), shape)
        .onGloballyPositioned { onBounds(it.boundsInRoot()) }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha }) {
            AsyncImage(b.backdrop ?: b.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // RTL: text on the right, so the shade comes from the right and the bottom.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent, .45f to Color.Transparent, 1f to Color.Black.copy(alpha = .78f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .72f))))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, end = 36.dp, bottom = 30.dp).fillMaxWidth(.52f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.graphicsLayer { alpha = textAlpha }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(b.title, style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp, lineHeight = 46.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (seriesMode) "סדרה" else "סרט", b.genres.split(',').firstOrNull()?.takeIf { it.isNotBlank() }, b.year?.toString(), b.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString("  ·  "),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), color = Color.White.copy(alpha = .85f))
                b.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = .9f)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BillboardButton("הפעל", Icons.Filled.PlayArrow, true, Modifier.focusRequester(playFocus), onFocus, play)
                BillboardButton("מידע נוסף", Icons.Outlined.Info, false, Modifier, onFocus, info)
            }
        }
    }
}

@Composable
private fun BillboardButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, modifier: Modifier, onFocus: () -> Unit, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier.height(52.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(26.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (primary) Color.White.copy(alpha = .9f) else Color.White.copy(alpha = .22f), contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp))
        }
    }
}

/**
 * Row card: a 2:3 poster that widens to 16:9 when focused (spring, no bounce). The wide image was pre-loaded, so it
 * fades in over the stretching poster; when the trailer plays here both fade out and the video behind shows through.
 */
@Composable
private fun ExpandingCard(item: ShelfTitle, expanded: Boolean, playingHere: Boolean, onBounds: (Rect) -> Unit, onFocus: () -> Unit, click: () -> Unit) {
    val width by animateDpAsState(if (expanded) WideW else PosterW, tween(CardMs, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "cardW")
    // The provider sometimes gives the poster (or a portrait image) as "backdrop": only a real landscape image is used wide.
    var wideOk by remember(item.backdrop) { mutableStateOf(false) }
    val hasWide = item.backdrop != null && item.backdrop != item.image
    val wideAlpha by animateFloatAsState(if (expanded && hasWide && wideOk) 1f else 0f, tween(if (expanded) 320 else 140), label = "wide")
    val fillAlpha by animateFloatAsState(if (expanded && !(hasWide && wideOk)) 1f else 0f, tween(260), label = "fill")
    val imageAlpha by animateFloatAsState(if (playingHere) 0f else 1f, tween(450), label = "cardImage")
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = click, modifier = Modifier.width(width).height(CardH).onFocusChanged { if (it.isFocused) onFocus() }.onGloballyPositioned { onBounds(it.boundsInRoot()) },
        shape = ClickableSurfaceDefaults.shape(shape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = shape))) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha }.background(NakashColors.Tile), contentAlignment = Alignment.Center) {
            // No wide image: the poster's own colours, blurred, fill the widened card behind the untouched poster.
            if (fillAlpha > 0f) AsyncImage(item.image, null, Modifier.fillMaxSize().graphicsLayer { alpha = fillAlpha }.blur(24.dp), contentScale = ContentScale.Crop, alpha = .55f)
            // The poster never stretches: it keeps its 2:3 size while the card widens around it.
            AsyncImage(item.image ?: item.backdrop, item.title, Modifier.width(PosterW).fillMaxHeight(), contentScale = ContentScale.Crop)
            if (hasWide && (expanded || wideAlpha > 0f)) AsyncImage(item.backdrop, null, Modifier.fillMaxSize().graphicsLayer { alpha = wideAlpha }, contentScale = ContentScale.Crop,
                onSuccess = { st -> val sz = st.painter.intrinsicSize; wideOk = sz.width > 0f && sz.width / sz.height >= 1.6f })
            if (expanded) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.6f to Color.Transparent, 1f to Color.Black.copy(alpha = .6f * wideAlpha))))
            item.progress?.let { p ->
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .25f))) { Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(NakashColors.Live)) }
                }
            }
        }
    }
}

private fun prefetch(ctx: android.content.Context, url: String?) {
    url ?: return
    // Warms the disk cache only, decoded at card size: an unsized request decodes the full image and evicts what is on screen.
    coil3.SingletonImageLoader.get(ctx).enqueue(coil3.request.ImageRequest.Builder(ctx).data(url).size(800, 450).memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build())
}

