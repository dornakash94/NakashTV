package tv.nakash.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import tv.nakash.R

object NakashColors {
    val Bg = Color(0xFF0A0A0C); val S1 = Color(0xFF15161A); val S2 = Color(0xFF1F2127); val S3 = Color(0xFF2A2D35)
    val Text = Color(0xFFF4F4F5); val Muted = Color(0xFF9A9CA5); val Dim = Color(0xFF5E616B)
    val Accent = Color(0xFFF0B441); val Live = Color(0xFFE3453A); val Ok = Color(0xFF4CC38A); val Tile = Color(0xFF1D1E23)
}

/** Bundled Heebo variable font; licensed under the SIL Open Font License. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Heebo = FontFamily(
        Font(R.font.heebo, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))), Font(R.font.heebo, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.heebo, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))), Font(R.font.heebo, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val scheme: ColorScheme = darkColorScheme(
    primary = NakashColors.Accent, onPrimary = Color.Black, background = NakashColors.Bg, onBackground = NakashColors.Text,
    surface = NakashColors.S1, onSurface = NakashColors.Text, surfaceVariant = NakashColors.S2, onSurfaceVariant = NakashColors.Muted,
    border = Color.White, error = NakashColors.Live,
)

/** TV type scale: nothing readable is smaller than 20sp at 1080p. */
private val type = Typography(
    displayLarge = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    labelMedium = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Medium, fontSize = 20.sp),
)

@Composable
fun NakashTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, typography = type) {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides NakashColors.Text, androidx.tv.material3.LocalTextStyle provides type.bodyLarge) { content() }
        }
    }
}
