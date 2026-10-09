package tv.nakash.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import tv.nakash.data.local.ChannelEntity
import tv.nakash.ui.theme.NakashColors

enum class CardKind { POSTER, WIDE, CHANNEL }

/** One card in a Netflix-style row. POSTER widens to 16:9 and plays its trailer; CHANNEL plays its live preview. */
data class RowCard(
    val key: String, val kind: CardKind, val title: String,
    val poster: String? = null, val wide: String? = null,
    val meta: String = "", val plot: String? = null, val progress: Float? = null, val label: String? = null,
    val channel: ChannelEntity? = null, val nowTitle: String? = null,
    val extras: (suspend () -> tv.nakash.domain.TmdbDetails?)? = null,
    val onFocus: () -> Unit = {}, val onClick: () -> Unit, val onLongClick: (() -> Unit)? = null,
)
data class RowShelf(val key: String, val title: String, val subtitle: String? = null, val cards: List<RowCard>, val onShowAll: (() -> Unit)? = null)

private val PosterH = 264.dp
private val PosterW = 176.dp
private val PosterWide = 469.dp
private val WideW = 272.dp
private val WideH = 153.dp
private const val CardMs = 320
private fun rowStep(@Suppress("UNUSED_PARAMETER") kind: CardKind) = PosterW + 12.dp

/**
 * Rows only (no billboard): black page, list clipped under the nav bar, the focused row just below it, the focused card
 * pinned to the row start with details underneath. One shared YouTube player for trailers; channel cards show the live
 * preview inside the card.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NetflixRowsPage(
    shelves: List<RowShelf>, previewPlayer: androidx.media3.common.Player?, previewHasFrame: Boolean,
    firstFocus: FocusRequester, modifier: Modifier = Modifier, restoreKey: Any = Unit,
) {
    // Saved with the screen: coming back (Back from a title, the player or another tab) lands on the card you left.
    var focusedShelf by androidx.compose.runtime.saveable.rememberSaveable(restoreKey) { mutableStateOf<String?>(null) }
    var focusedCard by androidx.compose.runtime.saveable.rememberSaveable(restoreKey) { mutableStateOf<String?>(null) }
    // Where [firstFocus] goes: the card that had focus when the screen was left, else the first card.
    val entry = remember(shelves.isNotEmpty()) {
        val s = focusedShelf; val c = focusedCard
        if (s != null && c != null && shelves.any { it.key == s && it.cards.any { x -> x.key == c } }) s to c
        else shelves.firstOrNull()?.let { it.key to it.cards.firstOrNull()?.key }
    }
    val list = rememberLazyListState()
    val rowState = rememberSaveableStateHolder()
    // trailers
    // TMDB extras per card, kept OUT of Compose state: a state map made every card and the page recompose on each
    // of the 20-30 results that land while a row slides (jank on weak boxes). Each card instead reads only its own
    // wide image ([wideOf]), and one title is never looked up twice at once.
    val extras = remember(restoreKey) { HashMap<String, kotlinx.coroutines.Deferred<tv.nakash.domain.TmdbDetails?>>() }
    val wides = remember(restoreKey) { HashMap<String, androidx.compose.runtime.MutableState<String?>>() }
    fun wideOf(key: String) = wides.getOrPut(key) { mutableStateOf(null) }
    val extrasScope = rememberCoroutineScope()
    suspend fun extrasOf(c: RowCard): tv.nakash.domain.TmdbDetails? {
        val lookup = c.extras ?: return null
        return extras.getOrPut(c.key) {
            extrasScope.async { runCatching { lookup() }.getOrNull().also { d -> d?.backdrop?.let { wideOf(c.key).value = it } } }
        }.await()
    }
    suspend fun trailerOf(c: RowCard): String? = extrasOf(c)?.trailerKey
    var stageOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    // Where the focused card is, updated every frame while it widens or the row slides. Kept outside Compose state
    // (writing state there recomposed the whole page every frame); copied into [cardBounds] only when a trailer is due.
    val liveBounds = remember { arrayOfNulls<Rect>(1) }
    var want by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val focused = shelves.firstOrNull { it.key == focusedShelf }?.cards?.firstOrNull { it.key == focusedCard }
    LaunchedEffect(focused?.key) {
        want = null
        val c = focused ?: return@LaunchedEffect
        if (c.kind != CardKind.POSTER) return@LaunchedEffect
        // A real pause on the card, not a pass through it: a trailer loading while you browse stutters weak boxes.
        delay(1_200)
        val k = trailerOf(c) ?: return@LaunchedEffect
        cardBounds = liveBounds[0]; want = k
    }
    LaunchedEffect(focusedShelf, shelves) {
        val i = shelves.indexOfFirst { it.key == focusedShelf }
        (shelves.getOrNull(i)?.cards.orEmpty().take(10) + shelves.getOrNull(i + 1)?.cards.orEmpty().take(3)).filter { it.kind == CardKind.POSTER }.forEach { trailerOf(it) }
    }
    val target = want?.let { k -> cardBounds?.let { TrailerTarget(k, it.translate(-stageOrigin), 10f) } }
    LaunchedEffect(focusedShelf) { val i = shelves.indexOfFirst { it.key == focusedShelf }; if (i >= 0) list.animateScrollToItem(i) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tintSource = focused?.let { wideOf(it.key).value ?: it.wide ?: it.poster ?: it.channel?.logo }
    var tint by remember(restoreKey) { mutableStateOf(Color(0xFF1B1D22)) }
    LaunchedEffect(tintSource) { delay(650); dominantColor(ctx, tintSource)?.let { tint = it } }
    val scrolled = rememberScrolledPx(list)
    Box(modifier.fillMaxSize()) {
        ScrollTintBackground(tint, { scrolled.value })
        Box(Modifier.fillMaxSize().onGloballyPositioned { stageOrigin = it.positionInRoot() }) { TrailerStage(target, onPlaying = { playing = it }) }
        // Scrolling is driven only by focus changes here (rows to the top, cards to the row start), never by the
        // system's bring-into-view, which nudged the row down on the first move across it.
        val defaultSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        val noAutoScroll = remember { object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f } }
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides noAutoScroll) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(top = tv.nakash.ui.nav.NavBarHeight).clipToBounds().focusGroup(), state = list,
                contentPadding = PaddingValues(top = 8.dp, bottom = maxHeight * .6f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(shelves, key = { _, s -> s.key }) { shelfIndex, shelf ->
                    val rowFocused = focusedShelf == shelf.key
                    val kind = shelf.cards.firstOrNull()?.kind ?: CardKind.POSTER
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(shelf.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                            shelf.subtitle?.let { Text(it, color = NakashColors.Muted, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)) }
                        }
                        rowState.SaveableStateProvider(shelf.key) {
                            val rowList = rememberLazyListState()
                            val focusIndex = if (rowFocused) shelf.cards.indexOfFirst { it.key == focusedCard }.let { if (it < 0) shelf.cards.size else it } else -1
                            val stepPx = with(LocalDensity.current) { rowStep(kind).toPx() }
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
                            val rowFirst = remember(shelf.key) { FocusRequester() }
                            // Rows keep the platform's bring-into-view (focus search across a row relies on it); only the page list
                            // has it disabled. Our anchoring scroll then only adds what is still missing.
                            CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides noAutoScroll) {
                            LazyRow(modifier = Modifier.focusProperties { enter = { rowFirst.ifAttached() } }, state = rowList, contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(shelf.cards, key = { _, c -> c.key }, contentType = { _, c -> c.kind }) { i, c ->
                                    val isFocused = rowFocused && focusedCard == c.key
                                    Box((if (i == 0) Modifier.focusRequester(rowFirst) else Modifier).then(if (entry?.first == shelf.key && entry.second == c.key) Modifier.focusRequester(firstFocus) else Modifier)) {
                                        // Same objects from one pass to the next, so cards whose inputs did not change skip recomposition.
                                        val onF = remember(shelf.key, c) { { focusedShelf = shelf.key; focusedCard = c.key; c.onFocus() } }
                                        val onB = remember(isFocused) { { r: Rect -> if (isFocused) { liveBounds[0] = r; if (want != null) cardBounds = r } } }
                                        val wide = (wideOf(c.key).value ?: c.wide)?.let(::cardSized)
                                        val card = remember(c, wide) { if (wide == c.wide) c else c.copy(wide = wide) }
                                        PosterExpandingCard(card, isFocused, isFocused && playing != null && playing == target?.key,
                                            onB, onF, previewPlayer, isFocused && previewHasFrame)
                                    }
                                }
                                shelf.onShowAll?.let { all ->
                                    item(key = "all") {
                                        Surface(onClick = all, modifier = Modifier.width(PosterW).height(PosterH)
                                            .onFocusChanged { if (it.isFocused) { focusedShelf = shelf.key; focusedCard = null } },
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
                            }
                        }
                        Box(Modifier.padding(horizontal = 40.dp).height(if (rowFocused) 58.dp else 0.dp)) {
                            val f = if (rowFocused) shelf.cards.firstOrNull { it.key == focusedCard } else null
                            f?.let {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(listOf(it.title, it.meta).filter { s -> s.isNotBlank() }.joinToString("  ·  "), style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    it.plot?.takeIf { p -> p.isNotBlank() }?.let { p -> Text(p, color = NakashColors.Muted, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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

/**
 * The one row card (Movies, Series, Home, Live): 2:3 at rest, widens to 16:9 when focused. Titles show their wide
 * image (and then the trailer behind it); continue-watching shows its wide frame; a channel shows its logo at rest and
 * its live picture when focused.
 */
@Composable
private fun PosterExpandingCard(c: RowCard, expanded: Boolean, playingHere: Boolean, onBounds: (Rect) -> Unit, onFocus: () -> Unit,
                                livePlayer: androidx.media3.common.Player? = null, liveFrame: Boolean = false) {
    val width by animateDpAsState(if (expanded) PosterWide else PosterW, tween(CardMs, easing = FastOutSlowInEasing), label = "w")
    var wideOk by remember(c.wide) { mutableStateOf(c.kind == CardKind.WIDE) }
    val hasWide = c.wide != null && c.wide != c.poster
    val wideAlpha by animateFloatAsState(if (expanded && hasWide && wideOk) 1f else 0f, tween(if (expanded) 320 else 140), label = "wide")
    val fillAlpha by animateFloatAsState(if (expanded && c.kind == CardKind.POSTER && !(hasWide && wideOk)) 1f else 0f, tween(260), label = "fill")
    val imageAlpha by animateFloatAsState(if (playingHere) 0f else 1f, tween(450), label = "img")
    val liveAlpha by animateFloatAsState(if (expanded && liveFrame) 1f else 0f, tween(400), label = "live")
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = c.onClick, onLongClick = c.onLongClick, modifier = Modifier.width(width).height(PosterH).onFocusChanged { if (it.isFocused) onFocus() }
            // Only the focused card's position is used (for its trailer); the others don't report it every frame.
            .then(if (expanded) Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) } else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = shape))) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha }.background(if (c.kind == CardKind.CHANNEL) Color(0xFF17181D) else NakashColors.Tile), contentAlignment = Alignment.Center) {
            when (c.kind) {
                CardKind.CHANNEL -> {
                    c.channel?.let { Box(Modifier.padding(bottom = 40.dp)) { ChannelLogo(it, 96, plain = true) } }
                    if (expanded && livePlayer != null) AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(tv.nakash.R.layout.player_preview, null, false) as PlayerView).apply {
                        useController = false; isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        player = livePlayer; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = liveAlpha }, onRelease = { it.player = null })
                }
                CardKind.WIDE -> AsyncImage(c.wide ?: c.poster, c.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                CardKind.POSTER -> {
                    if (fillAlpha > 0f) AsyncImage(c.poster, null, Modifier.fillMaxSize().graphicsLayer { alpha = fillAlpha }.blur(24.dp), contentScale = ContentScale.Crop, alpha = .55f)
                    AsyncImage(c.poster ?: c.wide, c.title, Modifier.width(PosterW).fillMaxHeight(), contentScale = ContentScale.Crop)
                    if (hasWide && (expanded || wideAlpha > 0f)) AsyncImage(c.wide, null, Modifier.fillMaxSize().graphicsLayer { alpha = wideAlpha }, contentScale = ContentScale.Crop,
                        onSuccess = { st -> val sz = st.painter.intrinsicSize; wideOk = sz.width > 0f && sz.width / sz.height >= 1.6f })
                }
            }
            // Channels and continue-watching carry their name on the card (posters already show it in the art).
            if (c.kind != CardKind.POSTER) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f))))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (c.kind == CardKind.CHANNEL) Box(Modifier.size(7.dp).clip(CircleShape).background(NakashColors.Live))
                        Text(c.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp), maxLines = if (expanded) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                    (c.nowTitle ?: c.label)?.let { Text(it, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            c.progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable
private fun ProgressLine(p: Float, modifier: Modifier) {
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .22f))) { Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(NakashColors.Live)) }
    }
}


/**
 * This requester when its card is attached right now, else the default focus search. Entering a row whose first card
 * is not attached (still pre-composed, or the screen being rebuilt) made Compose throw and took the app down;
 * freeFocus() is a harmless no-op that throws exactly in that case.
 */
internal fun FocusRequester.ifAttached(): FocusRequester = if (runCatching { freeFocus() }.isSuccess) this else FocusRequester.Default

/** A TMDB image at the size a card shows it (w780 is plenty for a 469 dp card; w1280 stays for full screens). */
internal fun cardSized(url: String): String = url.replace("/t/p/w1280/", "/t/p/w780/").replace("/t/p/original/", "/t/p/w780/")

/** "להסיר מהמשך צפייה?" — asked on a long press on a continue-watching card. */
@Composable
fun RemoveFromContinueDialog(title: String, remove: () -> Unit, dismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(28.dp).width(440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("להסיר מ״המשך צפייה״?", style = MaterialTheme.typography.headlineSmall)
                Text(title, color = NakashColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tv.nakash.ui.library.Action("הסרה", remove, Modifier.focusRequester(first))
                    tv.nakash.ui.library.Action("ביטול", dismiss)
                }
            }
        }
    }
}
