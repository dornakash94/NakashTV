package tv.nakash.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.focusGroup
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Arrangement
import tv.nakash.ui.library.Action
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.nakash.ui.guide.GuideScreen
import tv.nakash.ui.home.HomeScreen
import tv.nakash.ui.live.LiveScreen
import tv.nakash.ui.player.PlayerScreen
import tv.nakash.ui.series.SeriesDetailScreen
import tv.nakash.ui.series.SeriesScreen
import tv.nakash.ui.vod.MovieDetailScreen
import tv.nakash.ui.vod.MoviesScreen
import tv.nakash.ui.theme.NakashColors

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Search : Dest("search", "חיפוש", Icons.Filled.Search)
    data object Home : Dest("home", "בית", Icons.Filled.Home)
    data object Live : Dest("live", "ערוצים חיים", Icons.Outlined.Tv)
    data object Guide : Dest("guide", "לוח שידורים", Icons.Outlined.CalendarViewWeek)
    data object Movies : Dest("movies", "סרטים", Icons.Outlined.Movie)
    data object Series : Dest("series", "סדרות", Icons.Outlined.VideoLibrary)
    data object MyList : Dest("mylist", "הרשימה שלי", Icons.Filled.Add)
    data object Settings : Dest("settings", "הגדרות", Icons.Filled.Settings)
    companion object { val all = listOf(Search, Home, Live, Guide, Movies, Series, Settings); val topLevel = all.map { it.route }.toSet() }
}

@Composable
fun NakashNavHost(nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val fullscreen = route.startsWith("player")
    val immersive = fullscreen || route.startsWith("movie/") || route.startsWith("seriesDetail/")
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }        // the rail item of the current section
    var railFocused by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    /**
     * Netflix behavior (item 4): Back on a top-level screen moves the "cursor" to the nav bar, onto the current
     * section. Back again from the nav bar goes Home; Back from the nav bar while on Home asks to exit.
     * Detail/player screens keep the normal pop.
     */
    BackHandler(enabled = !fullscreen) {
        when {
            route !in Dest.topLevel -> nav.popBackStack()
            !railFocused -> runCatching { railFocus.requestFocus() }
            route == "home" -> exitPrompt = true
            else -> nav.navigate("home") { launchSingleTop = true; popUpTo("home") { inclusive = true } }
        }
    }
    LaunchedEffect(route) {
        if (!fullscreen) { kotlinx.coroutines.delay(200); contentFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(NakashColors.Bg)) {
        Box(Modifier.fillMaxSize().padding(start = if (immersive) 0.dp else 64.dp).focusRequester(contentFocus).focusGroup()) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(nav) }
                composable("live") { LiveScreen(nav) }
                composable("guide") { GuideScreen(nav) }
                composable("movies") { MoviesScreen(nav) }
                composable("series") { SeriesScreen(nav) }
                composable("mylist") { tv.nakash.ui.home.MyListScreen(nav) }
                composable("search") { tv.nakash.ui.home.SearchScreen(nav) }
                composable("settings") { tv.nakash.ui.home.SettingsScreen() }
                composable("movie/{id}") { MovieDetailScreen(nav, it.arguments!!.getString("id")!!.toInt()) }
                composable("seriesDetail/{id}") { SeriesDetailScreen(nav, it.arguments!!.getString("id")!!.toInt()) }
                composable("player") { PlayerScreen(nav) }
            }
        }
        // Scrim: when the nav bar is open the content dims, so it is obvious where the focus is (item 2).
        if (!immersive) androidx.compose.animation.AnimatedVisibility(railFocused, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)))
        }
        if (exitPrompt) Dialog(onDismissRequest = { exitPrompt = false }) { Surface { Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("לצאת מ־NakashTV?", style = MaterialTheme.typography.headlineMedium)
            Action("להמשיך לצפות", { exitPrompt = false })
            Action("יציאה", { activity?.finish() })
        } } }
        if (!immersive) NavRail(
            current = route, activeFocus = railFocus, onFocusChanged = { railFocused = it },
            onSelect = { nav.navigate(it.route) { launchSingleTop = true; popUpTo("home") } },
        )
    }
}

/**
 * Netflix-TV style nav bar. Collapsed: a narrow strip of icons with no background — the active section is bright,
 * the rest dimmed. Open (focused): a solid dark panel with labels, the content behind dims via the scrim above.
 */
@Composable
fun NavRail(current: String, activeFocus: FocusRequester, onFocusChanged: (Boolean) -> Unit, onSelect: (Dest) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(if (expanded) 300.dp else 64.dp, spring(dampingRatio = 1f, stiffness = 500f), label = "rail")
    Column(
        Modifier.fillMaxHeight().width(railWidth)
            .background(if (expanded) NakashColors.Bg else Color.Transparent)
            .padding(horizontal = 12.dp).onFocusChanged { expanded = it.hasFocus; onFocusChanged(it.hasFocus) }.focusGroup(),
        verticalArrangement = Arrangement.Center,
    ) {
        Dest.all.forEach { d ->
            var focused by remember { mutableStateOf(false) }
            val active = current == d.route || (d == Dest.Movies && current.startsWith("movie/")) || (d == Dest.Series && current.startsWith("seriesDetail")) || (d == Dest.Home && current == "mylist")
            Surface(
                onClick = { onSelect(d) },
                modifier = Modifier.height(52.dp).padding(vertical = 3.dp).then(if (active) Modifier.focusRequester(activeFocus) else Modifier)
                    .onFocusChanged { focused = it.isFocused },
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent, focusedContainerColor = Color.White,
                    contentColor = if (active) NakashColors.Text else NakashColors.Dim, focusedContentColor = Color.Black,
                ),
            ) {
                Row(Modifier.padding(horizontal = 9.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(d.icon, contentDescription = d.label, modifier = Modifier.size(24.dp))
                    if (expanded) {
                        androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                        Text(d.label, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                            fontWeight = if (active || focused) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
                    }
                }
            }
        }
    }
}
