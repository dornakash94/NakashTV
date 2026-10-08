package tv.nakash.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Personal and curated rows, from the profile's own history (what it watched, how far, what it saved) matched to what
 * the provider has. Light: it reads only the few fields it scores on (no plots or cast), keeps them 15 minutes, and
 * loads the full titles only for what it shows. Everything runs off the main thread.
 */
@Singleton
class RecommendationRepository @Inject constructor(
    private val catalog: CatalogRepository, private val profiles: ProfileStore, private val tmdb: TmdbRepository,
) {
    private class Library(val movies: List<MovieLite>, val series: List<SeriesLite>, val at: Long)
    @Volatile private var library: Library? = null
    private val loading = Mutex()

    private suspend fun library(): Library = loading.withLock {
        library?.takeIf { System.currentTimeMillis() - it.at < 15 * 60_000 && it.movies.isNotEmpty() }?.let { return it }
        Library(catalog.movieLite(), catalog.seriesLite(), System.currentTimeMillis()).also { library = it }
    }

    private suspend fun history(profileId: String): Recommender.History {
        val dao = profiles.db(profileId).user()
        return Recommender.History(dao.continueWatchingAll(), dao.favorites().first())
    }

    /** The personal rows for Home, best first. Empty for a profile with no history yet. */
    suspend fun rows(profileId: String): List<RecRow> = withContext(Dispatchers.Default) {
        val h = history(profileId); if (h.isEmpty) return@withContext emptyList()
        val lib = library()
        val seen = h.seenKeys()
        val affinity = Recommender.genreAffinity(h, lib.movies, lib.series)
        val rows = mutableListOf<Pair<IdRowMixed, String?>>()
        becauseYouWatched(h, lib, seen, null).forEach { rows += it to null }
        Recommender.newForYou(affinity, lib.movies, lib.series, seen).takeIf { it.size >= 4 }?.let { rows += IdRowMixed("rec_new", "חדש בשבילך", it) to "לפי הטעם שלך" }
        affinity.entries.sortedByDescending { it.value }.take(2).forEachIndexed { i, (genre, _) ->
            Recommender.byGenre(genre, lib.movies, lib.series, seen).takeIf { it.size >= 4 }?.let { rows += IdRowMixed("rec_genre_$i", "$genre בשבילך", it) to "הז׳אנר שהכי מתאים לך" }
        }
        // Full titles only for what is shown.
        val movies = catalog.moviesByIds(rows.flatMap { (r, _) -> r.items.filter { it.first == 'm' }.map { it.second } }.distinct()).associateBy { it.id }
        val series = catalog.seriesByIds(rows.flatMap { (r, _) -> r.items.filter { it.first == 's' }.map { it.second } }.distinct()).associateBy { it.id }
        rows.map { (r, sub) -> RecRow(r.key, r.title, sub, r.items.mapNotNull { (k, id) -> if (k == 'm') movies[id] else series[id] }) }.filter { it.items.size >= 3 }
    }

    /** Rows for the Movies or Series tab: the profile's own ("כי צפית ב…", "בשבילך", its genres), then the curated ones. */
    suspend fun discoverRows(profileId: String?, series: Boolean): List<IdRow> = withContext(Dispatchers.Default) {
        val lib = library()
        val out = mutableListOf<IdRow>()
        val h = profileId?.let { history(it) }
        val seen = h?.seenKeys().orEmpty()
        val prefix = if (series) "series:" else "movie:"
        val pool = if (series) lib.series.map { Curated.Item(it.id, it.year, it.genres, it.rating, null) } else lib.movies.map { Curated.Item(it.id, it.year, it.genres, it.rating, it.runtimeMin) }
        if (h != null && !h.isEmpty) {
            becauseYouWatched(h, lib, seen, series).forEach { r -> out += IdRow(r.key, r.title, r.items.map { it.second }) }
            val affinity = Recommender.genreAffinity(h, lib.movies, lib.series)
            Recommender.forYou(affinity, pool) { "$prefix$it" in seen }.takeIf { it.size >= 6 }?.let { out += IdRow("p_foryou", "מותאם בשבילך", it) }
            affinity.entries.sortedByDescending { it.value }.take(2).forEachIndexed { i, (genre, _) ->
                Curated.genreRow(pool, setOf(genre)) { "$prefix$it" in seen }.takeIf { it.size >= 6 }?.let { out += IdRow("p_genre_$i", "$genre בשבילך", it) }
            }
        }
        // What the world watches this week, kept to what the provider has.
        withTimeoutOrNull(8_000) { tmdb.trending(series) }?.let { trend ->
            val ids = if (series) LibraryMatch.match(trend, lib.series, { it.title }, { it.year }).map { it.id } else LibraryMatch.match(trend, lib.movies, { it.title }, { it.year }).map { it.id }
            if (ids.size >= 4) out += IdRow("trend", "חם השבוע בעולם", ids.take(20))
        }
        out += Curated.rows(pool, series, java.time.LocalDate.now())
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
        val perRow = rows(profileId).map { r -> r.items.mapNotNull { pick(it, r.title) }.take(3) }
        val out = LinkedHashMap<String, ProfilePick>()
        for (i in 0 until 3) perRow.forEach { row -> row.getOrNull(i)?.let { out.putIfAbsent(it.title, it) } }
        if (out.size < 3) library().movies.asSequence().filter { it.backdrop != null }.take(limit).forEach { out.putIfAbsent(it.title, ProfilePick(it.title, it.backdrop!!, "חדש בשירות")) }
        out.values.take(limit)
    }

    /** Mixed ids: ('m', movieId) / ('s', seriesId). */
    private data class IdRowMixed(val key: String, val title: String, val items: List<Pair<Char, Int>>)

    /** Up to two rows: TMDB recommendations for the most recent titles watched (of [series] kind, or both), kept to the library. */
    private suspend fun becauseYouWatched(h: Recommender.History, lib: Library, seen: Set<String>, series: Boolean?): List<IdRowMixed> {
        if (!tmdb.enabled) return emptyList()
        val rows = mutableListOf<IdRowMixed>()
        val seeds = Recommender.seeds(h, lib.movies, lib.series).filter { series == null || (it is SeriesLite) == series }
        for (seed in seeds.take(2)) {
            val recs = withTimeoutOrNull(10_000) {
                when (seed) { is MovieLite -> tmdb.movie(seed.tmdbId, seed.title, seed.year); is SeriesLite -> tmdb.tv(seed.title, seed.year); else -> null }
            }?.similar ?: continue
            val (title, items) = when (seed) {
                is MovieLite -> seed.title to LibraryMatch.match(recs, lib.movies, { it.title }, { it.year }).filter { "movie:${it.id}" !in seen && it.id != seed.id }.map { 'm' to it.id }
                is SeriesLite -> seed.title to LibraryMatch.match(recs, lib.series, { it.title }, { it.year }).filter { "series:${it.id}" !in seen && it.id != seed.id }.map { 's' to it.id }
                else -> continue
            }
            if (items.size >= 3) rows += IdRowMixed("because_${rows.size}", "כי צפית ב־$title", items.take(20))
        }
        return rows
    }
}

/** The pure part of the personal recommendations (no I/O), so it can be tested. */
object Recommender {
    data class History(val progress: List<WatchProgressEntity>, val favorites: List<FavoriteEntity>) {
        val isEmpty get() = progress.none { it.kind != "channel" } && favorites.none { it.kind != "channel" }
        /** "movie:<id>" / "series:<id>" of everything the profile already watched or saved. */
        fun seenKeys(): Set<String> = buildSet {
            progress.forEach { p -> when (p.kind) { "movie" -> add("movie:${p.refId}"); "episode" -> p.seriesId?.let { add("series:$it") } } }
            favorites.forEach { f -> if (f.kind == "movie" || f.kind == "series") add("${f.kind}:${f.refId}") }
        }
    }

    internal fun genres(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Most recent distinct movies / series watched (far enough to count), newest first. */
    fun seeds(h: History, movies: List<MovieLite>, series: List<SeriesLite>): List<Any> {
        val movieById = movies.associateBy { it.id }; val seriesById = series.associateBy { it.id }
        val seen = mutableSetOf<String>()
        return h.progress.sortedByDescending { it.updatedAt }.filter { it.completed || it.durationMs <= 0 || it.positionMs > it.durationMs / 5 || it.kind == "episode" }
            .mapNotNull { p ->
                when (p.kind) {
                    "movie" -> p.refId.toIntOrNull()?.let { movieById[it] }?.takeIf { seen.add("m${it.id}") }
                    "episode" -> p.seriesId?.let { seriesById[it] }?.takeIf { seen.add("s${it.id}") }
                    else -> null
                }
            }
    }

    /**
     * How much the profile likes each genre: finished titles and saved ones count double, the last month a bit more.
     * Normalised so the top genre is 1.
     */
    fun genreAffinity(h: History, movies: List<MovieLite>, series: List<SeriesLite>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val movieById = movies.associateBy { it.id }; val seriesById = series.associateBy { it.id }
        val score = HashMap<String, Double>()
        fun add(g: String, w: Double) = genres(g).forEach { score[it] = (score[it] ?: 0.0) + w }
        val countedSeries = mutableSetOf<Int>()
        h.progress.forEach { p ->
            val recency = if (now - p.updatedAt < 30L * 86_400_000) 1.5 else 1.0
            when (p.kind) {
                "movie" -> p.refId.toIntOrNull()?.let { movieById[it] }?.let { add(it.genres, (if (p.completed) 2.0 else 1.0) * recency) }
                "episode" -> p.seriesId?.takeIf { countedSeries.add(it) }?.let { seriesById[it] }?.let { add(it.genres, 2.0 * recency) }
            }
        }
        h.favorites.forEach { f ->
            when (f.kind) {
                "movie" -> f.refId.toIntOrNull()?.let { movieById[it] }?.let { add(it.genres, 2.0) }
                "series" -> f.refId.toIntOrNull()?.let { seriesById[it] }?.let { add(it.genres, 2.0) }
            }
        }
        val top = score.values.maxOrNull() ?: return emptyMap()
        return score.mapValues { it.value / top }
    }

    private fun match(g: String, affinity: Map<String, Double>) = genres(g).sumOf { affinity[it] ?: 0.0 }
    private fun quality(rating: Double?, year: Int?) = (rating ?: 5.0) + ((year ?: 2000) - 2000).coerceIn(0, 30) / 30.0

    /** The best titles of a genre the profile has not seen, movies and series mixed. */
    fun byGenre(genre: String, movies: List<MovieLite>, series: List<SeriesLite>, seen: Set<String>, limit: Int = 20): List<Pair<Char, Int>> {
        val m = movies.filter { genre in genres(it.genres) && "movie:${it.id}" !in seen }.sortedByDescending { quality(it.rating, it.year) }.map { 'm' to it.id }
        val s = series.filter { genre in genres(it.genres) && "series:${it.id}" !in seen }.sortedByDescending { quality(it.rating, it.year) }.map { 's' to it.id }
        return interleave(m, s).take(limit)
    }

    /** Recently added titles that fit the profile's genres (the lists come newest first). */
    fun newForYou(affinity: Map<String, Double>, movies: List<MovieLite>, series: List<SeriesLite>, seen: Set<String>, limit: Int = 20): List<Pair<Char, Int>> {
        if (affinity.isEmpty()) return emptyList()
        val m = movies.take(400).filter { "movie:${it.id}" !in seen && match(it.genres, affinity) >= .5 }.map { 'm' to it.id }
        val s = series.take(250).filter { "series:${it.id}" !in seen && match(it.genres, affinity) >= .5 }.map { 's' to it.id }
        return interleave(m, s).take(limit)
    }

    /** "מותאם בשבילך": genre fit × quality over the whole pool. */
    fun forYou(affinity: Map<String, Double>, pool: List<Curated.Item>, limit: Int = 20, seen: (Int) -> Boolean): List<Int> {
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
 * Curated rows for everyone (pure, testable): moods and themes over the provider's genres, best rated first, with a
 * daily rotation among the top so the rows feel fresh without losing quality.
 */
object Curated {
    data class Item(val id: Int, val year: Int?, val genres: String, val rating: Double?, val runtime: Int?)

    private class Theme(val key: String, val title: String, val genres: Set<String>, val minRating: Double = 6.2, val filter: (Item, Int) -> Boolean = { _, _ -> true })

    private val MOVIES = listOf(
        Theme("c_year", "הכי שווים מהשנה האחרונה", emptySet(), 6.5) { it, y -> (it.year ?: 0) >= y - 1 },
        Theme("c_comedy", "צחוק מובטח", setOf("קומדיה")),
        Theme("c_action", "אדרנלין בשמיים", setOf("אקשן", "פעולה", "הרפתקאות")),
        Theme("c_thriller", "מותחנים שלא מרפים", setOf("מותחן", "מתח", "פשע", "מסתורין")),
        Theme("c_family", "ערב סרט משפחתי", setOf("משפחה", "אנימציה", "ילדים"), 6.0),
        Theme("c_scifi", "מסע לעולמות אחרים", setOf("מדע בדיוני", "פנטזיה")),
        Theme("c_romance", "פרפרים בבטן", setOf("רומנטי", "רומנטיקה")),
        Theme("c_horror", "לילה של פחד", setOf("אימה"), 5.8),
        Theme("c_true", "סיפורים אמיתיים", setOf("דוקומנטרי", "תיעודי", "דוקו", "ביוגרפיה", "הסטוריה", "מלחמה"), 6.5),
        Theme("c_short", "קצר ולעניין · עד שעה וחצי", emptySet(), 6.4) { it, _ -> (it.runtime ?: 0) in 60..95 },
        Theme("c_israel", "תוצרת הארץ", setOf("ישראלי"), 0.0),
        Theme("c_classic", "קלאסיקות נצחיות", emptySet(), 7.4) { it, _ -> (it.year ?: 9999) <= 2000 },
        Theme("c_gems", "יהלומים נסתרים", emptySet(), 7.2) { it, y -> (it.year ?: y) in 2001..(y - 4) },
    )
    private val SERIES = listOf(
        Theme("c_year", "הכי שוות מהשנה האחרונה", emptySet(), 6.5) { it, y -> (it.year ?: 0) >= y - 1 },
        Theme("c_crime", "פשע ותעלומות", setOf("פשע", "מסתורין", "מיסתורין", "מתח")),
        Theme("c_comedy", "קומדיות לבינג׳", setOf("קומדיה")),
        Theme("c_scifi", "מדע בדיוני ופנטזיה", setOf("מדע בדיוני ופנטזיה", "מדע בדיוני", "פנטזיה")),
        Theme("c_action", "אקשן והרפתקאות", setOf("אקשן והרפתקאות", "פעולה", "הרפתקאות")),
        Theme("c_drama", "דרמות שאי אפשר לעזוב", setOf("דרמה"), 7.0),
        Theme("c_kids", "לילדים ולנוער", setOf("ילדים", "נוער", "אנימציה", "משפחה", "מדובב"), 0.0),
        Theme("c_reality", "ריאליטי", setOf("ריאליטי"), 0.0),
        Theme("c_novela", "טלנובלות", setOf("טלנובלה", "סבון"), 0.0),
        Theme("c_true", "דוקו", setOf("דוקומנטרי", "דוקו"), 6.0),
        Theme("c_gems", "יהלומים נסתרים", emptySet(), 7.6) { it, y -> (it.year ?: y) <= y - 4 },
    )

    fun rows(items: List<Item>, series: Boolean, today: java.time.LocalDate, perRow: Int = 20): List<IdRow> {
        val year = today.year
        val day = today.toEpochDay()
        return (if (series) SERIES else MOVIES).mapNotNull { t ->
            val pool = items.filter { i ->
                (i.rating ?: 0.0) >= t.minRating && t.filter(i, year) && (t.genres.isEmpty() || Recommender.genres(i.genres).any { it in t.genres })
            }.sortedByDescending { (it.rating ?: 0.0) + (it.year ?: 2000) / 10_000.0 }
            if (pool.size < 6) return@mapNotNull null
            IdRow(t.key, t.title, rotate(pool.take(perRow * 2), day, t.key).take(perRow).map { it.id })
        }
    }

    /** One genre set, best first (used for the profile's own genre rows). */
    fun genreRow(items: List<Item>, genres: Set<String>, perRow: Int = 20, seen: (Int) -> Boolean): List<Int> =
        items.filter { !seen(it.id) && Recommender.genres(it.genres).any { g -> g in genres } && (it.rating ?: 0.0) >= 6.0 }
            .sortedByDescending { it.rating ?: 0.0 }.take(perRow).map { it.id }

    /** The top of the pool reshuffled once a day (stable within the day): fresh rows, still the best titles. */
    private fun <T> rotate(list: List<T>, day: Long, key: String): List<T> = list.shuffled(java.util.Random(day * 31 + key.hashCode()))
}
