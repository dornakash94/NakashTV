package tv.nakash.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.nakash.data.profile.Profile
import tv.nakash.data.profile.ProfileStore
import tv.nakash.data.profile.ProfileSync
import tv.nakash.data.repo.UserRepository
import tv.nakash.ui.library.Action
import tv.nakash.ui.library.SearchField
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

@HiltViewModel
class ProfilePickerViewModel @Inject constructor(val store: ProfileStore, private val sync: ProfileSync, private val user: UserRepository) : ViewModel() {
    init {
        viewModelScope.launch { user.migrateLegacy(); sync.syncNow(); sync.start() }
    }
    fun choose(p: Profile) = store.choose(p)
    fun add(name: String) { store.add(name); viewModelScope.launch { sync.syncNow() } }
    fun rename(p: Profile, name: String) { store.rename(p, name); viewModelScope.launch { sync.syncNow() } }
    fun remove(p: Profile) { store.remove(p); viewModelScope.launch { sync.syncNow() } }
}

/** "מי צופה?": the account's profiles as big circles; long press (or the menu key) edits one. */
@Composable
fun ProfilePickerScreen(vm: ProfilePickerViewModel = hiltViewModel()) {
    val all by vm.store.all.collectAsState()
    val profiles = all.filter { !it.deleted }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val lastId = remember { vm.store.lastId }
    LaunchedEffect(profiles.size) { delay(150); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(NakashColors.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(44.dp)) {
            Text("מי צופה?", style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp), color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                val start = profiles.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
                profiles.forEachIndexed { i, p ->
                    ProfileCircle(p.name, Color(p.color), if (i == start) Modifier.focusRequester(first) else Modifier, { vm.choose(p) }, { editing = p })
                }
                if (profiles.size < 6) ProfileCircle("הוספה", NakashColors.S3, Modifier, { adding = true }, null, plus = true)
            }
            Text("לחיצה ארוכה על פרופיל לעריכה", color = NakashColors.Dim, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (adding) NameDialog("פרופיל חדש", "", { adding = false }) { vm.add(it); adding = false }
    editing?.let { p ->
        NameDialog("עריכת פרופיל", p.name, { editing = null }, onDelete = if (profiles.size > 1) ({ vm.remove(p); editing = null }) else null) { vm.rename(p, it); editing = null }
    }
}

@Composable
private fun ProfileCircle(name: String, color: Color, modifier: Modifier, click: () -> Unit, longClick: (() -> Unit)?, plus: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(onClick = click, onLongClick = longClick, modifier = modifier.size(150.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(CircleShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color),
            border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(4.dp, Color.White), shape = CircleShape))) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (plus) "+" else name.trim().take(1).uppercase(), style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp), color = Color.White)
            }
        }
        Text(name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = if (focused) Color.White else NakashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, close: () -> Unit, onDelete: (() -> Unit)? = null, save: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(28.dp).width(520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                SearchField(draft, { draft = it.take(20) }, "שם")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("שמירה", { save(draft) })
                    onDelete?.let { Action("מחיקת פרופיל", it) }
                    Action("ביטול", close)
                }
            }
        }
    }
}
