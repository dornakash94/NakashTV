package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.data.local.FavoriteEntity
import tv.nakash.data.local.MovieLite
import tv.nakash.data.local.SeriesLite
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.repo.Curated
import tv.nakash.data.repo.Recommender
import java.time.LocalDate

class RecommenderTest {
    private val now = 1_800_000_000_000L
    private fun movie(id: Int, genres: String, rating: Double = 7.0, year: Int = 2020, runtime: Int? = 110) = MovieLite(id, "m$id", year, genres, rating, null, null, runtime)
    private fun watched(id: Int, done: Boolean = true) = WatchProgressEntity("movie:$id", "movie", "$id", null, if (done) 100 else 50, 100, now, completed = done)

    @Test fun affinityPrefersWhatIsWatchedAndSaved() {
        val movies = listOf(movie(1, "קומדיה"), movie(2, "קומדיה,רומנטי"), movie(3, "אימה"))
        val h = Recommender.History(listOf(watched(1), watched(3, done = false)), listOf(FavoriteEntity("movie:2", "movie", "2", now)))
        val a = Recommender.genreAffinity(h, movies, emptyList(), now)
        assertThat(a["קומדיה"]).isEqualTo(1.0)
        assertThat(a["אימה"]!!).isLessThan(a["רומנטי"]!!)
    }

    @Test fun seenTitlesAreNotRecommended() {
        val movies = listOf(movie(1, "קומדיה"), movie(2, "קומדיה"), movie(3, "קומדיה"))
        val h = Recommender.History(listOf(watched(1)), emptyList())
        val ids = Recommender.byGenre("קומדיה", movies, emptyList<SeriesLite>(), h.seenKeys()).map { it.second }
        assertThat(ids).containsExactly(2, 3)
    }

    @Test fun emptyHistoryHasNoPersonalRows() {
        val h = Recommender.History(listOf(WatchProgressEntity("channel:1", "channel", "1", null, 0, 0, now)), emptyList())
        assertThat(h.isEmpty).isTrue()
        assertThat(Recommender.genreAffinity(h, emptyList(), emptyList(), now)).isEmpty()
    }

    @Test fun curatedRowsNeedEnoughGoodTitlesAndRotateDaily() {
        val items = (1..30).map { Curated.Item(it, 2015, "קומדיה", 6.0 + it / 10.0, 90) } + (31..33).map { Curated.Item(it, 2015, "אימה", 8.0, 90) }
        val day1 = Curated.rows(items, series = false, today = LocalDate.of(2026, 10, 8))
        val comedy = day1.first { it.key == "c_comedy" }
        assertThat(comedy.ids).hasSize(20)
        assertThat(comedy.ids.toSet()).doesNotContain(1)      // rating 6.1 is below the bar
        assertThat(day1.none { it.key == "c_horror" }).isTrue() // only 3 horror titles
        assertThat(Curated.rows(items, false, LocalDate.of(2026, 10, 8)).first { it.key == "c_comedy" }.ids).isEqualTo(comedy.ids)
        assertThat(day1.first { it.key == "c_short" }.ids).isNotEmpty()
    }

    private fun rated(id: Int, v: Int) = tv.nakash.data.profile.RatingEntity("movie:$id", "movie", "$id", v, now)

    @Test fun ratingsShapeTheTasteAndHideTheTitle() {
        val movies = listOf(movie(1, "קומדיה"), movie(2, "אימה"), movie(3, "קומדיה"), movie(4, "אימה"))
        val h = Recommender.History(emptyList(), emptyList(), listOf(rated(1, 2), rated(2, -1)))
        val a = Recommender.genreAffinity(h, movies, emptyList(), now)
        assertThat(a["קומדיה"]).isEqualTo(1.0)
        assertThat(a["אימה"]!!).isLessThan(0.0)
        assertThat(h.isEmpty).isFalse()
        assertThat(h.seenKeys()).containsAtLeast("movie:1", "movie:2")
    }

    @Test fun lovedTitlesLeadTheSeedsAndDislikedNeverSeed() {
        val movies = listOf(movie(1, "קומדיה"), movie(2, "דרמה"), movie(3, "אימה"))
        val h = Recommender.History(listOf(watched(2), watched(3)), emptyList(), listOf(rated(1, 2), rated(3, -1)))
        val seeds = Recommender.seeds(h, movies, emptyList()).map { (it as MovieLite).id }
        assertThat(seeds).containsExactly(1, 2).inOrder()
    }

    private fun castMovie(id: Int, cast: String, cats: String = "") = MovieLite(id, "m$id", 2020, "דרמה", 7.0, null, null, 110, cats, cast)

    @Test fun actorsSeenInTwoWatchedTitlesGetARow() {
        val movies = listOf(castMovie(1, "Kit Harington, Emilia Clarke"), castMovie(2, "Kit Harington, Rose Leslie"), castMovie(3, "Kit Harington"), castMovie(4, "Emilia Clarke"))
        val h = Recommender.History(listOf(watched(1), watched(2)), emptyList())
        assertThat(Recommender.topActors(h, movies, emptyList(), now = now)).containsExactly("Kit Harington")
        assertThat(Recommender.byActor("Kit Harington", movies, emptyList(), h.seenKeys()).map { it.second }).containsExactly(3)
    }

    @Test fun watchedProviderCategoriesLeadButBroadOnesDoNot() {
        // Category 73 (Turkish, 8 titles) was watched twice; category 2 holds everything and says nothing.
        val series = (1..30).map { SeriesLite(it, "s$it", 2020, "דרמה", 7.0, null, if (it <= 8) "2,73" else "2") }
        val ep = { sid: Int -> WatchProgressEntity("episode:$sid", "episode", "e$sid", sid, 10, 100, now) }
        val h = Recommender.History(listOf(ep(1), ep(2)), emptyList())
        assertThat(Recommender.topCategories(h, emptyList(), series, now = now)).containsExactly("73")
        assertThat(Recommender.categoryTitle("סדרות טורקיות")).isEqualTo("עוד סדרות טורקיות")
        assertThat(Recommender.categoryTitle("נטפליקס")).isEqualTo("עוד מ־נטפליקס")
    }

    @Test fun newEpisodesOnlyForSeriesUpdatedAfterTheLastViewing() {
        val ep = { sid: Int, at: Long -> WatchProgressEntity("episode:$sid", "episode", "e$sid", sid, 10, 100, at) }
        val day = 86_400_000L
        val series = listOf(
            SeriesLite(1, "a", 2020, "", 7.0, null, lastModified = (now - day) / 1000),        // updated after viewing -> yes
            SeriesLite(2, "b", 2020, "", 7.0, null, lastModified = (now - 10 * day) / 1000),   // updated before viewing -> no
            SeriesLite(3, "c", 2020, "", 7.0, null, lastModified = (now - day) / 1000))        // never watched -> no
        val h = Recommender.History(listOf(ep(1, now - 5 * day), ep(2, now - 5 * day)), emptyList())
        assertThat(Recommender.newEpisodes(h, series, now)).containsExactly(1)
    }

    @Test fun aMovieLeftInItsFirstMinutesCountsAgainstItsGenres() {
        val movies = listOf(movie(1, "קומדיה"), movie(2, "אימה"))
        val left = WatchProgressEntity("movie:2", "movie", "2", null, 5, 100, now - 5 * 86_400_000L)
        val a = Recommender.genreAffinity(Recommender.History(listOf(watched(1), left), emptyList()), movies, emptyList(), now)
        assertThat(a["אימה"]!!).isLessThan(0.0)
    }

    @Test fun rowsPassedWithoutOpeningSlideDownAndOpenedOnesRise() {
        assertThat(Recommender.rowShift(0, 0)).isEqualTo(0.0)
        assertThat(Recommender.rowShift(6, 0)).isEqualTo(2.0)
        assertThat(Recommender.rowShift(6, 2)).isLessThan(0.0)
    }

    @Test fun themedRowsCanFollowProviderCategories() {
        val items = (1..10).map { Curated.Item(it, 2020, "דרמה", 7.0, 50, if (it <= 7) "73" else "9") }
        val specs = listOf(Curated.Spec("turkish", "דרמות טורקיות", categories = listOf("טורקיות"), minRating = 0.0))
        val rows = Curated.rows(items, true, LocalDate.of(2026, 10, 18), specs = specs, categoryNames = mapOf("73" to "סדרות טורקיות", "9" to "סדרות מהעולם"))
        assertThat(rows.single().key).isEqualTo("c_turkish")
        assertThat(rows.single().ids.toSet()).isEqualTo((1..7).toSet())
    }

    @Test fun theRowLineupChangesBetweenSlotsButNotWithinOne() {
        val rows = (1..30).toList()
        val a = Recommender.pickWeighted(rows, 9, 100L) { 1.0 }
        assertThat(a).hasSize(9)
        assertThat(Recommender.pickWeighted(rows, 9, 100L) { 1.0 }).isEqualTo(a)
        val lineups = (101L..110L).map { Recommender.pickWeighted(rows, 9, it) { 1.0 } }.toSet()
        assertThat(lineups.size).isGreaterThan(5)
        // A row the profile likes comes up far more often than one it keeps passing.
        val liked = (1L..400L).count { 1 in Recommender.pickWeighted(rows, 9, it) { r -> if (r == 1) Recommender.themeWeight(1.0, 2 to 2) else if (r == 2) Recommender.themeWeight(0.0, 20 to 0) else 1.0 } }
        val passed = (1L..400L).count { 2 in Recommender.pickWeighted(rows, 9, it) { r -> if (r == 1) Recommender.themeWeight(1.0, 2 to 2) else if (r == 2) Recommender.themeWeight(0.0, 20 to 0) else 1.0 } }
        assertThat(liked).isGreaterThan(passed * 3)
    }

    @Test fun personalPicksKeepTheTopAndRotateTheRest() {
        val ranked = listOf("a", "b", "c", "d", "e", "f")
        val picks = (1L..20L).map { Recommender.rotate(ranked, 2, it) }
        assertThat(picks.all { it.first() == "a" && it.size == 2 }).isTrue()
        assertThat(picks.map { it[1] }.toSet().size).isGreaterThan(2)
    }
}
