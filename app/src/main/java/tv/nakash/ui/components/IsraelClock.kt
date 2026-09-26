package tv.nakash.ui.components

import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val ISRAEL: ZoneId = ZoneId.of("Asia/Jerusalem")
private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Israel time, small and white, for the top right corner of every screen. Always Israel time, whatever zone the TV is
 * set to; ticks on the minute boundary. A soft shadow keeps it readable over bright pictures.
 */
@Composable
fun IsraelClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(ZonedDateTime.now(ISRAEL)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now(ISRAEL)
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
        }
    }
    Text(HM.format(now), modifier,
        style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            shadow = Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 1.5f), 6f)))
}
