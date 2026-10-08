package tv.nakash.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.nakash.data.profile.Profile
import tv.nakash.data.profile.ProfileStore
import tv.nakash.data.profile.ProfileSync
import tv.nakash.data.repo.ProfilePick
import tv.nakash.data.repo.RecommendationRepository
import tv.nakash.data.repo.UserRepository
import tv.nakash.ui.library.SearchField
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    val store: ProfileStore, private val sync: ProfileSync, private val user: UserRepository, private val recs: RecommendationRepository,
) : ViewModel() {
    private val _picks = MutableStateFlow<Map<String, List<ProfilePick>>>(emptyMap())
    /** Per profile, titles picked for it, shown in turn behind it on "מי צופה?". */
    val picks: StateFlow<Map<String, List<ProfilePick>>> = _picks

    init {
        viewModelScope.launch { user.migrateLegacy(); sync.syncNow(); sync.start() }
    }
    fun loadPicks() = viewModelScope.launch {
        store.visible.forEach { p -> runCatching { recs.picksFor(p.id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { _picks.value = _picks.value + (p.id to it) } }
    }
    fun choose(p: Profile) = store.choose(p)
    fun add(name: String, avatar: Int, url: String?) { store.add(name, avatar, url); viewModelScope.launch { sync.syncNow() } }
    fun update(p: Profile, name: String, avatar: Int, url: String?) { store.update(p, name, avatar, url); viewModelScope.launch { sync.syncNow() } }
    fun remove(p: Profile) { store.remove(p); viewModelScope.launch { sync.syncNow() } }
}

/**
 * "מי צופה?" (Netflix TV style): the profiles in a column on the right over a big picture of a title picked for
 * the focused profile from what it watches. The pencil next to a profile (or a long press) edits it.
 */
@Composable
fun ProfilePickerScreen(vm: ProfilePickerViewModel = hiltViewModel()) {
    val all by vm.store.all.collectAsState()
    val picks by vm.picks.collectAsState()
    val profiles = all.filter { !it.deleted }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val lastId = remember { vm.store.lastId }
    var focusedId by remember { mutableStateOf(lastId) }
    LaunchedEffect(profiles.map { it.id }) { vm.loadPicks() }
    LaunchedEffect(profiles.size, editing, adding) { if (editing == null && !adding) { delay(150); runCatching { first.requestFocus() } } }

    if (editing != null || adding) {
        val p = editing
        ProfileEditor(p, vm, close = { editing = null; adding = false },
            onDelete = if (p != null && profiles.size > 1) ({ vm.remove(p); editing = null }) else null) { n, a, u ->
            if (p != null) vm.update(p, n, a, u) else vm.add(n, a, u); editing = null; adding = false
        }
        return
    }

    // While a profile stays focused its picks take turns (a new one every few seconds); a new profile starts at its first.
    val list = picks[focusedId].orEmpty()
    var turn by remember(focusedId) { mutableIntStateOf(0) }
    LaunchedEffect(focusedId, list.size) { while (list.size > 1) { delay(6_000); turn++ } }
    val pick = list.getOrNull(turn % list.size.coerceAtLeast(1))
    Box(Modifier.fillMaxSize().background(NakashColors.Bg)) {
        Crossfade(pick?.backdrop, animationSpec = tween(900), label = "backdrop") { url ->
            if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else PickerGlow(Modifier.fillMaxSize())
        }
        // The list side (right) dark, the picture clear on the left; a soft floor for the caption.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent, .45f to Color.Black.copy(alpha = .25f), .78f to Color.Black.copy(alpha = .86f), 1f to Color.Black.copy(alpha = .95f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f))))
        Crossfade(pick, animationSpec = tween(900), modifier = Modifier.align(Alignment.BottomEnd), label = "caption") { pk -> if (pk != null)
            Column(Modifier.padding(start = 48.dp, end = 56.dp, bottom = 44.dp).widthIn(max = 520.dp)) {
                Text(pk.reason, color = NakashColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(pk.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // The logo in the top corner, apart from the profiles (in the column it read as one more tile).
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(tv.nakash.R.drawable.ic_logo), "NakashTV",
            Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 36.dp).size(48.dp))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val start = profiles.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
            profiles.forEachIndexed { i, p ->
                ProfileRow(p, if (i == start) Modifier.focusRequester(first) else Modifier, onFocus = { focusedId = p.id }, click = { vm.choose(p) }, edit = { editing = p })
            }
            if (profiles.size < 6) AddRow { adding = true }
        }
    }
}

/** The violet / gold glow used where there is no picture. */
@Composable
fun PickerGlow(modifier: Modifier) = Box(modifier.background(NakashColors.Bg)
    .background(Brush.radialGradient(listOf(Color(0xFF3B1F6E).copy(alpha = .8f), Color.Transparent), center = Offset(1500f, -150f), radius = 1500f))
    .background(Brush.radialGradient(listOf(NakashColors.Accent.copy(alpha = .22f), Color.Transparent), center = Offset(200f, 1250f), radius = 1100f))
    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .35f)))))

@Composable
private fun ProfileRow(p: Profile, modifier: Modifier, onFocus: () -> Unit, click: () -> Unit, edit: () -> Unit) {
    var rowFocus by remember { mutableStateOf(false) }
    var tileFocus by remember { mutableStateOf(false) }
    Row(Modifier.onFocusChanged { rowFocus = it.hasFocus }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // The pencil on the right of the picture (Netflix): ► from the picture reaches it; shown while the row has focus.
        Surface(onClick = edit, modifier = Modifier.size(40.dp).alpha(if (rowFocus) 1f else 0f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Edit, "עריכה", Modifier.size(22.dp)) }
        }
        Surface(onClick = click, onLongClick = edit, modifier = modifier.size(84.dp).onFocusChanged { tileFocus = it.isFocused; if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(AvatarShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.14f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = AvatarShape))) {
            Avatar(p, 84.dp, Modifier.fillMaxSize())
        }
        Text(p.name, color = if (tileFocus) Color.White else Color.White.copy(alpha = .55f), fontSize = if (tileFocus) 24.sp else 19.sp,
            fontWeight = if (tileFocus) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp))
    }
}

@Composable
private fun AddRow(click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.width(40.dp))   // lines up with the profiles' pencil column
        Surface(onClick = click, modifier = Modifier.size(84.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(AvatarShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.14f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
            border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(2.dp, Color.White.copy(alpha = .6f)), shape = AvatarShape))) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("+", fontSize = 40.sp) }
        }
        Text("הוספת פרופיל", color = if (focused) Color.White else Color.White.copy(alpha = .55f), fontSize = if (focused) 22.sp else 18.sp)
    }
}

/**
 * Editing / creating a profile (Netflix TV style), full screen: the menu on the right (name, picture), Done / Delete
 * under it, and the profile as it will look on the left. "תמונת פרופיל" opens the avatar gallery.
 */
@Composable
private fun ProfileEditor(initial: Profile?, vm: ProfilePickerViewModel, close: () -> Unit, onDelete: (() -> Unit)?, save: (String, Int, String?) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var avatar by remember { mutableStateOf(initial?.avatar ?: (vm.store.visible.size % AVATAR_COUNT)) }
    var url by remember { mutableStateOf(initial?.avatarUrl) }
    var editingName by remember { mutableStateOf(initial == null) }
    var gallery by remember { mutableStateOf(false) }
    val color = Color(initial?.color ?: ProfileStore.COLORS[vm.store.visible.size % ProfileStore.COLORS.size])
    val nameFocus = remember { FocusRequester() }
    val pictureFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(!gallery) { if (editingName && initial != null) editingName = false else close() }

    if (gallery) {
        AvatarGallery(avatar, url, name, color, close = { gallery = false }) { a, u -> avatar = a; url = u; gallery = false }
        return
    }
    LaunchedEffect(editingName) { delay(150); runCatching { if (editingName) nameFocus.requestFocus() else pictureFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        PickerGlow(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.CenterStart).padding(start = 72.dp).width(420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (initial == null) "פרופיל חדש" else "עריכת פרופיל", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("כאן בוחרים מה לשנות.", color = Color.White.copy(alpha = .7f), fontSize = 17.sp)
            Spacer(Modifier.height(26.dp))
            if (editingName) SearchField(name, { name = it.take(20) }, "שם", Modifier.focusRequester(nameFocus).onFocusChanged { if (!it.hasFocus && name.isNotBlank()) editingName = false })
            else EditorItem(Icons.Outlined.Person, "שם", name.ifBlank { "ללא שם" }, Modifier) { editingName = true }
            // ▼ from the menu always lands on "בוצע" (never on "מחיקת פרופיל" just because it sits below).
            EditorItem(Icons.Outlined.Face, "תמונת פרופיל", null, Modifier.focusRequester(pictureFocus).focusProperties { down = doneFocus }) { gallery = true }
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("בוצע", primary = true, Modifier.focusRequester(doneFocus)) { save(name, avatar, url) }
                if (onDelete != null) Pill(if (confirmDelete) "בטוח? לחיצה נוספת מוחקת" else "מחיקת פרופיל", primary = false,
                    Modifier.onFocusChanged { if (!it.isFocused) confirmDelete = false }) { if (confirmDelete) onDelete() else confirmDelete = true }
                Pill("ביטול", primary = false, click = close)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 140.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Avatar(avatar, name, color, 230.dp, Modifier.size(230.dp), url)
            Text(name.ifBlank { " " }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A menu row of the editor: icon, label and current value; a white pill when focused. */
@Composable
private fun EditorItem(icon: ImageVector, label: String, value: String?, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier.fillMaxWidth().height(54.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(27.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(26.dp))
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            value?.let { Text(it, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(.65f)) }
        }
    }
}

/** A rounded button: primary is solid white, the others are quiet until focused. */
@Composable
private fun Pill(label: String, primary: Boolean, modifier: Modifier = Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier.height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White.copy(alpha = .85f) else Color.White.copy(alpha = .1f), contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black)) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 30.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * The avatar gallery (Netflix style): rows of drawn characters — people, animals and fantasy figures. All drawn on
 * the device, so it opens instantly and works offline.
 */
@Composable
private fun AvatarGallery(avatar: Int, url: String?, name: String, color: Color, close: () -> Unit, pick: (Int, String?) -> Unit) {
    BackHandler(onBack = close)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(NakashColors.Bg)) {
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("בחירת תמונת פרופיל", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("כאן מעדכנים את סמל הפרופיל.", color = Color.White.copy(alpha = .7f), fontSize = 16.sp)
                }
                Avatar(avatar, name, color, 64.dp, Modifier.size(64.dp), url)
            }
            Spacer(Modifier.height(18.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(AVATAR_ROWS, key = { _, r -> r.first }) { i, (rowName, ids) ->
                    GalleryRow(rowName, ids.map { it to null }, color, name, if (i == 0) first else null, pick)
                }
            }
        }
    }
}

@Composable
private fun GalleryRow(title: String, faces: List<Pair<Int, String?>>, color: Color, name: String, firstFocus: FocusRequester?, pick: (Int, String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 56.dp, end = 18.dp).width(170.dp))
        LazyRow(contentPadding = PaddingValues(start = 14.dp, end = 56.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(faces) { i, (a, u) ->
                Surface(onClick = { pick(a, u) }, modifier = Modifier.size(118.dp).then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(AvatarShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = AvatarShape))) {
                    Avatar(a, name, color, 118.dp, Modifier.fillMaxSize(), u)
                }
            }
        }
    }
}
