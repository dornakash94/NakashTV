package tv.nakash.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.tv.material3.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tv.nakash.data.local.*
import tv.nakash.data.repo.*
import tv.nakash.player.*
import tv.nakash.domain.SearchMatcher
import tv.nakash.ui.components.*
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(val catalog: CatalogRepository, val user: UserRepository, val player: PlayerController, val preview: PreviewPlayer, val thumbs:ThumbnailGenerator) : ViewModel() {
    val movies = catalog.newestMovies(Int.MAX_VALUE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val series = catalog.recentlyUpdatedSeries(Int.MAX_VALUE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val channels = catalog.channels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = user.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val query = MutableStateFlow("")
    val status = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    fun refresh(series: Boolean) = viewModelScope.launch {
        if (loading.value) return@launch
        loading.value = true
        status.value = null
        runCatching { if (series) catalog.syncSeries() else catalog.syncVod() }.onFailure { status.value = "לא הצלחנו לטעון את הספרייה. אפשר לנסות שוב." }
        loading.value = false
    }
    fun play(c: ChannelEntity) { player.zapChannels.value = channels.value; player.play(PlayRequest.Live(c)) }
}

@Composable
fun Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.colors(containerColor = NakashColors.S3, contentColor = NakashColors.Text,
            focusedContainerColor = NakashColors.Accent, focusedContentColor = androidx.compose.ui.graphics.Color.Black)) { Text(label, maxLines=2, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, label: String = "חיפוש לפי שם", modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        label = { Text(label, color = NakashColors.Muted) },
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NakashColors.Text, unfocusedTextColor = NakashColors.Text,
            focusedBorderColor = NakashColors.Accent, unfocusedBorderColor = NakashColors.Muted, cursorColor = NakashColors.Accent))
}

@Composable
fun LibraryScreen(nav: NavHostController, kind: String, vm: LibraryViewModel = hiltViewModel()) {
    val movies by vm.movies.collectAsState()
    val series by vm.series.collectAsState()
    val channels by vm.channels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val query by vm.query.collectAsState()
    val status by vm.status.collectAsState()
    val loading by vm.loading.collectAsState()
    var category by remember(kind) { mutableStateOf<Int?>(null) }
    var genre by remember(kind) { mutableStateOf("") }
    var sort by remember(kind) { mutableStateOf(false) }
    val isSeries = kind == "series"
    val categories by remember(isSeries) { vm.catalog.categories(if(isSeries) "series" else "vod") }.collectAsState(emptyList())
    val isList = kind == "mylist"
    val isSearch = kind == "search"
    val favoriteKeys = favorites.map { it.key }.toSet()
    val genres = remember(movies, series, kind) { (if (isSeries) series.map { it.genres } else movies.map { it.genres }).flatMap { it.split(',') }.filter { it.isNotBlank() }.distinct().sorted() }
    val q = query.trim()
    val visibleMovies = remember(movies, q, genre, category, sort, favoriteKeys, kind) {
        if (isSeries || (isSearch && q.isBlank())) emptyList() else movies.filter {
            (!isList || "movie:${it.id}" in favoriteKeys) && (category==null || category.toString() in it.categoryIds.split(',')) && (genre.isEmpty() || genre in it.genres.split(',')) &&
                SearchMatcher.matches(q,it.title,it.cast,it.director,it.genres)
        }.let { if (sort) it.sortedByDescending { m -> m.rating ?: 0.0 } else it }
    }
    val visibleSeries = remember(series, q, genre, category, favoriteKeys, kind) {
        if (kind == "movies" || (isSearch && q.isBlank())) emptyList() else series.filter {
            (!isList || "series:${it.id}" in favoriteKeys) && (category==null || category.toString() in it.categoryIds.split(',')) && (!isSeries || genre.isEmpty() || genre in it.genres.split(',')) &&
                SearchMatcher.matches(q,it.title,it.cast,it.genres)
        }
    }
    val visibleChannels = remember(channels, q, favoriteKeys, kind) {
        if (!isList && !isSearch || isSearch && q.isBlank()) emptyList() else channels.filter {
            (!isList || "channel:${it.id}" in favoriteKeys) && (SearchMatcher.matches(q,it.displayName) || q.toIntOrNull()==it.number)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when(kind) { "series" -> "סדרות"; "movies" -> "סרטים"; "mylist" -> "הרשימה שלי"; else -> "מה בא לך לראות?" }, style = MaterialTheme.typography.headlineMedium)
            if (!isSearch && !isList) Action(if(loading) "טוענים…" else "רענון", { vm.refresh(isSeries) }, enabled = !loading)
        }
        SearchField(query, { vm.query.value = it }, if(isSearch) "ערוץ, סרט, סדרה או שחקן" else "חיפוש בספרייה")
        if (!isSearch && !isList) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
            item { Action(if(genre.isEmpty() && category==null) "✓ הכול" else "הכול", { genre = ""; category=null }) }
            if (!isSeries) item { Action(if(sort) "✓ לפי דירוג" else "לפי דירוג", { sort = !sort }) }
            items(categories, key={"category${it.id}"}) { c -> Action(if(category==c.id) "✓ ${c.name}" else c.name,{category=c.id;genre=""}) }
            items(genres) { g -> Action(if(genre == g) "✓ $g" else g, { genre = g; category=null }) }
        }
        status?.let { Text(it, color = NakashColors.Live) }
        if (visibleMovies.isEmpty() && visibleSeries.isEmpty() && visibleChannels.isEmpty()) {
            Text(when { loading -> "טוענים את הספרייה…"; isSearch && q.isBlank() -> "הקלד שם כדי להתחיל לחפש"; isList -> "תכנים שתוסיף לרשימה יופיעו כאן"; q.isNotBlank() -> "לא נמצאו תוצאות"; else -> "הספרייה עדיין ריקה. בחר רענון כדי לטעון תכנים." }, color = NakashColors.Muted)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(142.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(visibleChannels, key = { "c${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { c ->
                Action("${c.number} · ${c.displayName}", { vm.play(c); nav.navigate("player") }, Modifier.fillMaxWidth())
            }
            items(visibleMovies, key = { "m${it.id}" }) { m -> PosterCard(m.title, m.year, m.poster, null, {}, { nav.navigate("movie/${m.id}") }, m.title, expandable = false) }
            items(visibleSeries, key = { "s${it.id}" }) { s -> PosterCard(s.title, s.year, s.cover, null, {}, { nav.navigate("seriesDetail/${s.id}") }, s.title, expandable = false) }
        }
    }
}
