package tv.nakash.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
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
private fun rowStep(kind: CardKind) = when (kind) { CardKind.POSTER -> PosterW; else -> WideW } + 12.dp

/**
 * Rows only (no billboard): black page, list clipped under the nav bar, the focused row just below it, the focused card
 * pinned to the row start with details underneath. One shared YouTube player for trailers; channel cards show the live
 * preview inside the card.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NetflixRowsPage(
    shelves: List<RowShelf>, previewPlayer: androidx.media3.common.Player?, previewHasFrame: Boolean,
    firstFocus: FocusRequester, modifier: Modifier = Modifier, restoreKey: Any = Unit,
) {
    var focusedShelf by remember(restoreKey) { mutableStateOf<String?>(null) }
    var focusedCard by remember(restoreKey) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val rowState = rememberSaveableStateHolder()
    // trailers
    val extras = remember(restoreKey) { mutableStateMapOf<String, tv.nakash.domain.TmdbDetails?>() }
    suspend fun extrasOf(c: RowCard): tv.nakash.domain.TmdbDetails? {
        val lookup = c.extras ?: return null
        if (extras.containsKey(c.key)) return extras[c.key]
        return runCatching { lookup() }.getOrNull().also { extras[c.key] = it }
    }
    suspend fun trailerOf(c: RowCard): String? = extrasOf(c)?.trailerKey
    var stageOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var want by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val focused = shelves.firstOrNull { it.key == focusedShelf }?.cards?.firstOrNull { it.key == focusedCard }
    LaunchedEffect(focused?.key) {
        want = null
        val c = focused ?: return@LaunchedEffect
        if (c.kind != CardKind.POSTER) return@LaunchedEffect
        delay(350); want = trailerOf(c)
    }
    LaunchedEffect(focusedShelf, shelves) {
        val i = shelves.indexOfFirst { it.key == focusedShelf }
        (shelves.getOrNull(i)?.cards.orEmpty() + shelves.getOrNull(i + 1)?.cards.orEmpty().take(6)).filter { it.kind == CardKind.POSTER }.forEach { trailerOf(it) }
    }
    val target = want?.let { k -> cardBounds?.let { TrailerTarget(k, it.translate(-stageOrigin), 10f) } }
    LaunchedEffect(focusedShelf) { val i = shelves.indexOfFirst { it.key == focusedShelf }; if (i >= 0) list.animateScrollToItem(i) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { stageOrigin = it.positionInRoot() }) { TrailerStage(target, onPlaying = { playing = it }) }
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
                            LaunchedEffect(focusIndex) {
                                if (focusIndex < 0) return@LaunchedEffect
                                val current = rowList.firstVisibleItemIndex * stepPx + rowList.firstVisibleItemScrollOffset
                                val delta = focusIndex * stepPx - current
                                if (kotlin.math.abs(delta) > 1f) rowList.animateScrollBy(delta, tween(CardMs, easing = FastOutSlowInEasing))
                            }
                            val rowFirst = remember(shelf.key) { FocusRequester() }
                            LazyRow(modifier = Modifier.focusRestorer { rowFirst }, state = rowList, contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(shelf.cards, key = { _, c -> c.key }) { i, c ->
                                    val isFocused = rowFocused && focusedCard == c.key
                                    Box(if (i == 0) Modifier.focusRequester(rowFirst).then(if (shelfIndex == 0) Modifier.focusRequester(firstFocus) else Modifier) else Modifier) {
                                        val onF = { focusedShelf = shelf.key; focusedCard = c.key; c.onFocus() }
                                        when (c.kind) {
                                            CardKind.POSTER -> PosterExpandingCard(c.copy(wide = extras[c.key]?.backdrop ?: c.wide), isFocused, isFocused && playing != null && playing == target?.key, { if (isFocused) cardBounds = it }, onF)
                                            CardKind.WIDE -> WideCard(c, onF)
                                            CardKind.CHANNEL -> ChannelWideCard(c, isFocused, previewPlayer, isFocused && previewHasFrame, onF)
                                        }
                                    }
                                }
                                shelf.onShowAll?.let { all ->
                                    item(key = "all") {
                                        Surface(onClick = all, modifier = Modifier.width(if (kind == CardKind.POSTER) PosterW else WideW * .6f).height(if (kind == CardKind.POSTER) PosterH else WideH)
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

@Composable
private fun PosterExpandingCard(c: RowCard, expanded: Boolean, playingHere: Boolean, onBounds: (Rect) -> Unit, onFocus: () -> Unit) {
    val width by animateDpAsState(if (expanded) PosterWide else PosterW, tween(CardMs, easing = FastOutSlowInEasing), label = "w")
    var wideOk by remember(c.wide) { mutableStateOf(false) }
    val hasWide = c.wide != null && c.wide != c.poster
    val wideAlpha by animateFloatAsState(if (expanded && hasWide && wideOk) 1f else 0f, tween(if (expanded) 320 else 140), label = "wide")
    val fillAlpha by animateFloatAsState(if (expanded && !(hasWide && wideOk)) 1f else 0f, tween(260), label = "fill")
    val imageAlpha by animateFloatAsState(if (playingHere) 0f else 1f, tween(450), label = "img")
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = c.onClick, onLongClick = c.onLongClick, modifier = Modifier.width(width).height(PosterH).onFocusChanged { if (it.isFocused) onFocus() }.onGloballyPositioned { onBounds(it.boundsInRoot()) },
        shape = ClickableSurfaceDefaults.shape(shape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = shape))) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha }.background(NakashColors.Tile), contentAlignment = Alignment.Center) {
            if (fillAlpha > 0f) AsyncImage(c.poster, null, Modifier.fillMaxSize().graphicsLayer { alpha = fillAlpha }.blur(24.dp), contentScale = ContentScale.Crop, alpha = .55f)
            AsyncImage(c.poster ?: c.wide, c.title, Modifier.width(PosterW).fillMaxHeight(), contentScale = ContentScale.Crop)
            if (hasWide && (expanded || wideAlpha > 0f)) AsyncImage(c.wide, null, Modifier.fillMaxSize().graphicsLayer { alpha = wideAlpha }, contentScale = ContentScale.Crop,
                onSuccess = { st -> val sz = st.painter.intrinsicSize; wideOk = sz.width > 0f && sz.width / sz.height >= 1.6f })
            c.progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** Continue-watching style: landscape image, title and remaining time. */
@Composable
private fun WideCard(c: RowCard, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = c.onClick, onLongClick = c.onLongClick, modifier = Modifier.width(WideW).height(WideH).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = NakashColors.Tile, focusedContainerColor = NakashColors.Tile),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = shape))) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(c.wide ?: c.poster, c.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .88f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                c.label?.let { Text(it, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1) }
            }
            c.progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** A channel: logo and what's on; when focused, the live picture plays inside the card. */
@Composable
private fun ChannelWideCard(c: RowCard, focused: Boolean, player: androidx.media3.common.Player?, hasFrame: Boolean, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val videoAlpha by animateFloatAsState(if (focused && hasFrame) 1f else 0f, tween(400), label = "live")
    Surface(onClick = c.onClick, onLongClick = c.onLongClick, modifier = Modifier.width(WideW).height(WideH).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF16171C), focusedContainerColor = Color(0xFF1E2027)),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = shape))) {
        Box(Modifier.fillMaxSize()) {
            val ch = c.channel
            Box(Modifier.align(Alignment.Center).padding(bottom = 26.dp)) { if (ch != null) ChannelLogo(ch, 72, plain = true) }
            if (focused && player != null) AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(tv.nakash.R.layout.player_preview, null, false) as PlayerView).apply {
                useController = false; isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = videoAlpha }, onRelease = { it.player = null })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(NakashColors.Live))
                    Text(c.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                c.nowTitle?.let { Text(it, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
