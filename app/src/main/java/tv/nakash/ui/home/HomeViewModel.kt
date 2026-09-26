package tv.nakash.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.nakash.data.local.ChannelEntity
import tv.nakash.data.local.EpgEntity
import tv.nakash.data.local.MovieEntity
import tv.nakash.data.local.SeriesEntity
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.data.repo.EpgRepository
import tv.nakash.data.repo.UserRepository
import tv.nakash.player.PreviewPlayer
import javax.inject.Inject

/** What the hero shows for the focused item. */
sealed interface HeroItem {
    data class Channel(val channel: ChannelEntity, val now: EpgEntity?, val next: EpgEntity?) : HeroItem
    data class Movie(val movie: MovieEntity, val progress: WatchProgressEntity?) : HeroItem
    data class Series(val series: SeriesEntity, val progress: WatchProgressEntity?) : HeroItem
}

data class ContinueItem(val progress: WatchProgressEntity, val title: String, val image: String?, val target: Any)

data class HomeRow(val key: String, val title: String, val subtitle: String? = null, val items: List<Any>)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val user: UserRepository,
    private val preview: PreviewPlayer,
    @Suppress("unused") searchIndex: tv.nakash.ui.search.SearchIndex,   // created with Home so search is ready
    val tmdb: tv.nakash.data.remote.TmdbRepository,
) : ViewModel() {

    private val kidsPattern = Regex("ילדים|לוגי|ניק|Nick|כוכבים|הופ|לולי|בייבי|דיסני|Zoom|חינוכית")
    private val docsPattern = Regex("דוקו|Doco|ג'יאוגרפיק|דיסקברי|Animal|היסטוריה")

    /** Favorite channels (long-press on a card, or ▶ / the favorite button in the player). Empty → Israel's first 10. */
    private val favChannelIds = user.favorites().map { f -> f.filter { it.kind == "channel" }.map { it.refId.toInt() } }
    private val favChannels = favChannelIds.flatMapLatest { ids -> if (ids.isEmpty()) catalog.channelsInCategory(1).map { it.take(10) } else catalog.channelsByIds(ids) }
    private val hasFavChannels = favChannelIds.map { it.isNotEmpty() }

    /** "הרשימה שלי": favorite movies + series, newest-added first. Replaces the old MyList nav entry (item 3). */
    private val myList: kotlinx.coroutines.flow.Flow<List<Any>> = user.favorites().map { favs ->
        favs.filter { it.kind == "movie" || it.kind == "series" }.sortedByDescending { it.addedAt }.mapNotNull { f ->
            when (f.kind) { "movie" -> catalog.movie(f.refId.toInt()).first(); "series" -> catalog.series(f.refId.toInt()).first(); else -> null }
        }
    }

    private val extras = combine(myList, hasFavChannels) { list, hasFavs -> list to hasFavs }
    // Sport = category 30, kids/docs = Israel (category 1) by name: read those categories only, never all ~12k channels.
    private val themed = combine(catalog.channelsInCategory(30), catalog.channelsInCategory(1)) { sportAll, israel ->
        Triple(sportAll.take(16),
            israel.asSequence().filter { kidsPattern.containsMatchIn(it.displayName) }.take(24).toList(),
            israel.asSequence().filter { docsPattern.containsMatchIn(it.displayName) }.take(24).toList())
    }.distinctUntilChanged()
    val rows: StateFlow<List<HomeRow>> = combine(
        user.continueWatching(), favChannels, catalog.newestMovies(24), catalog.recentlyUpdatedSeries(24), combine(themed, extras) { a, b -> a to b },
    ) { cont, favs, movies, series, (themedRows, extra) ->
        val (mine, hasFavs) = extra
        val (sport, kids, docs) = themedRows
        buildList {
            val seenSeries=mutableSetOf<Int>()
            var hasChannel=false
            val continued=cont.mapNotNull { p ->
                when(p.kind) {
                    "channel" -> if(hasChannel) null else catalog.channel(p.refId.toInt())?.let { hasChannel=true;ContinueItem(p,it.displayName,it.logo,it) }
                    "movie" -> catalog.movie(p.refId.toInt()).first()?.let { ContinueItem(p,it.title,it.backdrop ?: it.poster,it) }
                    "episode" -> p.seriesId?.takeIf { seenSeries.add(it) }?.let { catalog.series(it).first() }?.let { ContinueItem(p,it.title,it.backdrop ?: it.cover,it) }
                    else -> null
                }
            }
            if (continued.isNotEmpty()) add(HomeRow("continue", "המשך צפייה", items = continued))
            add(HomeRow("now", if (hasFavs) "הערוצים שלי" else "עכשיו בשידור", if (hasFavs) "המועדפים שלך · לחיצה ארוכה על ערוץ מוסיפה" else "ישראל · לחיצה ארוכה על ערוץ מוסיפה למועדפים", favs))
            if (mine.isNotEmpty()) add(HomeRow("mylist", "הרשימה שלי", "סרטים וסדרות שסימנת", mine))
            if (movies.isNotEmpty()) add(HomeRow("new", "חדש בשירות", items = movies))
            if (series.isNotEmpty()) add(HomeRow("eps", "פרקים חדשים", items = series))
            if (sport.isNotEmpty()) add(HomeRow("sport", "ספורט", "שידורים חיים", sport))
            if (kids.isNotEmpty()) add(HomeRow("kids", "ילדים", items = kids))
            if (docs.isNotEmpty()) add(HomeRow("docs", "דוקו וטבע", items = docs))
        }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** now/next per channel, refreshed lazily on focus and every minute for visible channels. */
    val nowMap = MutableStateFlow<Map<String, EpgEntity>>(emptyMap())
    fun warmNow(channels: List<ChannelEntity>) = viewModelScope.launch {
        val time=System.currentTimeMillis()/1000
        val ids=channels.mapNotNull { it.epgChannelId }.distinct().take(100)
        val rows=epg.gridRows(ids,time,time+1)
        val next=rows.mapNotNull { (id,programs) -> programs.firstOrNull { !it.isFiller }?.let { id to it } }.toMap()
        if(next!=nowMap.value) nowMap.value=next
    }

    private val _hero = MutableStateFlow<HeroItem?>(null)
    val hero: StateFlow<HeroItem?> = _hero

    private var focusJob: kotlinx.coroutines.Job? = null
    fun onFocus(item: Any) {
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            val resolved: Any? = if(item is ContinueItem) item.target else if(item is WatchProgressEntity) when(item.kind) {
                "channel" -> catalog.channel(item.refId.toInt())
                "movie" -> catalog.movie(item.refId.toInt()).first()
                "episode" -> item.seriesId?.let { catalog.series(it).first() }
                else -> null
            } else item
            when(resolved) {
                is ChannelEntity -> {
                    _hero.value=HeroItem.Channel(resolved,null,null)
                    preview.focus(resolved.id,this@HomeViewModel)
                    var pair=epg.nowAndNext(resolved.epgChannelId)
                    if(pair.first==null && resolved.epgChannelId!=null) {
                        epg.refreshShort(resolved.id,resolved.epgChannelId,resolved.displayName)
                        pair=epg.nowAndNext(resolved.epgChannelId)
                    }
                    _hero.value=HeroItem.Channel(resolved,pair.first,pair.second)
                }
                is MovieEntity -> { preview.focus(null,this@HomeViewModel);_hero.value=HeroItem.Movie(resolved,user.progress("movie",resolved.id.toString())) }
                is SeriesEntity -> { preview.focus(null,this@HomeViewModel);_hero.value=HeroItem.Series(resolved,user.latestForSeries(resolved.id)) }
            }
        }
    }
    val toast = MutableStateFlow<String?>(null)
    fun toggleFavorite(ch: ChannelEntity) = viewModelScope.launch { val on = user.toggleFavorite("channel", ch.id.toString()); toast.value = if (on) "${ch.displayName} נוסף לערוצים שלך" else "${ch.displayName} הוסר מהערוצים שלך" }
    fun clearToast() { toast.value = null }
    fun stopPreview() { focusJob?.cancel();preview.stop(this) }
    val previewPlayer get() = preview.player
    val previewMuted get()=preview.muted
    val previewFrame get()=preview.hasFrame
    fun togglePreviewMute()=preview.toggleMute()
}
