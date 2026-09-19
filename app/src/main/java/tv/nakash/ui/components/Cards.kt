package tv.nakash.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import tv.nakash.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.EpgEntity
import tv.nakash.domain.Normalizer
import tv.nakash.ui.theme.NakashColors

/**
 * Focus scale 1.08, white 3dp ring, 200ms. Focus changes never recompose the row — only this card.
 */
@Composable
fun FocusScale(focused: Boolean, content: @Composable () -> Unit) {
    val s by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "scale")
    Box(Modifier.scale(s)) { content() }
}

@Composable
fun Monogram(name: String, size: Int = 64, corner: Int = 12) {
    // Neutral tile that works for white and dark logos; deterministic hue per name for the fallback initials.
    val hue = remember(name) { name.fold(0) { a, c -> (a * 31 + c.code) % 360 } }
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(Color.hsl(hue.toFloat(), 0.22f, 0.24f)),
        contentAlignment = Alignment.Center,
    ) { Text(Normalizer.initials(name), style = MaterialTheme.typography.titleLarge, color = Color.White) }
}

@Composable
fun ChannelLogo(channel: ChannelEntity, size: Int = 64, plain: Boolean = false) {
    var failed by remember(channel.logo) { mutableStateOf(channel.logo == null) }
    if (failed) Monogram(channel.displayName, size)
    else Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(if(plain) Color.Transparent else Color(0xFF1F1F1F)).padding(if(plain) 2.dp else 8.dp)) {
        AsyncImage(model = channel.logo, contentDescription = channel.displayName, modifier = Modifier.fillMaxSize(), onError = { failed = true })
    }
}

/** 320x180 channel card: logo, name, number, "now" line, progress. No tags. */
@Composable
fun ChannelCard(
    channel: ChannelEntity, now: EpgEntity?, nowSec: Long,
    onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier, compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    FocusScale(focused) {
        Surface(
            onClick = onClick, onLongClick = onLongClick,
            modifier = modifier.width(if(compact) 160.dp else 320.dp).height(if(compact) 90.dp else 150.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = NakashColors.Tile, focusedContainerColor = NakashColors.S2),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(10.dp))),
        ) {
            Column(Modifier.fillMaxSize().padding(if(compact) 8.dp else 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChannelLogo(channel,if(compact) 34 else 52)
                    Column(Modifier.weight(1f)) {
                        Text(channel.displayName, style = MaterialTheme.typography.titleLarge.copy(fontSize=if(compact) 12.sp else 18.sp,lineHeight=if(compact) 15.sp else 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.channel_number, channel.number), style = MaterialTheme.typography.labelMedium.copy(fontSize=if(compact) 10.sp else 16.sp), color = NakashColors.Muted)
                    }
                }
                Column {
                    if (now != null && !now.isFiller && now.end > now.start) {
                        Row { Text(stringResource(R.string.now_playing) + " ", style = MaterialTheme.typography.labelLarge.copy(fontSize=if(compact) 11.sp else 16.sp)); Text(now.title, style = MaterialTheme.typography.labelLarge.copy(fontSize=if(compact) 11.sp else 16.sp), color = NakashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(((nowSec - now.start).toFloat() / (now.end - now.start)).coerceIn(0f, 1f), Color.White)
                    } else Text(stringResource(R.string.no_epg), style = MaterialTheme.typography.labelLarge.copy(fontSize=if(compact) 11.sp else 16.sp), color = NakashColors.Muted)
                }
            }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, height: Int = 3) {
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxSize().background(color))
    }
}

/** 200x300 poster. Title/year only in the fallback or on focus. Red progress line for partially watched items. */
@Composable
fun PosterCard(title: String, year: Int?, image: String?, progress: Float?, onFocus: () -> Unit, onClick: () -> Unit, label: String? = null) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(image) { mutableStateOf(image == null) }
    FocusScale(focused) {
        Surface(
            onClick = onClick,
            modifier = Modifier.width(100.dp).height(150.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = NakashColors.Tile, focusedContainerColor = NakashColors.Tile),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp))),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!failed) AsyncImage(model = image, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, onError = { failed = true })
                else Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(NakashColors.S3, NakashColors.S1))).padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Normalizer.initials(title), style = MaterialTheme.typography.displayLarge, color = NakashColors.Muted)
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    year?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = NakashColors.Muted) }
                }
                if (focused && label != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.9f)))).padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (progress != null) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp)) { ProgressBar(progress, NakashColors.Live) }
            }
        }
    }
}
