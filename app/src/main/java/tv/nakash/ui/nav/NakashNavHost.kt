@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package tv.nakash.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.nakash.ui.guide.GuideScreen
import tv.nakash.ui.home.HomeScreen
import tv.nakash.ui.library.Action
import tv.nakash.ui.live.LiveScreen
import tv.nakash.ui.player.PlayerScreen
import tv.nakash.ui.series.SeriesDetailScreen
import tv.nakash.ui.series.SeriesScreen
import tv.nakash.ui.theme.NakashColors
import tv.nakash.ui.vod.MovieDetailScreen
import tv.nakash.ui.vod.MoviesScreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Search : Dest("search", "חיפוש", Icons.Filled.Search)
    data object Home : Dest("home", "בית", Icons.Filled.Home)
    data object Live : Dest("live", "ערוצים חיים", Icons.Outlined.Tv)
    data object Guide : Dest("guide", "לוח שידורים", Icons.Outlined.CalendarViewWeek)
    data object Movies : Dest("movies", "סרטים", Icons.Outlined.Movie)
    data object Series : Dest("series", "סדרות", Icons.Outlined.VideoLibrary)
    data object Settings : Dest("settings", "הגדרות", Icons.Filled.Settings)
    companion object {
        val all = listOf(Search, Home, Live, Guide, Movies, Series, Settings)
        val topLevel = all.map { it.route }.toSet()
        /** Sections whose own hero fills the top of the screen; the nav bar floats over it instead of pushing it down. */
        val heroRoutes = setOf(Home.route, Live.route, Movies.route, Series.route)
    }
}

/** Height of the top nav bar. Screens without a hero are padded by this much so their headers stay visible. */
val NavBarHeight: Dp = 48.dp

@Composable
fun NakashNavHost(nav: NavHostController = rememberNavController()) {
    LaunchedEffect(Unit) { if (tv.nakash.BuildConfig.DEBUG) DebugRoute.pending?.let { DebugRoute.pending = null; kotlinx.coroutines.delay(800); runCatching { nav.navigate(it) } } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val fullscreen = route.startsWith("player")
    val immersive = fullscreen || route.startsWith("movie/") || route.startsWith("seriesDetail/")
    val overlayNav = route in Dest.heroRoutes
    val contentFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val navFocus = remember { FocusRequester() }        // the nav item of the current section
    var navFocused by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    val profilesVm: tv.nakash.ui.profile.ProfilePickerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val currentProfile by profilesVm.store.current.collectAsState()
    val allProfiles by profilesVm.store.all.collectAsState()
    val scope = rememberCoroutineScope()
    fun focusNav() { scope.launch { repeat(6) { if (runCatching { navFocus.requestFocus() }.isSuccess) return@launch; kotlinx.coroutines.delay(40) } } }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    /**
     * Top-level tabs keep their state: leaving one saves it (rows, focus, loaded data) and coming back restores it,
     * instead of rebuilding the whole screen and re-reading its catalog on every visit. Home stays at the bottom.
     */
    fun goTab(tab: String) = nav.navigate(tab) { launchSingleTop = true; restoreState = true; popUpTo("home") { saveState = true } }

    /**
     * Netflix behavior: Back on a top-level screen moves the "cursor" up to the nav bar, onto the current
     * section. Back again from the nav bar goes Home; Back from the nav bar while on Home asks to exit.
     * Detail/player screens keep the normal pop.
     */
    var contentHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(route) {
        // A new screen starts with focus in its page: a stale "in the nav bar" from before (the bar is not even shown
        // on title pages) made Back go Home instead of up to this section's tab.
        navFocused = false
        // Screens put focus where it belongs themselves (the card you came back to, the first card...). Only if
        // nothing in the page has focus after a while (still loading), nudge it into the page.
        if (!fullscreen) repeat(8) {
            kotlinx.coroutines.delay(if (it == 0) 1_200 else 300)
            if (contentHasFocus) return@LaunchedEffect
            runCatching { contentFocus.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(NakashColors.Bg)) {
        // Focus in the page = not in the nav bar. The bar's own flag could stay "true" after focus moved down (rows
        // restoring focus by themselves), and Back then went Home instead of up to this section's tab.
        Box(Modifier.fillMaxSize().padding(top = if (immersive || overlayNav) 0.dp else NavBarHeight).focusRequester(contentFocus)
            .onFocusChanged { contentHasFocus = it.hasFocus; if (it.hasFocus) navFocused = false }.focusGroup()) {
            NavHost(
                nav, startDestination = "home",
                enterTransition = { fadeIn(tween(260)) }, exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(260)) }, popExitTransition = { fadeOut(tween(140)) },
            ) {
                composable("home") { HomeScreen(nav) }
                composable("live") { LiveScreen(nav) }
                composable("guide") { GuideScreen(nav) }
                composable("movies") { MoviesScreen(nav) }
                composable("series") { SeriesScreen(nav) }
                composable("search") { tv.nakash.ui.home.SearchScreen(nav) }
                composable("settings") { tv.nakash.ui.home.SettingsScreen() }
                composable("movie/{id}") { MovieDetailScreen(nav, it.arguments!!.getString("id")!!.toInt()) }
                composable("seriesDetail/{id}") { SeriesDetailScreen(nav, it.arguments!!.getString("id")!!.toInt()) }
                composable("player") { PlayerScreen(nav) }
            }
        }
        // Registered after NavHost's own back handling so this one decides: on a tab, Back goes up to the tab in the
        // nav bar (NavHost alone popped straight back to Home).
        BackHandler(enabled = !fullscreen) {
            when {
                route !in Dest.topLevel -> nav.popBackStack()
                !navFocused -> focusNav()
                route == "home" -> exitPrompt = true
                else -> goTab("home")
            }
        }
        if (exitPrompt) Dialog(onDismissRequest = { exitPrompt = false }) { Surface { Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("לצאת מ־NakashTV?", style = MaterialTheme.typography.headlineMedium)
            Action("להמשיך לצפות", { exitPrompt = false })
            Action("יציאה", { activity?.finish() })
        } } }
        if (!immersive) TopNav(
            current = route, activeFocus = navFocus, floating = overlayNav, onFocusChanged = { navFocused = it },
            // The card right under the bar (the row you see at the top); if nothing is found there, anywhere in the page.
            onDown = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) || runCatching { contentFocus.requestFocus() }.isSuccess },
            // Already on that section: just go back into its content, no reload.
            onSelect = { if (it.route == route) scope.launch { kotlinx.coroutines.delay(250); runCatching { contentFocus.requestFocus() } } else goTab(it.route) },
            profile = currentProfile, onProfile = { profileMenu = true }, menuOpen = profileMenu, onMenuDismiss = { profileMenu = false },
            menu = { p ->
                ProfileMenu(p, allProfiles.filter { !it.deleted && it.id != p.id },
                    onChoose = { profileMenu = false; profilesVm.choose(it) },
                    onManage = { profileMenu = false; profilesVm.store.signOutProfile() },
                    onExit = { profileMenu = false; activity?.finish() })
            },
        )
        // Israel time on every screen, top right (absolute: the app is RTL, so a plain TopEnd would be the left).
        tv.nakash.ui.components.IsraelClock(Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopRight).absolutePadding(top = 12.dp, right = 40.dp))
    }
}

/**
 * Netflix-TV style top nav bar: a single quiet row of text tabs, search on the leading side (right, RTL) and
 * settings on the trailing side. The current section is white and semibold, the rest muted; the focused tab is
 * a solid white pill with black text. The bar always sits on a soft top-down fade so it reads on any image.
 */
/** Debug builds only: a screen to open on start (from the launch intent), for testing on the emulator. */
object DebugRoute { @Volatile var pending: String? = null }

@Composable
fun TopNav(current: String, activeFocus: FocusRequester, floating: Boolean, onFocusChanged: (Boolean) -> Unit, onDown: () -> Boolean, onSelect: (Dest) -> Unit,
           profile: tv.nakash.data.profile.Profile? = null, onProfile: () -> Unit = {}, menuOpen: Boolean = false, onMenuDismiss: () -> Unit = {},
           menu: @Composable (tv.nakash.data.profile.Profile) -> Unit = {}) {
    val avatarFocus = remember { FocusRequester() }
    val navScope = rememberCoroutineScope()
    val fade = Brush.verticalGradient(0f to NakashColors.Bg.copy(alpha = if (floating) .92f else 1f), .6f to NakashColors.Bg.copy(alpha = if (floating) .5f else 1f), 1f to Color.Transparent)
    Box(Modifier.fillMaxWidth().height(NavBarHeight + if (floating) 40.dp else 12.dp).background(fade)) {
        Row(
            Modifier.fillMaxWidth().height(NavBarHeight).padding(horizontal = 26.dp)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                // ▼ always leads back down into the page (the bar floats over it, so a geometric search could miss it).
                .onPreviewKeyEvent { if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && it.key == androidx.compose.ui.input.key.Key.DirectionDown) onDown() else false }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Dest.all.forEach { d ->
                val active = current == d.route || (d == Dest.Movies && current.startsWith("movie/")) || (d == Dest.Series && current.startsWith("seriesDetail"))
                NavTab(d, active, iconOnly = d == Dest.Search || d == Dest.Settings,
                    modifier = if (active) Modifier.focusRequester(activeFocus) else Modifier, onClick = { onSelect(d) })
            }
            // Who is watching, at the end of the bar (Netflix): opens switch profile / exit.
            profile?.let { p ->
                Spacer(Modifier.width(6.dp))
                Box {
                Surface(onClick = onProfile, modifier = Modifier.size(32.dp).focusRequester(avatarFocus),
                    shape = ClickableSurfaceDefaults.shape(tv.nakash.ui.profile.AvatarShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = tv.nakash.ui.profile.AvatarShape))) {
                    tv.nakash.ui.profile.Avatar(p, 32.dp, Modifier.fillMaxSize())
                }
                // The dropdown hangs right under the avatar; closing it puts the cursor back on the avatar.
                if (menuOpen) androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopCenter,
                    offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 38.dp.roundToPx() }),
                    onDismissRequest = { onMenuDismiss(); navScope.launch { kotlinx.coroutines.delay(60); runCatching { avatarFocus.requestFocus() } } },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                ) { menu(p) }
                }
            }
        }
    }
}

@Composable
private fun NavTab(d: Dest, active: Boolean, iconOnly: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color.White.copy(alpha = .07f) else Color.Transparent, focusedContainerColor = Color.White,
            contentColor = if (active) NakashColors.Text else NakashColors.Muted, focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (active) Border(BorderStroke(1.5.dp, Color.White.copy(alpha = .55f)), shape = RoundedCornerShape(16.dp)) else Border.None,
            focusedBorder = Border.None,
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = if (iconOnly) 7.dp else 13.dp), contentAlignment = Alignment.Center) {
            if (iconOnly) Icon(d.icon, contentDescription = d.label, modifier = Modifier.size(19.dp))
            else Text(d.label, style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp), maxLines = 1,
                fontWeight = if (active || focused) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
