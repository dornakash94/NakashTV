package tv.nakash.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.cos
import kotlin.math.sin

private enum class Hair { Short, Long, Curly, Bun, Afro, Bald, Spiky }

/** One of the 8 avatars: a drawn character (head and shoulders) on a two-colour gradient. */
private data class Person(
    val bg1: Color, val bg2: Color, val skin: Color, val hair: Color, val shirt: Color, val style: Hair,
    val glasses: Boolean = false, val beard: Boolean = false, val moustache: Boolean = false,
    val cap: Color? = null, val headphones: Boolean = false,
)

private val PEOPLE = listOf(
    Person(Color(0xFFFFB86B), Color(0xFFFF5E62), Color(0xFFF6D2B8), Color(0xFF6B3A1E), Color(0xFF2B2D42), Hair.Long),
    Person(Color(0xFF43C6AC), Color(0xFF1B5E6B), Color(0xFFD9A47E), Color(0xFF1E1E1E), Color(0xFFF4F1DE), Hair.Short, beard = true),
    Person(Color(0xFFA77BF3), Color(0xFF4A2BA8), Color(0xFF8D5A3B), Color(0xFF1A1110), Color(0xFFFFC145), Hair.Curly),
    Person(Color(0xFFFF9AC8), Color(0xFFC2185B), Color(0xFFFBE0CF), Color(0xFFF2C14E), Color(0xFF3A86FF), Hair.Bun, glasses = true),
    Person(Color(0xFF6FB1FC), Color(0xFF1F4E9C), Color(0xFFEFC7A6), Color(0xFF8B5A2B), Color(0xFFE63946), Hair.Short, cap = Color(0xFFFFD166)),
    Person(Color(0xFFFFD86B), Color(0xFFF08A24), Color(0xFF6B4226), Color(0xFF231815), Color(0xFF06A77D), Hair.Afro),
    Person(Color(0xFF9BE15D), Color(0xFF2E8B57), Color(0xFFF1CDB0), Color(0xFFD9D9D9), Color(0xFF5C677D), Hair.Bald, glasses = true, moustache = true),
    Person(Color(0xFFFF8A80), Color(0xFF8E2442), Color(0xFFF8D7C0), Color(0xFFC1440E), Color(0xFF111111), Hair.Spiky, headphones = true),
    // more people
    Person(Color(0xFFB794F6), Color(0xFF6B46C1), Color(0xFFFBE3D0), Color(0xFFF6D365), Color(0xFF2EC4B6), Hair.Long),
    Person(Color(0xFFFFC371), Color(0xFFFF5F6D), Color(0xFF5A3825), Color(0xFF151010), Color(0xFF3D5A80), Hair.Short, beard = true, glasses = true),
    Person(Color(0xFF84FAB0), Color(0xFF2A9D8F), Color(0xFFF7D6BF), Color(0xFFB5361B), Color(0xFFFFB703), Hair.Curly, glasses = true),
    Person(Color(0xFF8EC5FC), Color(0xFF3B5BDB), Color(0xFFC68B59), Color(0xFF2B1B12), Color(0xFFF72585), Hair.Bun, headphones = true),
    Person(Color(0xFFFDA085), Color(0xFFE2366B), Color(0xFFF3CFB3), Color(0xFF3A86FF), Color(0xFF14213D), Hair.Spiky),
    Person(Color(0xFFF6D365), Color(0xFFFDA085), Color(0xFF7B4B2A), Color(0xFF3B2416), Color(0xFF7209B7), Hair.Afro, glasses = true),
    Person(Color(0xFF43E97B), Color(0xFF168AAD), Color(0xFFF5D5BC), Color(0xFF111111), Color(0xFFE63946), Hair.Long, cap = Color(0xFF1D3557)),
    Person(Color(0xFFE0C3FC), Color(0xFF8E7DBE), Color(0xFFF2D0B6), Color(0xFFE8E8E8), Color(0xFFB5838D), Hair.Bun, glasses = true),
)

private enum class Animal { Cat, Dog, Panda, Fox, Bear, Rabbit, Owl, Lion }
private enum class Fantasy { Robot, Alien, Monster, Ghost, Astronaut, Ninja, Wizard, Hero }

/** 32 drawn avatars: 16 people, 8 animals, 8 fantasy characters (no photos of real people). */
const val AVATAR_COUNT = 32

/** The gallery's rows: a name and the avatar numbers in it. */
val AVATAR_ROWS = listOf("אנשים" to (0 until 8).toList(), "עוד אנשים" to (8 until 16).toList(), "חיות" to (16 until 24).toList(), "דמויות דמיון" to (24 until 32).toList())

private val ANIMAL_BG = listOf(Color(0xFFFFD86B) to Color(0xFFF08A24), Color(0xFF6FB1FC) to Color(0xFF1F4E9C), Color(0xFF9BE15D) to Color(0xFF2E8B57), Color(0xFF43C6AC) to Color(0xFF1B5E6B),
    Color(0xFFFF9AC8) to Color(0xFFC2185B), Color(0xFFA77BF3) to Color(0xFF4A2BA8), Color(0xFF8EC5FC) to Color(0xFF3B5BDB), Color(0xFFFF8A80) to Color(0xFF8E2442))
private val FANTASY_BG = listOf(Color(0xFF4FACFE) to Color(0xFF0B3D91), Color(0xFF2B2D42) to Color(0xFF0D1B2A), Color(0xFFFFC371) to Color(0xFFFF5F6D), Color(0xFF667EEA) to Color(0xFF2D1B69),
    Color(0xFF0F2027) to Color(0xFF2C5364), Color(0xFFFF6B6B) to Color(0xFF7F1D1D), Color(0xFF43C6AC) to Color(0xFF191654), Color(0xFFF6D365) to Color(0xFFE85D04))

/** The shape of every profile picture (Netflix style rounded square). */
val AvatarShape = RoundedCornerShape(14)

/**
 * A profile's picture: one of the 32 drawn avatars (or a photo kept from an older version), or (nothing chosen) its first
 * letter on its colour. Fills the given modifier's size; [size] scales the letter.
 */
@Composable
fun Avatar(avatar: Int, name: String, color: Color, size: Dp, modifier: Modifier = Modifier, url: String? = null) {
    val person = PEOPLE.getOrNull(avatar)
    Box(modifier.clip(AvatarShape).background(color), contentAlignment = Alignment.Center) {
        when {
            url != null -> AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            person != null -> Canvas(Modifier.fillMaxSize()) { drawPerson(person) }
            avatar in 16 until 24 -> Canvas(Modifier.fillMaxSize()) { drawAnimal(Animal.entries[avatar - 16], ANIMAL_BG[avatar - 16]) }
            avatar in 24 until 32 -> Canvas(Modifier.fillMaxSize()) { drawFantasy(Fantasy.entries[avatar - 24], FANTASY_BG[avatar - 24]) }
            else -> Text(name.trim().take(1).uppercase(), style = TextStyle(fontSize = (size.value * .42f).sp, color = Color.White))
        }
    }
}

@Composable
fun Avatar(p: tv.nakash.data.profile.Profile, size: Dp, modifier: Modifier = Modifier) = Avatar(p.avatar, p.name, Color(p.color), size, modifier, p.avatarUrl)

private fun DrawScope.drawPerson(p: Person) {
    val s = size.minDimension
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    fun z(w: Float, h: Float) = Size(w * s, h * s)
    val ink = Color(0xFF2A2321)
    val shade = lerp(p.skin, Color.Black, .14f)

    drawRect(Brush.linearGradient(listOf(p.bg1, p.bg2), start = Offset.Zero, end = Offset(s, s)))

    // Hair behind the head.
    when (p.style) {
        Hair.Long -> drawRoundRect(p.hair, o(.22f, .2f), z(.56f, .58f), CornerRadius(.22f * s))
        Hair.Afro -> drawCircle(p.hair, .31f * s, o(.5f, .4f))
        Hair.Bun -> drawCircle(p.hair, .1f * s, o(.5f, .16f))
        else -> Unit
    }
    // Shoulders and neck.
    drawOval(p.shirt, o(.13f, .74f), z(.74f, .52f))
    drawRect(shade, o(.43f, .6f), z(.14f, .16f))
    drawArc(shade, 0f, 180f, true, o(.41f, .72f), z(.18f, .08f))
    // Ears, then the face.
    drawCircle(p.skin, .045f * s, o(.29f, .48f)); drawCircle(p.skin, .045f * s, o(.71f, .48f))
    drawOval(p.skin, o(.29f, .25f), z(.42f, .46f))

    // Hair in front.
    when (p.style) {
        Hair.Short, Hair.Long, Hair.Bun -> {
            drawArc(p.hair, 180f, 180f, true, o(.27f, .21f), z(.46f, .34f))
            if (p.style == Hair.Long) drawArc(p.hair, 200f, 120f, true, o(.27f, .27f), z(.3f, .2f))
        }
        Hair.Curly -> {
            drawArc(p.hair, 180f, 180f, true, o(.27f, .22f), z(.46f, .32f))
            for (a in 180..360 step 30) {
                val r = Math.toRadians(a.toDouble())
                drawCircle(p.hair, .075f * s, o(.5f + .2f * cos(r).toFloat(), .37f + .14f * sin(r).toFloat()))
            }
        }
        Hair.Afro -> drawArc(p.hair, 180f, 180f, true, o(.28f, .23f), z(.44f, .28f))
        Hair.Bald -> {
            drawOval(p.hair, o(.27f, .34f), z(.07f, .15f)); drawOval(p.hair, o(.66f, .34f), z(.07f, .15f))
        }
        Hair.Spiky -> {
            drawArc(p.hair, 180f, 180f, true, o(.27f, .22f), z(.46f, .32f))
            val spikes = Path().apply {
                moveTo(.28f * s, .36f * s)
                listOf(.33f to .14f, .39f to .3f, .45f to .1f, .51f to .28f, .58f to .11f, .63f to .3f, .7f to .17f).forEach { (x, y) -> lineTo(x * s, y * s) }
                lineTo(.72f * s, .36f * s); close()
            }
            drawPath(spikes, p.hair)
        }
    }
    p.cap?.let { c ->
        drawArc(c, 180f, 180f, true, o(.27f, .18f), z(.46f, .36f))
        drawOval(lerp(c, Color.Black, .2f), o(.24f, .33f), z(.52f, .065f))
    }

    // Face: brows, eyes, cheeks, mouth.
    val brow = Stroke(.016f * s, cap = StrokeCap.Round)
    drawLine(lerp(p.hair, ink, .5f), o(.38f, .425f), o(.45f, .415f), brow.width, StrokeCap.Round)
    drawLine(lerp(p.hair, ink, .5f), o(.55f, .415f), o(.62f, .425f), brow.width, StrokeCap.Round)
    for (x in listOf(.415f, .585f)) {
        drawCircle(ink, .026f * s, o(x, .48f)); drawCircle(Color.White, .009f * s, o(x + .009f, .471f))
    }
    drawCircle(Color(0xFFFF7A8A).copy(alpha = .35f), .035f * s, o(.37f, .56f))
    drawCircle(Color(0xFFFF7A8A).copy(alpha = .35f), .035f * s, o(.63f, .56f))
    if (p.beard) drawArc(p.hair, 0f, 180f, true, o(.29f, .44f), z(.42f, .28f))
    if (p.moustache) { drawOval(p.hair, o(.41f, .555f), z(.09f, .035f)); drawOval(p.hair, o(.5f, .555f), z(.09f, .035f)) }
    drawArc(if (p.beard) Color(0xFFFFE3D3) else Color(0xFF8A2E2E), 20f, 140f, false, o(.44f, .53f), z(.12f, .08f), style = Stroke(.016f * s, cap = StrokeCap.Round))

    if (p.glasses) {
        val g = Stroke(.014f * s)
        drawCircle(ink, .06f * s, o(.415f, .48f), style = g); drawCircle(ink, .06f * s, o(.585f, .48f), style = g)
        drawLine(ink, o(.475f, .478f), o(.525f, .478f), g.width)
    }
    if (p.headphones) {
        drawArc(ink, 190f, 160f, false, o(.24f, .17f), z(.52f, .5f), style = Stroke(.035f * s, cap = StrokeCap.Round))
        drawRoundRect(ink, o(.22f, .41f), z(.08f, .15f), CornerRadius(.03f * s))
        drawRoundRect(ink, o(.7f, .41f), z(.08f, .15f), CornerRadius(.03f * s))
    }
}

private class Pen(val s: Float) {
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    fun z(w: Float, h: Float) = Size(w * s, h * s)
    fun r(v: Float) = v * s
}

private val INK = Color(0xFF231F20)

private fun DrawScope.eyes(p: Pen, y: Float, dx: Float, r: Float, iris: Color = INK) {
    for (x in listOf(.5f - dx, .5f + dx)) { drawCircle(iris, p.r(r), p.o(x, y)); drawCircle(Color.White, p.r(r * .38f), p.o(x + r * .3f, y - r * .3f)) }
}

private fun DrawScope.tri(p: Pen, color: Color, vararg pts: Pair<Float, Float>) =
    drawPath(Path().apply { moveTo(p.r(pts[0].first), p.r(pts[0].second)); pts.drop(1).forEach { lineTo(p.r(it.first), p.r(it.second)) }; close() }, color)

private fun DrawScope.drawAnimal(a: Animal, bg: Pair<Color, Color>) {
    val p = Pen(size.minDimension)
    drawRect(Brush.linearGradient(listOf(bg.first, bg.second), end = Offset(p.s, p.s)))
    val smile = Stroke(p.r(.016f), cap = StrokeCap.Round)
    when (a) {
        Animal.Cat -> {
            val fur = Color(0xFFF4A261)
            tri(p, fur, .22f to .44f, .28f to .14f, .46f to .3f); tri(p, fur, .78f to .44f, .72f to .14f, .54f to .3f)
            tri(p, Color(0xFFFFB4A2), .27f to .38f, .3f to .22f, .4f to .31f); tri(p, Color(0xFFFFB4A2), .73f to .38f, .7f to .22f, .6f to .31f)
            drawOval(fur, p.o(.2f, .26f), p.z(.6f, .56f))
            drawOval(Color(0xFFFFF1E6), p.o(.36f, .52f), p.z(.28f, .22f))
            drawOval(Color(0xFF7CB518), p.o(.33f, .41f), p.z(.1f, .12f)); drawOval(Color(0xFF7CB518), p.o(.57f, .41f), p.z(.1f, .12f))
            drawOval(INK, p.o(.365f, .415f), p.z(.035f, .1f)); drawOval(INK, p.o(.605f, .415f), p.z(.035f, .1f))
            tri(p, Color(0xFFE76F8B), .46f to .55f, .54f to .55f, .5f to .6f)
            for (k in listOf(-1f, 1f)) for (dy in listOf(-.02f, .02f)) drawLine(INK.copy(alpha = .6f), p.o(.5f + k * .1f, .6f + dy), p.o(.5f + k * .3f, .58f + dy * 2), p.r(.008f))
            drawArc(INK, 20f, 140f, false, p.o(.44f, .58f), p.z(.12f, .07f), style = smile)
        }
        Animal.Dog -> {
            val fur = Color(0xFFC68B59); val ear = Color(0xFF7F5539)
            drawOval(ear, p.o(.14f, .28f), p.z(.18f, .4f)); drawOval(ear, p.o(.68f, .28f), p.z(.18f, .4f))
            drawOval(fur, p.o(.24f, .22f), p.z(.52f, .58f))
            drawOval(Color(0xFFF2D8B8), p.o(.34f, .5f), p.z(.32f, .26f))
            eyes(p, .43f, .1f, .04f)
            drawOval(INK, p.o(.44f, .52f), p.z(.12f, .08f))
            drawArc(INK, 20f, 140f, false, p.o(.42f, .58f), p.z(.16f, .08f), style = smile)
            drawRoundRect(Color(0xFFE5677B), p.o(.47f, .64f), p.z(.06f, .08f), CornerRadius(p.r(.03f)))
        }
        Animal.Panda -> {
            drawCircle(INK, p.r(.1f), p.o(.28f, .26f)); drawCircle(INK, p.r(.1f), p.o(.72f, .26f))
            drawOval(Color.White, p.o(.2f, .22f), p.z(.6f, .58f))
            drawOval(INK, p.o(.3f, .38f), p.z(.15f, .17f)); drawOval(INK, p.o(.55f, .38f), p.z(.15f, .17f))
            drawCircle(Color.White, p.r(.035f), p.o(.39f, .46f)); drawCircle(Color.White, p.r(.035f), p.o(.61f, .46f))
            drawCircle(INK, p.r(.018f), p.o(.39f, .465f)); drawCircle(INK, p.r(.018f), p.o(.61f, .465f))
            drawOval(INK, p.o(.45f, .56f), p.z(.1f, .06f))
            drawArc(INK, 20f, 140f, false, p.o(.44f, .6f), p.z(.12f, .07f), style = smile)
            drawCircle(Color(0xFFFF8FA3).copy(alpha = .5f), p.r(.04f), p.o(.3f, .62f)); drawCircle(Color(0xFFFF8FA3).copy(alpha = .5f), p.r(.04f), p.o(.7f, .62f))
        }
        Animal.Fox -> {
            val fur = Color(0xFFF77F00)
            tri(p, fur, .18f to .46f, .22f to .1f, .46f to .32f); tri(p, fur, .82f to .46f, .78f to .1f, .54f to .32f)
            tri(p, INK, .21f to .2f, .22f to .1f, .3f to .17f); tri(p, INK, .79f to .2f, .78f to .1f, .7f to .17f)
            drawPath(Path().apply { moveTo(p.r(.16f), p.r(.42f)); quadraticTo(p.r(.5f), p.r(.12f), p.r(.84f), p.r(.42f)); lineTo(p.r(.5f), p.r(.8f)); close() }, fur)
            drawPath(Path().apply { moveTo(p.r(.22f), p.r(.52f)); lineTo(p.r(.44f), p.r(.56f)); lineTo(p.r(.5f), p.r(.8f)); lineTo(p.r(.56f), p.r(.56f)); lineTo(p.r(.78f), p.r(.52f)); lineTo(p.r(.5f), p.r(.82f)); close() }, Color(0xFFFFF1E6))
            eyes(p, .45f, .11f, .035f)
            drawOval(INK, p.o(.46f, .68f), p.z(.08f, .06f))
        }
        Animal.Bear -> {
            val fur = Color(0xFF8D5524)
            drawCircle(fur, p.r(.1f), p.o(.27f, .27f)); drawCircle(fur, p.r(.1f), p.o(.73f, .27f))
            drawCircle(Color(0xFFD4A373), p.r(.05f), p.o(.27f, .27f)); drawCircle(Color(0xFFD4A373), p.r(.05f), p.o(.73f, .27f))
            drawOval(fur, p.o(.2f, .22f), p.z(.6f, .58f))
            drawOval(Color(0xFFD4A373), p.o(.36f, .5f), p.z(.28f, .22f))
            eyes(p, .43f, .11f, .035f)
            drawOval(INK, p.o(.45f, .53f), p.z(.1f, .07f))
            drawArc(INK, 20f, 140f, false, p.o(.44f, .58f), p.z(.12f, .07f), style = smile)
        }
        Animal.Rabbit -> {
            val fur = Color(0xFFF1F1F1)
            drawOval(fur, p.o(.29f, .02f), p.z(.14f, .4f)); drawOval(fur, p.o(.57f, .02f), p.z(.14f, .4f))
            drawOval(Color(0xFFFFB4C2), p.o(.325f, .07f), p.z(.07f, .3f)); drawOval(Color(0xFFFFB4C2), p.o(.605f, .07f), p.z(.07f, .3f))
            drawOval(fur, p.o(.22f, .3f), p.z(.56f, .52f))
            eyes(p, .5f, .11f, .035f)
            drawOval(Color(0xFFE76F8B), p.o(.47f, .58f), p.z(.06f, .04f))
            drawRect(Color.White, p.o(.465f, .64f), p.z(.07f, .07f)); drawLine(INK.copy(alpha = .4f), p.o(.5f, .64f), p.o(.5f, .71f), p.r(.006f))
            drawCircle(Color(0xFFFF8FA3).copy(alpha = .45f), p.r(.04f), p.o(.32f, .62f)); drawCircle(Color(0xFFFF8FA3).copy(alpha = .45f), p.r(.04f), p.o(.68f, .62f))
        }
        Animal.Owl -> {
            val body = Color(0xFF6D4C41)
            tri(p, body, .24f to .3f, .26f to .1f, .4f to .24f); tri(p, body, .76f to .3f, .74f to .1f, .6f to .24f)
            drawOval(body, p.o(.18f, .18f), p.z(.64f, .76f))
            drawOval(Color(0xFFD7CCC8), p.o(.32f, .58f), p.z(.36f, .3f))
            drawCircle(Color.White, p.r(.12f), p.o(.37f, .42f)); drawCircle(Color.White, p.r(.12f), p.o(.63f, .42f))
            drawCircle(Color(0xFFFFB703), p.r(.075f), p.o(.37f, .42f)); drawCircle(Color(0xFFFFB703), p.r(.075f), p.o(.63f, .42f))
            drawCircle(INK, p.r(.04f), p.o(.37f, .42f)); drawCircle(INK, p.r(.04f), p.o(.63f, .42f))
            tri(p, Color(0xFFF77F00), .46f to .5f, .54f to .5f, .5f to .6f)
        }
        Animal.Lion -> {
            drawCircle(Color(0xFFB5651D), p.r(.36f), p.o(.5f, .5f))
            for (k in 0 until 12) { val a = Math.toRadians(k * 30.0); drawCircle(Color(0xFF9C4A1A), p.r(.09f), p.o(.5f + .32f * cos(a).toFloat(), .5f + .32f * sin(a).toFloat())) }
            drawCircle(Color(0xFFF4C430), p.r(.25f), p.o(.5f, .52f))
            drawCircle(Color(0xFFF4C430), p.r(.06f), p.o(.32f, .32f)); drawCircle(Color(0xFFF4C430), p.r(.06f), p.o(.68f, .32f))
            drawOval(Color(0xFFFFF1C1), p.o(.38f, .55f), p.z(.24f, .16f))
            eyes(p, .47f, .09f, .03f)
            tri(p, Color(0xFF6B3E26), .45f to .56f, .55f to .56f, .5f to .62f)
            drawArc(INK, 20f, 140f, false, p.o(.44f, .6f), p.z(.12f, .06f), style = smile)
        }
    }
}

private fun DrawScope.drawFantasy(f: Fantasy, bg: Pair<Color, Color>) {
    val p = Pen(size.minDimension)
    drawRect(Brush.linearGradient(listOf(bg.first, bg.second), end = Offset(p.s, p.s)))
    val smile = Stroke(p.r(.018f), cap = StrokeCap.Round)
    when (f) {
        Fantasy.Robot -> {
            drawLine(Color(0xFFADB5BD), p.o(.5f, .1f), p.o(.5f, .22f), p.r(.02f)); drawCircle(Color(0xFFFF4D6D), p.r(.04f), p.o(.5f, .1f))
            drawRoundRect(Color(0xFFCED4DA), p.o(.22f, .22f), p.z(.56f, .5f), CornerRadius(p.r(.1f)))
            drawRoundRect(Color(0xFFADB5BD), p.o(.15f, .38f), p.z(.08f, .16f), CornerRadius(p.r(.03f))); drawRoundRect(Color(0xFFADB5BD), p.o(.77f, .38f), p.z(.08f, .16f), CornerRadius(p.r(.03f)))
            drawRoundRect(Color(0xFF212529), p.o(.3f, .32f), p.z(.4f, .18f), CornerRadius(p.r(.06f)))
            drawCircle(Color(0xFF4CC9F0), p.r(.045f), p.o(.4f, .41f)); drawCircle(Color(0xFF4CC9F0), p.r(.045f), p.o(.6f, .41f))
            for (k in 0 until 5) drawRect(Color(0xFF495057), p.o(.36f + k * .06f, .57f), p.z(.04f, .07f))
            drawOval(Color(0xFF6C757D), p.o(.22f, .78f), p.z(.56f, .3f))
        }
        Fantasy.Alien -> {
            drawLine(Color(0xFF80ED99), p.o(.38f, .24f), p.o(.3f, .1f), p.r(.018f)); drawLine(Color(0xFF80ED99), p.o(.62f, .24f), p.o(.7f, .1f), p.r(.018f))
            drawCircle(Color(0xFFFFD60A), p.r(.035f), p.o(.3f, .1f)); drawCircle(Color(0xFFFFD60A), p.r(.035f), p.o(.7f, .1f))
            drawPath(Path().apply { moveTo(p.r(.2f), p.r(.42f)); cubicTo(p.r(.2f), p.r(.12f), p.r(.8f), p.r(.12f), p.r(.8f), p.r(.42f)); cubicTo(p.r(.8f), p.r(.66f), p.r(.6f), p.r(.8f), p.r(.5f), p.r(.8f)); cubicTo(p.r(.4f), p.r(.8f), p.r(.2f), p.r(.66f), p.r(.2f), p.r(.42f)); close() }, Color(0xFF80ED99))
            drawOval(INK, p.o(.29f, .38f), p.z(.17f, .12f)); drawOval(INK, p.o(.54f, .38f), p.z(.17f, .12f))
            drawCircle(Color.White, p.r(.02f), p.o(.34f, .41f)); drawCircle(Color.White, p.r(.02f), p.o(.59f, .41f))
            drawArc(INK, 20f, 140f, false, p.o(.44f, .56f), p.z(.12f, .07f), style = smile)
        }
        Fantasy.Monster -> {
            tri(p, Color(0xFFFFF3B0), .3f to .3f, .26f to .1f, .4f to .26f); tri(p, Color(0xFFFFF3B0), .7f to .3f, .74f to .1f, .6f to .26f)
            drawRoundRect(Color(0xFF9D4EDD), p.o(.18f, .2f), p.z(.64f, .7f), CornerRadius(p.r(.28f)))
            drawCircle(Color.White, p.r(.13f), p.o(.5f, .42f)); drawCircle(Color(0xFF06D6A0), p.r(.07f), p.o(.5f, .42f)); drawCircle(INK, p.r(.035f), p.o(.5f, .42f))
            drawArc(INK, 0f, 180f, true, p.o(.34f, .54f), p.z(.32f, .2f))
            tri(p, Color.White, .38f to .64f, .43f to .64f, .405f to .7f); tri(p, Color.White, .57f to .64f, .62f to .64f, .595f to .7f)
        }
        Fantasy.Ghost -> {
            drawPath(Path().apply {
                moveTo(p.r(.24f), p.r(.84f)); lineTo(p.r(.24f), p.r(.44f)); cubicTo(p.r(.24f), p.r(.1f), p.r(.76f), p.r(.1f), p.r(.76f), p.r(.44f)); lineTo(p.r(.76f), p.r(.84f))
                for (k in 0 until 4) { val x = .76f - k * .13f; quadraticTo(p.r(x - .035f), p.r(.76f), p.r(x - .065f), p.r(.84f)); quadraticTo(p.r(x - .095f), p.r(.92f), p.r(x - .13f), p.r(.84f)) }
                close() }, Color.White)
            drawOval(INK, p.o(.37f, .36f), p.z(.08f, .12f)); drawOval(INK, p.o(.55f, .36f), p.z(.08f, .12f))
            drawOval(INK, p.o(.45f, .54f), p.z(.1f, .1f))
            drawCircle(Color(0xFFFF8FA3).copy(alpha = .5f), p.r(.04f), p.o(.33f, .52f)); drawCircle(Color(0xFFFF8FA3).copy(alpha = .5f), p.r(.04f), p.o(.67f, .52f))
        }
        Fantasy.Astronaut -> {
            drawOval(Color.White, p.o(.16f, .7f), p.z(.68f, .4f))
            drawCircle(Color(0xFFE9ECEF), p.r(.3f), p.o(.5f, .44f))
            drawRoundRect(Color(0xFF14213D), p.o(.27f, .28f), p.z(.46f, .32f), CornerRadius(p.r(.14f)))
            drawOval(Color.White.copy(alpha = .35f), p.o(.32f, .31f), p.z(.14f, .07f))
            drawCircle(Color(0xFFFF6B6B), p.r(.035f), p.o(.5f, .82f))
            for (k in 0 until 6) drawCircle(Color.White.copy(alpha = .8f), p.r(.008f), p.o(listOf(.1f, .86f, .14f, .9f, .82f, .2f)[k], listOf(.12f, .2f, .6f, .5f, .9f, .9f)[k]))
        }
        Fantasy.Ninja -> {
            drawOval(Color(0xFF212529), p.o(.14f, .74f), p.z(.72f, .4f))
            drawCircle(Color(0xFF212529), p.r(.3f), p.o(.5f, .46f))
            drawRoundRect(Color(0xFFF1CDB0), p.o(.28f, .38f), p.z(.44f, .14f), CornerRadius(p.r(.07f)))
            drawRect(Color(0xFFE63946), p.o(.2f, .27f), p.z(.6f, .06f))
            tri(p, Color(0xFFE63946), .78f to .28f, .9f to .22f, .88f to .36f)
            drawLine(INK, p.o(.36f, .42f), p.o(.46f, .44f), p.r(.02f), StrokeCap.Round); drawLine(INK, p.o(.64f, .42f), p.o(.54f, .44f), p.r(.02f), StrokeCap.Round)
            eyes(p, .47f, .09f, .028f)
        }
        Fantasy.Wizard -> {
            drawOval(Color(0xFF5A189A), p.o(.14f, .78f), p.z(.72f, .36f))
            drawOval(Color(0xFFF1CDB0), p.o(.32f, .32f), p.z(.36f, .36f))
            drawPath(Path().apply { moveTo(p.r(.3f), p.r(.52f)); quadraticTo(p.r(.5f), p.r(.98f), p.r(.7f), p.r(.52f)); close() }, Color(0xFFF8F9FA))
            tri(p, Color(0xFF3C096C), .22f to .36f, .78f to .36f, .56f to .02f)
            drawOval(Color(0xFF3C096C), p.o(.16f, .32f), p.z(.68f, .08f))
            drawCircle(Color(0xFFFFD60A), p.r(.025f), p.o(.48f, .2f)); drawCircle(Color(0xFFFFD60A), p.r(.018f), p.o(.58f, .14f))
            eyes(p, .45f, .07f, .025f)
        }
        Fantasy.Hero -> {
            drawPath(Path().apply { moveTo(p.r(.1f), p.r(1f)); lineTo(p.r(.3f), p.r(.66f)); lineTo(p.r(.7f), p.r(.66f)); lineTo(p.r(.9f), p.r(1f)); close() }, Color(0xFFE63946))
            drawOval(Color(0xFF1D4ED8), p.o(.2f, .74f), p.z(.6f, .4f))
            tri(p, Color(0xFFFFD60A), .44f to .8f, .56f to .8f, .5f to .9f)
            drawOval(Color(0xFFF1CDB0), p.o(.3f, .24f), p.z(.4f, .46f))
            drawArc(Color(0xFF231815), 180f, 180f, true, p.o(.28f, .2f), p.z(.44f, .3f))
            drawRoundRect(Color(0xFF1D4ED8), p.o(.3f, .38f), p.z(.4f, .1f), CornerRadius(p.r(.05f)))
            drawCircle(Color.White, p.r(.026f), p.o(.41f, .43f)); drawCircle(Color.White, p.r(.026f), p.o(.59f, .43f))
            drawArc(Color(0xFF8A2E2E), 20f, 140f, false, p.o(.44f, .52f), p.z(.12f, .08f), style = smile)
        }
    }
}
