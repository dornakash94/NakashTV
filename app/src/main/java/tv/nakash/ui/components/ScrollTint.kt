package tv.nakash.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How far the list has scrolled, in px, continuous across items (sizes remembered as they are laid out). */
@Composable
fun rememberScrolledPx(list: LazyListState): State<Float> {
    val sizes = remember(list) { HashMap<Int, Int>() }
    return remember(list) { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.forEach { sizes[it.index] = it.size }
        val first = list.firstVisibleItemIndex
        var sum = 0f
        for (i in 0 until first) sum += (sizes[i] ?: 400)
        sum + list.firstVisibleItemScrollOffset
    } }
}

/**
 * The page background: a gradient in [tint] that deepens gradually as you scroll and is close to black by about the
 * third row, but always keeps a trace of the colour (never a plain black gradient).
 */
@Composable
fun ScrollTintBackground(tint: Color, scrolledPx: Float, modifier: Modifier = Modifier) {
    val color by animateColorAsState(tint, tween(900), label = "tint")
    val density = LocalDensity.current
    val fadePx = with(density) { 1100.dp.toPx() }
    // Follows the scroll itself (which is already animated), so it never lags behind the content.
    val p = (scrolledPx / fadePx).coerceIn(0f, 1f)
    val strength = 1f - .75f * (p * p * (3f - 2f * p))          // smoothstep: eases in and out
    Box(modifier.fillMaxSize().background(Color.Black))
    // The colour belongs to the top of the page: it drifts up with the content (slower, parallax) as it deepens.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val h = maxHeight * 1.6f
        val shift = with(density) { (scrolledPx * .45f).toDp() }.coerceAtMost(maxHeight * .6f)
        Box(Modifier.fillMaxWidth().height(h).offset(y = -shift).background(Brush.verticalGradient(
            0f to lerp(Color.Black, lerp(color, Color.Black, .25f), strength),
            .35f to lerp(Color.Black, lerp(color, Color.Black, .55f), strength),
            .70f to lerp(Color.Black, lerp(color, Color.Black, .82f), strength),
            1f to lerp(Color.Black, lerp(color, Color.Black, .92f), strength),
        )))
    }
}

/** A dark, saturated tone from an image, for page backgrounds. */
suspend fun dominantColor(ctx: android.content.Context, url: String?): Color? = withContext(Dispatchers.IO) {
    url ?: return@withContext null
    runCatching {
        val req = coil3.request.ImageRequest.Builder(ctx).data(url).size(160, 90).allowHardware(false).build()
        val bmp = (coil3.SingletonImageLoader.get(ctx).execute(req) as? coil3.request.SuccessResult)?.image?.toBitmap() ?: return@runCatching null
        val p = androidx.palette.graphics.Palette.from(bmp).generate()
        val rgb = (p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch)?.rgb ?: return@runCatching null
        Color(rgb)
    }.getOrNull()
}
