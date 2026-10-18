package tv.nakash.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import tv.nakash.data.local.FavoriteEntity
import tv.nakash.data.local.MovieLite
import tv.nakash.data.local.SeriesLite
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.profile.ProfileStore
import tv.nakash.data.remote.TmdbRepository
import tv.nakash.domain.LibraryMatch
import javax.inject.Inject
import javax.inject.Singleton

/** A title picked for a profile, shown behind it on "מי צופה?". */
data class ProfilePick(val title: String, val backdrop: String, val reason: String)

/** A personal Home row: [items] are MovieEntity / SeriesEntity. */
data class RecRow(val key: String, val title: String, val subtitle: String?, val items: List<Any>)

/** A row of ids for the Movies / Series tabs (they map ids to their own cards). */
data class IdRow(val key: String, val title: String, val ids: List<Int>)

/**
 * Personal and curated rows, from the profile's own history (what it watched, how far, what it saved and rated) matched
 * to what the provider has. Light: it reads only the few fields it scores on (no plots), keeps them 15 minutes, and
 * loads the full titles only for what it shows. Everything runs off the main thread.
 *
 * A title shows in one row only: rows are filled in order of how personal they are, and later rows skip what an
 * earlier one already holds.
 */
@Singleton
class RecommendationRepository @Inject constructor(
    private val catalog: CatalogRepository, private val profiles: ProfileStore, private val tmdb: TmdbRepository,
    private val config: RowConfig, private val stats: RowStats,
) {
    private class Library(val movies: List<MovieLite>, val series: List<SeriesLite>, val movieCats: Map<String, String>, val seriesCats: Map<String, String>, val at: Long)
    @Volatile private var library: Library? = null
    private val loading = Mutex()

    private suspend fun library(): Library = loading.withLock {
        library?.takeIf { System.currentTimeMillis() - it.at < 15 * 60_000 && it.movies.isNotEmpty() }?.let { return it }
        Library(catalog.movieLite(), catalog.seriesLite(),
            catalog.categories("vod").first().associate { it.id.toString() to it.name },
            catalog.categories("series").first().associate { it.id.toString() to it.name },
            System.currentTimeMillis()).also { library = it }
    }

    private suspend fun history(profileId: String): Recommender.History {
        val db = profiles.db(profileId)
        return Recommender.History(db.user().continueWatchingAll(), db.user().favorites().first(), db.sync().ratings())
    }

    /** The profile scrolled to a row of the Movies / Series tab (once per visit) / opened a title from it. */
    fun rowSeen(row: String) { profiles.current.value?.id?.let { stats.seen(it, row) } }
    fun rowOpened(row: String) { profiles.current.value?.id?.let { stats.opened(it, row) } }

    /** Fills rows in order; a title already shown in an earlier row is skipped. */
    private class Deduper { private val used = HashSet<String>()
        fun <T> take(items: List<T>, key: (T) -> String, max: Int = 20, min: Int = 3, shared: Boolean = false): List<T>? {
            val out = items.asSequence().filter { key(it) !in used }.take(max).toList()
            if (out.size < min) return null
            if (!shared) out.forEach { used += key(it) }
            return out
        }
    }

    /** The personal rows for Home, best first. Empty for a profile with no history yet. */
    suspend fun rows(profileId: String): List<RecRow> = withContext(Dispatchers.Default) {
        val h = history(profileId); if (h.isEmpty) return@withContext emptyList()
        val lib = library()
        val seen = h.seenKeys()
        val affinity = Recommender.genreAffinity(h, lib.movies, lib.series)
        val dedupe = Deduper(); val mixedKey: (Pair<Char, Int>) -> String = { "${it.first}${it.second}" }
        val rows = mutableListOf<Pair<IdRowMixed, String?>>()
        fun add(key: String, title: String, sub: String?, items: List<Pair<Char, Int>>, min: Int = 4) {
            dedupe.take(items, mixedKey, min = min)?.let { rows += IdRowMixed(key, title, it) to sub }
        }
        add("rec_neweps", "פרקים חדשים בסדרות שלך", "עלו פרקים מאז שצפית", Recommender.newEpisodes(h, lib.series).map { 's' to it }, min = 1)
        becauseYouWatched(h, lib, seen, null, 3).forEachIndexed { i, r -> add("rec_because_$i", r.title, null, r.items, min = 3) }
        collections(h, lib, seen).take(1).forEachIndexed { i, r -> add("rec_coll_$i", r.title, "הסרטים הבאים בסדרה", r.items, min = 1) }
        add("rec_new", "חדש בשבילך", "לפי הטעם שלך", Recommender.newForYou(affinity, lib.movies, lib.series, seen))
        Recommender.topActors(h, lib.movies, lib.series).take(1).forEachIndexed { i, actor ->
            add("rec_actor_$i", "עוד עם $actor", "שחקן שאתה צופה בו הרבה", Recommender.byActor(actor, lib.movies, lib.series, seen))
        }
        Recommender.topCategories(h, lib.movies, lib.series).filter { (lib.seriesCats[it] ?: lib.movieCats[it])?.let(Recommender::tasteCategory) == true }.take(1).forEachIndexed { i, cat ->
            val name = lib.seriesCats[cat] ?: lib.movieCats[cat] ?: return@forEachIndexed
            add("rec_cat_$i", Recommender.categoryTitle(name), "ממה שאתה אוהב לראות", Recommender.byCategory(cat, affinity, lib.movies, lib.series, seen))
        }
        affinity.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(2).forEachIndexed { i, (genre, _) ->
            add("rec_genre_$i", "$genre בשבילך", "הז׳אנר שהכי מתאים לך", Recommender.byGenre(genre, lib.movies, lib.series, seen))
        }
        // Full titles only for what is shown.
        val movies = catalog.moviesByIds(rows.flatMap { (r, _) -> r.items.filter { it.first == 'm' }.map { it.second } }.distinct()).associateBy { it.id }
        val series = catalog.seriesByIds(rows.flatMap { (r, _) -> r.items.filter { it.first == 's' }.map { it.second } }.distinct()).associateBy { it.id }
        rows.map { (r, sub) -> RecRow(r.key, r.title, sub, r.items.mapNotNull { (k, id) -> if (k == 'm') movies[id] else series[id] }) }.filter { it.items.isNotEmpty() }
    }

    /**
     * Rows for the Movies or Series tab: the profile's own ("פרקים חדשים בסדרות שלך", "כי צפית ב…", film series to
     * finish, its actors, categories and genres), what the world watches, then the themed rows (from our server when it
     * lists them), the ones the profile keeps passing moved down.
     */
    suspend fun discoverRows(profileId: String?, series: Boolean): List<IdRow> = withContext(Dispatchers.Default) {
        val lib = library()
        val out = mutableListOf<IdRow>()
        val h = profileId?.let { history(it) }
        val seen = h?.seenKeys().orEmpty()
        val prefix = if (series) "series:" else "movie:"
        val kind = if (series) 's' else 'm'
        val dedupe = Deduper(); val idKey: (Int) -> String = { "$kind$it" }
        fun add(key: String, title: String, ids: List<Int>, min: Int = 6) { dedupe.take(ids, idKey, min = min)?.let { out += IdRow(key, title, it) } }
        fun mine(items: List<Pair<Char, Int>>) = items.filter { it.first == kind }.map { it.second }
        val pool = if (series) lib.series.map { Curated.Item(it.id, it.year, it.genres, it.rating, null, it.categoryIds, it.cast) }
            else lib.movies.map { Curated.Item(it.id, it.year, it.genres, it.rating, it.runtimeMin, it.categoryIds, it.cast) }
        if (h != null && !h.isEmpty) {
            if (series) add("p_neweps", "פרקים חדשים בסדרות שלך", Recommender.newEpisodes(h, lib.series), min = 1)
            becauseYouWatched(h, lib, seen, series, 3).forEach { r -> add(r.key, r.title, r.items.map { it.second }, min = 4) }
            if (!series) collections(h, lib, seen).take(2).forEachIndexed { i, r -> add("p_coll_$i", r.title, r.items.map { it.second }, min = 1) }
            val affinity = Recommender.genreAffinity(h, lib.movies, lib.series)
            add("p_foryou", "מותאם בשבילך", Recommender.forYou(affinity, pool) { "$prefix$it" in seen })
            Recommender.topActors(h, lib.movies, lib.series, series).take(2).forEachIndexed { i, actor ->
                add("p_actor_$i", "עוד עם $actor", mine(Recommender.byActor(actor, lib.movies, lib.series, seen)), min = 4)
            }
            val cats = if (series) lib.seriesCats else lib.movieCats
            Recommender.topCategories(h, lib.movies, lib.series, series).filter { cats[it]?.let(Recommender::tasteCategory) == true }.take(2).forEachIndexed { i, cat ->
                add("p_cat_$i", Recommender.categoryTitle(cats.getValue(cat)), mine(Recommender.byCategory(cat, affinity, lib.movies, lib.series, seen)))
            }
            affinity.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(2).forEachIndexed { i, (genre, _) ->
                add("p_genre_$i", "$genre בשבילך", Curated.genreRow(pool, setOf(genre), perRow = 40) { "$prefix$it" in seen })
            }
        }
        // What the world watches this week, kept to what the provider has.
        withTimeoutOrNull(8_000) { tmdb.trending(series) }?.let { trend ->
            val ids = if (series) LibraryMatch.match(trend, lib.series, { it.title }, { it.year }).map { it.id } else LibraryMatch.match(trend, lib.movies, { it.title }, { it.year }).map { it.id }
            add("trend", "חם השבוע בעולם", ids, min = 4)
        }
        val now = java.time.LocalDateTime.now()
        val themed = Curated.rows(pool, series, now.toLocalDate(), extra = 20, slot = now.hour / 6, specs = config.specs(series), categoryNames = if (series) lib.seriesCats else lib.movieCats)
        stats.order(profileId, themed) { it.key }.forEach { add(it.key, it.title, it.ids) }
        out
    }

    /**
     * Titles to show (in turn) behind the profile on "מי צופה?": a few from each of its personal rows, taken in
     * rotation so the reasons vary; with no history yet, what is new.
     */
    suspend fun picksFor(profileId: String, limit: Int = 8): List<ProfilePick> = withContext(Dispatchers.Default) {
        fun pick(item: Any, reason: String) = when (item) {
            is tv.nakash.data.local.MovieEntity -> item.backdrop?.let { ProfilePick(item.title, it, reason) }
            is tv.nakash.data.local.SeriesEntity -> item.backdrop?.let { ProfilePick(item.title, it, reason) }
            else -> null
        }
        val perRow = rows(profileId).filter { it.key != "rec_neweps" }.map { r -> r.items.mapNotNull { pick(it, r.title) }.take(3) }
        val out = LinkedHashMap<String, ProfilePick>()
        for (i in 0 until 3) perRow.forEach { row -> row.getOrNull(i)?.let { out.putIfAbsent(it.title, it) } }
        if (out.size < 3) library().movies.asSequence().filter { it.backdrop != null }.take(limit).forEach { out.putIfAbsent(it.title, ProfilePick(it.title, it.backdrop!!, "חדש בשירות")) }
        out.values.take(limit)
    }

    /** Mixed ids: ('m', movieId) / ('s', seriesId). */
    private data class IdRowMixed(val key: String, val title: String, val items: List<Pair<Char, Int>>)

    /** "כי צפית ב…": TMDB recommendations for the titles the profile loved / watched last (of [series] kind, or both), kept to the library. */
    private suspend fun becauseYouWatched(h: Recommender.History, lib: Library, seen: Set<String>, series: Boolean?, count: Int): List<IdRowMixed> {
        if (!tmdb.enabled) return emptyList()
        val rows = mutableListOf<IdRowMixed>()
        val seeds = Recommender.seeds(h, lib.movies, lib.series).filter { series == null || (it is SeriesLite) == series }
        for (seed in seeds.take(count + 1)) {
            if (rows.size >= count) break
            val recs = withTimeoutOrNull(10_000) {
                when (seed) { is MovieLite -> tmdb.movie(seed.tmdbId, seed.title, seed.year); is SeriesLite -> tmdb.tv(seed.title, seed.year); else -> null }
            }?.similar ?: continue
            val (title, items) = when (seed) {
                is MovieLite -> seed.title to LibraryMatch.match(recs, lib.movies, { it.title }, { it.year }).filter { "movie:${it.id}" !in seen && it.id != seed.id }.map { 'm' to it.id }
                is SeriesLite -> seed.title to LibraryMatch.match(recs, lib.series, { it.title }, { it.year }).filter { "series:${it.id}" !in seen && it.id != seed.id }.map { 's' to it.id }
                else -> continue
            }
            if (items.size >= 3) rows += IdRowMixed("because_${rows.size}", "כי צפית ב־$title", items.distinct().take(30))
        }
        return rows
    }

    /** "להשלים את …": the other movies of a film series the profile watched (TMDB collections), in order, that the provider has. */
    private suspend fun collections(h: Recommender.History, lib: Library, seen: Set<String>): List<IdRowMixed> {
        if (!tmdb.enabled) return emptyList()
        val rows = mutableListOf<IdRowMixed>(); val done = HashSet<Int>()
        for (seed in Recommender.seeds(h, lib.movies, lib.series).filterIsInstance<MovieLite>().take(6)) {
            val details = withTimeoutOrNull(8_000) { tmdb.movie(seed.tmdbId, seed.title, seed.year) } ?: continue
            val id = details.collectionId ?: continue
            if (!done.add(id)) continue
            val parts = withTimeoutOrNull(8_000) { tmdb.collection(id) } ?: continue
            val items = LibraryMatch.match(parts, lib.movies, { it.title }, { it.year }).distinctBy { it.id }
                .filter { "movie:${it.id}" !in seen && it.id != seed.id }.sortedBy { it.year ?: 9999 }.map { 'm' to it.id }
            if (items.isNotEmpty()) rows += IdRowMixed("coll_$id", "להשלים את ${details.collectionName ?: seed.title}", items)
            if (rows.size >= 2) break
        }
        return rows
    }
}

/** The pure part of the personal recommendations (no I/O), so it can be tested. */
object Recommender {
    data class History(val progress: List<WatchProgressEntity>, val favorites: List<FavoriteEntity>,
                       val ratings: List<tv.nakash.data.profile.RatingEntity> = emptyList()) {
        val isEmpty get() = progress.none { it.kind != "channel" } && favorites.none { it.kind != "channel" } && ratings.isEmpty()
        /** "movie:<id>" / "series:<id>" of everything the profile already watched, saved or rated (never recommended again). */
        fun seenKeys(): Set<String> = buildSet {
            progress.forEach { p -> when (p.kind) { "movie" -> add("movie:${p.refId}"); "episode" -> p.seriesId?.let { add("series:$it") } } }
            favorites.forEach { f -> if (f.kind == "movie" || f.kind == "series") add("${f.kind}:${f.refId}") }
            ratings.forEach { add(it.key) }
        }
        val disliked: Set<String> get() = ratings.filter { it.value < 0 }.map { it.key }.toSet()
    }

    internal fun genres(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** A movie opened and left in its first minutes days ago: the profile did not like it (a mild minus, never a seed). */
    internal fun abandoned(p: WatchProgressEntity, now: Long) =
        p.kind == "movie" && !p.completed && p.durationMs > 0 && p.positionMs < p.durationMs * 0.15 && now - p.updatedAt > 3L * 86_400_000

    /** Most recent distinct movies / series watched (far enough to count), newest first. */
    fun seeds(h: History, movies: List<MovieLite>, series: List<SeriesLite>): List<Any> {
        val movieById = movies.associateBy { it.id }; val seriesById = series.associateBy { it.id }
        val seen = mutableSetOf<String>()
        // "ממש אהבתי" titles lead ("כי צפית ב…" starts from what the profile loved), then what it watched; never a disliked one.
        val disliked = h.disliked
        val loved = h.ratings.filter { it.value >= 2 }.sortedByDescending { it.at }.mapNotNull { r ->
            when (r.kind) { "movie" -> r.refId.toIntOrNull()?.let { movieById[it] }?.takeIf { seen.add("m${it.id}") }; "series" -> r.refId.toIntOrNull()?.let { seriesById[it] }?.takeIf { seen.add("s${it.id}") }; else -> null }
        }
        return loved + h.progress.sortedByDescending { it.updatedAt }.filter { p -> (if (p.kind == "episode") "series:${p.seriesId}" else "${p.kind}:${p.refId}") !in disliked }.filter { it.completed || it.durationMs <= 0 || it.positionMs > it.durationMs / 5 || it.kind == "episode" }
            .mapNotNull { p ->
                when (p.kind) {
                    "movie" -> p.refId.toIntOrNull()?.let { movieById[it] }?.takeIf { seen.add("m${it.id}") }
                    "episode" -> p.seriesId?.let { seriesById[it] }?.takeIf { seen.add("s${it.id}") }
                    else -> null
                }
            }
    }

    /**
     * How much each watched / saved / rated title says about the profile: loved 3, a series it kept watching 2 (more
     * episodes, more weight), finished movie or saved 2, started 1, left in the first minutes -0.5, disliked -2.
     * Keyed "m<id>" / "s<id>"; the last month counts 1.5x.
     */
    internal fun titleWeights(h: History, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val w = HashMap<String, Double>()
        fun put(k: String, v: Double) { w[k] = if (v < 0) minOf(w[k] ?: 0.0, v) else maxOf(w[k] ?: 0.0, v) }
        val episodes = h.progress.filter { it.kind == "episode" }.groupBy { it.seriesId }
        episodes.forEach { (sid, eps) ->
            sid ?: return@forEach
            val recency = if (now - eps.maxOf { it.updatedAt } < 30L * 86_400_000) 1.5 else 1.0
            put("s$sid", (1.5 + minOf(eps.size, 6) * 0.25) * recency)
        }
        h.progress.filter { it.kind == "movie" }.forEach { p ->
            val recency = if (now - p.updatedAt < 30L * 86_400_000) 1.5 else 1.0
            put("m${p.refId}", if (abandoned(p, now)) -0.5 else (if (p.completed) 2.0 else 1.0) * recency)
        }
        h.favorites.forEach { f -> if (f.kind == "movie" || f.kind == "series") put("${f.kind[0]}${f.refId}", maxOf(w["${f.kind[0]}${f.refId}"] ?: 0.0, 2.0)) }
        h.ratings.forEach { r ->
            val k = "${r.kind[0]}${r.refId}"
            if (r.value < 0) w[k] = -2.0 else w[k] = maxOf(w[k] ?: 0.0, if (r.value >= 2) 3.0 else 1.5)
        }
        return w
    }

    /**
     * How much the profile likes each genre: finished titles and saved ones count double, the last month a bit more,
     * a movie left in its first minutes a little less. Normalised so the top genre is 1.
     */
    fun genreAffinity(h: History, movies: List<MovieLite>, series: List<SeriesLite>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val movieById = movies.associateBy { it.id }; val seriesById = series.associateBy { it.id }
        val score = HashMap<String, Double>()
        fun add(g: String, w: Double) = genres(g).forEach { score[it] = (score[it] ?: 0.0) + w }
        val countedSeries = mutableSetOf<Int>()
        h.progress.forEach { p ->
            val recency = if (now - p.updatedAt < 30L * 86_400_000) 1.5 else 1.0
            when (p.kind) {
                "movie" -> p.refId.toIntOrNull()?.let { movieById[it] }?.let { add(it.genres, if (abandoned(p, now)) -0.5 else (if (p.completed) 2.0 else 1.0) * recency) }
                "episode" -> p.seriesId?.takeIf { countedSeries.add(it) }?.let { seriesById[it] }?.let { add(it.genres, 2.0 * recency) }
            }
        }
        h.favorites.forEach { f ->
            when (f.kind) {
                "movie" -> f.refId.toIntOrNull()?.let { movieById[it] }?.let { add(it.genres, 2.0) }
                "series" -> f.refId.toIntOrNull()?.let { seriesById[it] }?.let { add(it.genres, 2.0) }
            }
        }
        // Ratings: "ממש אהבתי" counts most, "אהבתי" like a saved title, "לא בשבילי" pulls its genres down.
        h.ratings.forEach { r ->
            val w = when { r.value >= 2 -> 3.0; r.value == 1 -> 1.5; else -> -2.0 }
            when (r.kind) {
                "movie" -> r.refId.toIntOrNull()?.let { movieById[it] }?.let { add(it.genres, w) }
                "series" -> r.refId.toIntOrNull()?.let { seriesById[it] }?.let { add(it.genres, w) }
            }
        }
        val top = score.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyMap()
        return score.mapValues { it.value / top }
    }

    internal fun castOf(cast: String?, top: Int = 5) = cast.orEmpty().split(',').map { it.trim() }.filter { it.length > 1 }.take(top)

    /**
     * Actors the profile follows: the leads (first 4 billed) of what it watched, weighted like [titleWeights]. Only an
     * actor seen in two titles or more, or in one it loved, counts. Best first. [series]: from that kind only (null = both).
     */
    fun topActors(h: History, movies: List<MovieLite>, series: List<SeriesLite>, seriesOnly: Boolean? = null, now: Long = System.currentTimeMillis()): List<String> {
        val movieById = movies.associateBy { it.id }; val seriesById = series.associateBy { it.id }
        val score = HashMap<String, Double>(); val titles = HashMap<String, Int>(); val loved = HashSet<String>()
        val lovedTitles = h.ratings.filter { it.value >= 2 }.map { "${it.kind[0]}${it.refId}" }.toSet()
        titleWeights(h, now).forEach { (k, w) ->
            if (w <= 0) return@forEach
            val id = k.drop(1).toIntOrNull() ?: return@forEach
            val cast = when {
                k[0] == 'm' && seriesOnly != true -> movieById[id]?.cast
                k[0] == 's' && seriesOnly != false -> seriesById[id]?.cast
                else -> null
            } ?: return@forEach
            castOf(cast, 4).forEach { a -> score[a] = (score[a] ?: 0.0) + w; titles[a] = (titles[a] ?: 0) + 1; if (k in lovedTitles) loved += a }
        }
        return score.filterKeys { (titles[it] ?: 0) >= 2 || it in loved }.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Titles with [actor] among the first billed, not seen yet, best first. */
    fun byActor(actor: String, movies: List<MovieLite>, series: List<SeriesLite>, seen: Set<String>, limit: Int = 30): List<Pair<Char, Int>> {
        val m = movies.filter { "movie:${it.id}" !in seen && actor in castOf(it.cast, 6) }.sortedByDescending { quality(it.rating, it.year) }.map { 'm' to it.id }
        val s = series.filter { "series:${it.id}" !in seen && actor in castOf(it.cast, 6) }.sortedByDescending { quality(it.rating, it.year) }.map { 's' to it.id }
        return interleave(m, s).take(limit)
    }

    /**
     * The provider's own categories the profile keeps coming back to ("סדרות טורקיות", "נטפליקס", "אנימה"…): at least two
     * titles watched there. Broad ones that hold more than a third of the library ("סרטים") say nothing and are left out.
     * Category ids, best first.
     */
    fun topCategories(h: History, movies: List<MovieLite>, series: List<SeriesLite>, seriesOnly: Boolean? = null, now: Long = System.currentTimeMillis()): List<String> {
        val movieById = movies.associateBy { it.id }; val seriesById = series.associateBy { it.id }
        val size = HashMap<String, Int>()
        if (seriesOnly != true) movies.forEach { m -> m.categoryIds.split(',').filter { it.isNotBlank() }.forEach { size["m$it"] = (size["m$it"] ?: 0) + 1 } }
        if (seriesOnly != false) series.forEach { s -> s.categoryIds.split(',').filter { it.isNotBlank() }.forEach { size["s$it"] = (size["s$it"] ?: 0) + 1 } }
        val score = HashMap<String, Double>(); val count = HashMap<String, Int>()
        titleWeights(h, now).forEach { (k, w) ->
            if (w <= 0) return@forEach
            val id = k.drop(1).toIntOrNull() ?: return@forEach
            val cats = when {
                k[0] == 'm' && seriesOnly != true -> movieById[id]?.categoryIds
                k[0] == 's' && seriesOnly != false -> seriesById[id]?.categoryIds
                else -> null
            } ?: return@forEach
            cats.split(',').filter { it.isNotBlank() }.forEach { c -> val ck = "${k[0]}$c"; score[ck] = (score[ck] ?: 0.0) + w; count[ck] = (count[ck] ?: 0) + 1 }
        }
        return score.filterKeys { k ->
            val total = if (k[0] == 'm') movies.size else series.size
            (count[k] ?: 0) >= 2 && (size[k] ?: 0) in 6..maxOf(6, total / 3)
        }.entries.sortedByDescending { it.value }.map { it.key.drop(1) }.distinct()
    }

    /** Catch-all provider categories ("סדרות מהעולם", "סרטים ב-4K", "2024-2026 סרטים"): they say nothing about taste. */
    private val BROAD = Regex("מהעולם|4K|\\d{4}|^סרטים$|^סדרות$", RegexOption.IGNORE_CASE)
    fun tasteCategory(name: String) = !BROAD.containsMatchIn(name.trim())

    private val PLATFORMS = Regex("נטפליקס|דיסני|אמזון|HBO|Apple|פריים|Netflix|Disney|Prime", RegexOption.IGNORE_CASE)
    /** "סדרות טורקיות" → "עוד סדרות טורקיות", "נטפליקס" → "עוד מ־נטפליקס". */
    fun categoryTitle(name: String) = if (PLATFORMS.containsMatchIn(name)) "עוד מ־$name" else "עוד $name"

    /** The best of a provider category the profile has not seen: genre fit × quality, the newest a little ahead. */
    fun byCategory(cat: String, affinity: Map<String, Double>, movies: List<MovieLite>, series: List<SeriesLite>, seen: Set<String>, limit: Int = 30): List<Pair<Char, Int>> {
        fun score(genres: String, rating: Double?, year: Int?) = (1 + match(genres, affinity)) * quality(rating, year)
        val m = movies.filter { "movie:${it.id}" !in seen && cat in it.categoryIds.split(',') }.sortedByDescending { score(it.genres, it.rating, it.year) }.map { 'm' to it.id }
        val s = series.filter { "series:${it.id}" !in seen && cat in it.categoryIds.split(',') }.sortedByDescending { score(it.genres, it.rating, it.year) }.map { 's' to it.id }
        return interleave(m, s).take(limit)
    }

    /**
     * Series the profile watches that got new episodes since it last watched (the provider updated them in the last 30
     * days, after that viewing). Newest update first. Not a disliked one.
     */
    fun newEpisodes(h: History, series: List<SeriesLite>, now: Long = System.currentTimeMillis()): List<Int> {
        val last = h.progress.filter { it.kind == "episode" && it.seriesId != null }.groupBy { it.seriesId!! }.mapValues { (_, e) -> e.maxOf { it.updatedAt } }
        val disliked = h.disliked
        return series.filter { s ->
            val watched = last[s.id] ?: return@filter false
            val updated = s.lastModified * 1000
            "series:${s.id}" !in disliked && updated > watched + 3_600_000 && now - updated < 30L * 86_400_000
        }.sortedByDescending { it.lastModified }.map { it.id }
    }

    /** Places a themed row moves down the tab: one for every 3 visits without opening a title, minus 1.5 per title opened. */
    fun rowShift(seen: Int, opened: Int): Double = maxOf(0, seen - 4 * opened) / 3.0 - opened * 1.5

    private fun match(g: String, affinity: Map<String, Double>) = genres(g).sumOf { affinity[it] ?: 0.0 }
    private fun quality(rating: Double?, year: Int?) = (rating ?: 5.0) + ((year ?: 2000) - 2000).coerceIn(0, 30) / 30.0

    /** The best titles of a genre the profile has not seen, movies and series mixed. */
    fun byGenre(genre: String, movies: List<MovieLite>, series: List<SeriesLite>, seen: Set<String>, limit: Int = 30): List<Pair<Char, Int>> {
        val m = movies.filter { genre in genres(it.genres) && "movie:${it.id}" !in seen }.sortedByDescending { quality(it.rating, it.year) }.map { 'm' to it.id }
        val s = series.filter { genre in genres(it.genres) && "series:${it.id}" !in seen }.sortedByDescending { quality(it.rating, it.year) }.map { 's' to it.id }
        return interleave(m, s).take(limit)
    }

    /** Recently added titles that fit the profile's genres (the lists come newest first). */
    fun newForYou(affinity: Map<String, Double>, movies: List<MovieLite>, series: List<SeriesLite>, seen: Set<String>, limit: Int = 30): List<Pair<Char, Int>> {
        if (affinity.isEmpty()) return emptyList()
        val m = movies.take(400).filter { "movie:${it.id}" !in seen && match(it.genres, affinity) >= .5 }.map { 'm' to it.id }
        val s = series.take(250).filter { "series:${it.id}" !in seen && match(it.genres, affinity) >= .5 }.map { 's' to it.id }
        return interleave(m, s).take(limit)
    }

    /** "מותאם בשבילך": genre fit × quality over the whole pool. */
    fun forYou(affinity: Map<String, Double>, pool: List<Curated.Item>, limit: Int = 40, seen: (Int) -> Boolean): List<Int> {
        if (affinity.isEmpty()) return emptyList()
        return pool.asSequence().filter { !seen(it.id) && (it.rating ?: 0.0) >= 6.0 }
            .map { it to match(it.genres, affinity) * quality(it.rating, it.year) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(limit).map { it.first.id }.toList()
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
        val n = maxOf(a.size, b.size); for (i in 0 until n) { a.getOrNull(i)?.let(::add); b.getOrNull(i)?.let(::add) }
    }
}

/**
 * Themed rows for everyone (pure, testable): moods and themes over the provider's genres, categories and cast, best
 * rated first, reshuffled among the top every 6 hours so the rows feel fresh without losing quality. Our server can
 * replace the built-in list ([RowConfig]).
 */
object Curated {
    data class Item(val id: Int, val year: Int?, val genres: String, val rating: Double?, val runtime: Int?, val categoryIds: String = "", val cast: String? = null)

    /**
     * One themed row. All set conditions must hold: a genre of [genres], a provider category whose name contains one of
     * [categories], an actor of [cast]; rating ≥ [minRating]; year in the last [yearsBack] years / ≥ [yearMin] / ≤
     * [yearMax] / at least [olderThan] years old; runtime in [runtimeMin]..[runtimeMax] minutes.
     */
    @Serializable
    data class Spec(val key: String, val title: String, val genres: List<String> = emptyList(), val categories: List<String> = emptyList(),
                    val cast: List<String> = emptyList(), val minRating: Double = 6.2, val yearsBack: Int? = null, val yearMin: Int? = null,
                    val yearMax: Int? = null, val olderThan: Int? = null, val runtimeMin: Int? = null, val runtimeMax: Int? = null)

    val MOVIES = listOf(
        Spec("c_year", "הכי שווים מהשנה האחרונה", minRating = 6.5, yearsBack = 1),
        Spec("c_comedy", "צחוק מובטח", listOf("קומדיה")),
        Spec("c_action", "אדרנלין בשמיים", listOf("אקשן", "פעולה", "הרפתקאות")),
        Spec("c_thriller", "מותחנים שלא מרפים", listOf("מותחן", "מתח", "פשע", "מסתורין")),
        Spec("c_family", "ערב סרט משפחתי", listOf("משפחה", "אנימציה", "ילדים"), minRating = 6.0),
        Spec("c_scifi", "מסע לעולמות אחרים", listOf("מדע בדיוני", "פנטזיה")),
        Spec("c_romance", "פרפרים בבטן", listOf("רומנטי", "רומנטיקה")),
        Spec("c_horror", "לילה של פחד", listOf("אימה"), minRating = 5.8),
        Spec("c_true", "סיפורים אמיתיים", listOf("דוקומנטרי", "תיעודי", "דוקו", "ביוגרפיה", "הסטוריה", "מלחמה"), minRating = 6.5),
        Spec("c_short", "קצר ולעניין · עד שעה וחצי", minRating = 6.4, runtimeMin = 60, runtimeMax = 95),
        Spec("c_israel", "תוצרת הארץ", listOf("ישראלי"), minRating = 0.0),
        Spec("c_classic", "קלאסיקות נצחיות", minRating = 7.4, yearMax = 2000),
        Spec("c_gems", "יהלומים נסתרים", minRating = 7.2, yearMin = 2001, olderThan = 4),
        Spec("c_netflix", "הכי טובים מנטפליקס", categories = listOf("נטפליקס"), minRating = 6.8),
        Spec("c_disney", "קסם של דיסני", categories = listOf("דיסני"), minRating = 6.5),
        Spec("c_4k", "באיכות 4K", categories = listOf("4K"), minRating = 6.5),
        Spec("c_standup", "סטנד אפ", categories = listOf("סטנד אפ"), minRating = 0.0),
    )
    val SERIES = listOf(
        Spec("c_year", "הכי שוות מהשנה האחרונה", minRating = 6.5, yearsBack = 1),
        Spec("c_crime", "פשע ותעלומות", listOf("פשע", "מסתורין", "מיסתורין", "מתח")),
        Spec("c_comedy", "קומדיות לבינג׳", listOf("קומדיה")),
        Spec("c_scifi", "מדע בדיוני ופנטזיה", listOf("מדע בדיוני ופנטזיה", "מדע בדיוני", "פנטזיה")),
        Spec("c_action", "אקשן והרפתקאות", listOf("אקשן והרפתקאות", "פעולה", "הרפתקאות")),
        Spec("c_drama", "דרמות שאי אפשר לעזוב", listOf("דרמה"), minRating = 7.0),
        Spec("c_kids", "לילדים ולנוער", listOf("ילדים", "נוער", "אנימציה", "משפחה", "מדובב"), minRating = 0.0),
        Spec("c_reality", "ריאליטי", listOf("ריאליטי"), minRating = 0.0),
        Spec("c_novela", "טלנובלות", listOf("טלנובלה", "סבון"), minRating = 0.0),
        Spec("c_true", "דוקו", listOf("דוקומנטרי", "דוקו"), minRating = 6.0),
        Spec("c_gems", "יהלומים נסתרים", minRating = 7.6, olderThan = 4),
        Spec("c_israel", "הכי טובות מישראל", categories = listOf("ישראליות"), minRating = 6.5),
        Spec("c_turkish", "דרמות טורקיות", categories = listOf("טורקיות"), minRating = 0.0),
        Spec("c_korean", "הגל הקוריאני", categories = listOf("קוריאניות"), minRating = 6.5),
        Spec("c_netflix", "הכי טובות מנטפליקס", categories = listOf("נטפליקס"), minRating = 7.0),
        Spec("c_hbo", "HBO Max במיטבה", categories = listOf("HBO"), minRating = 7.0),
        Spec("c_anime", "אנימה", categories = listOf("אנימה"), minRating = 0.0),
    )

    private fun fits(s: Spec, i: Item, year: Int, cats: Map<String, String>): Boolean {
        val y = i.year
        return (i.rating ?: 0.0) >= s.minRating &&
            (s.genres.isEmpty() || Recommender.genres(i.genres).any { it in s.genres }) &&
            (s.categories.isEmpty() || i.categoryIds.split(',').any { id -> cats[id]?.let { n -> s.categories.any { n.contains(it, ignoreCase = true) } } == true }) &&
            (s.cast.isEmpty() || Recommender.castOf(i.cast, 6).any { it in s.cast }) &&
            (s.yearsBack == null || (y ?: 0) >= year - s.yearsBack) &&
            (s.yearMin == null || (y ?: 0) >= s.yearMin) &&
            (s.yearMax == null || (y ?: 9999) <= s.yearMax) &&
            (s.olderThan == null || (y ?: year) <= year - s.olderThan) &&
            (s.runtimeMin == null || (i.runtime ?: 0) >= s.runtimeMin) &&
            (s.runtimeMax == null || (i.runtime ?: Int.MAX_VALUE) <= s.runtimeMax)
    }

    /**
     * The themed rows ([specs], or the built-in ones): each the best [perRow] (+ [extra] spare ones, for rows that skip
     * titles shown above them) out of its top, reshuffled per [slot] of the day (stable within it). Rows with fewer
     * than 6 titles are left out.
     */
    fun rows(items: List<Item>, series: Boolean, today: java.time.LocalDate, perRow: Int = 20, extra: Int = 0, slot: Int = 0,
             specs: List<Spec>? = null, categoryNames: Map<String, String> = emptyMap()): List<IdRow> {
        val year = today.year
        val seed = today.toEpochDay() * 4 + slot
        return (specs ?: if (series) SERIES else MOVIES).mapNotNull { s ->
            val pool = items.filter { fits(s, it, year, categoryNames) }.sortedByDescending { (it.rating ?: 0.0) + (it.year ?: 2000) / 10_000.0 }
            if (pool.size < 6) return@mapNotNull null
            val key = if (s.key.startsWith("c_")) s.key else "c_${s.key}"
            IdRow(key, s.title, rotate(pool.take(maxOf(perRow * 2, perRow + extra)), seed, key).take(perRow + extra).map { it.id })
        }
    }

    /** One genre set, best first (used for the profile's own genre rows). */
    fun genreRow(items: List<Item>, genres: Set<String>, perRow: Int = 20, seen: (Int) -> Boolean): List<Int> =
        items.filter { !seen(it.id) && Recommender.genres(it.genres).any { g -> g in genres } && (it.rating ?: 0.0) >= 6.0 }
            .sortedByDescending { it.rating ?: 0.0 }.take(perRow).map { it.id }

    /** The top of the pool reshuffled once per slot (stable within it): fresh rows, still the best titles. */
    private fun <T> rotate(list: List<T>, seed: Long, key: String): List<T> = list.shuffled(java.util.Random(seed * 31 + key.hashCode()))
}
