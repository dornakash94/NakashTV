package tv.nakash.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.nakash.data.profile.Profile
import tv.nakash.ui.profile.Avatar
import tv.nakash.ui.theme.NakashColors

private val Panel = Color(0xF5141416)

/**
 * Netflix-style dropdown under the profile avatar in the nav bar: who is watching, the other profiles to switch to
 * in one click, then manage profiles and leave the app.
 */
@Composable
fun ProfileMenu(current: Profile, others: List<Profile>, onChoose: (Profile) -> Unit, onManage: () -> Unit, onExit: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { delay(60); if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(20.dp, 10.dp)) {
            drawPath(Path().apply { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height); close() }, Panel)
        }
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(18.dp)).background(Panel)
                .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(18.dp)).padding(vertical = 10.dp),
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(current, 52.dp, Modifier.size(52.dp))
                Column {
                    Text(current.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("צופה עכשיו", color = NakashColors.Dim, fontSize = 13.sp)
                }
            }
            Line()
            others.forEachIndexed { i, p ->
                MenuItem(p.name, if (i == 0) Modifier.focusRequester(first) else Modifier, { onChoose(p) }) {
                    Avatar(p, 30.dp, Modifier.size(30.dp))
                }
            }
            if (others.isNotEmpty()) Line()
            MenuItem("ניהול פרופילים", if (others.isEmpty()) Modifier.focusRequester(first) else Modifier, onManage) { Icon(Icons.Outlined.ManageAccounts, null, Modifier.size(24.dp)) }
            MenuItem("יציאה מהאפליקציה", Modifier, onExit) { Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun Line() = Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .1f)))

@Composable
private fun MenuItem(label: String, modifier: Modifier, onClick: () -> Unit, lead: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = NakashColors.Muted,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { lead() }
            Text(label, fontSize = 16.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}
