package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.data.local.MovieLite
import tv.nakash.data.local.SeriesLite
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.repo.Recommender
import java.time.LocalDateTime

/** The many kinds of Home rows, and how a visit draws among them. */
class HomeRowKindsTest {
    private val now = 1_800_000_000_000L
    private fun movie(id: Int, genres: String = "דרמה", rating: Double = 7.0, year: Int = 2015, runtime: Int? = 110, cast: String? = null) =
        MovieLite(id, "m$id", year, genres, rating, null, null, runtime, "", cast)
    private fun series(id: Int, genres: String = "דרמה", rating: Double = 7.0, year: Int = 2015) = SeriesLite(id, "s$id", year, genres, rating, null)
    private fun watched(id: Int) = WatchProgressEntity("movie:$id", "movie", "$id", null, 100, 100, now, completed = true)
    private fun ids(l: List<Pair<Char, Int>>) = l.map { it.second }

    @Test fun eachVisitDrawsADifferentSetOfKindsAndLeansToTheHeavy() {
        val kinds = (1..23).map { "k$it" }
        val w = { k: String -> if (k == "k1") 3.0 else 1.0 }
        val tops = (1L..200L).map { Recommender.weightedOrder(kinds, it, w).take(10).toSet() }
        assertThat(tops.toSet().size).isGreaterThan(150)
        assertThat(tops.count { "k1" in it }).isGreaterThan(tops.count { "k23" in it })
        assertThat(Recommender.weightedOrder(kinds, 5L, w)).containsExactlyElementsIn(kinds)
    }

    @Test fun freshReleasesAreRecentGoodAndToTaste() {
        val m = listOf(movie(1, year = 2026, rating = 7.0), movie(2, year = 2025, rating = 6.8), movie(3, year = 2020, rating = 8.0),
            movie(4, year = 2026, rating = 5.0), movie(5, "אימה", year = 2026, rating = 8.0))
        assertThat(ids(Recommender.freshReleases(mapOf("דרמה" to 1.0), m, emptyList(), emptySet(), 2026))).containsExactly(1, 2)
    }

    @Test fun titlesWithSeveralFavouriteActorsLead() {
        val m = listOf(movie(1, cast = "Ann, Bob"), movie(2, cast = "Ann, Zed"), movie(3, cast = "Zed"), movie(4, rating = 9.0, cast = "Bob"))
        val picks = ids(Recommender.byActors(listOf("Ann", "Bob"), m, emptyList(), emptySet()))
        assertThat(picks.first()).isEqualTo(1)
        assertThat(picks).containsExactly(1, 4, 2)
        assertThat(Recommender.byActors(listOf("Ann"), m, emptyList(), emptySet())).isEmpty()
    }

    @Test fun twoLikedGenresTogether() {
        val m = (1..7).map { movie(it, "קומדיה,רומנטי") } + (8..20).map { movie(it, "קומדיה") }
        val aff = mapOf("קומדיה" to 1.0, "רומנטי" to 0.6, "אימה" to 0.5)
        assertThat(Recommender.genrePairs(aff, m, emptyList(), emptySet())).containsExactly("קומדיה+רומנטי")
        assertThat(ids(Recommender.byGenres(setOf("קומדיה", "רומנטי"), m, emptyList(), emptySet())).toSet()).isEqualTo((1..7).toSet())
    }

    @Test fun theDecadeTheProfileKeepsGoingBackTo() {
        val m = (1..4).map { movie(it, year = 1994) } + (5..9).map { movie(it, year = 2024) } + listOf(movie(10, year = 1996, rating = 7.5), movie(11, year = 2005))
        val h = Recommender.History((1..9).map { watched(it) }, emptyList())
        assertThat(Recommender.favoriteDecade(h, m, emptyList(), 2026, now)).isEqualTo(1990)    // the 2020s are just "new"
        assertThat(Recommender.decadeTitle(1990)).isEqualTo("שנות ה־90")
        assertThat(Recommender.decadeTitle(2000)).isEqualTo("שנות ה־2000")
        assertThat(ids(Recommender.byDecade(1990, emptyMap(), m, emptyList(), h.seenKeys()))).containsExactly(10)
        assertThat(Recommender.favoriteDecade(Recommender.History(listOf(watched(1)), emptyList()), m, emptyList(), 2026, now)).isNull()
    }

    @Test fun classicsAndTheTopRated() {
        val m = listOf(movie(1, year = 1972, rating = 9.2, genres = "פשע"), movie(2, year = 1995, rating = 7.9), movie(3, year = 1995, rating = 7.9, genres = "מערבון"),
            movie(4, year = 2015, rating = 8.5), movie(5, year = 2015, rating = 8.6, genres = "אימה"))
        val aff = mapOf("דרמה" to 1.0, "אימה" to -0.5)
        assertThat(ids(Recommender.classics(aff, m, emptyList(), emptySet()))).containsExactly(1, 2)
        assertThat(ids(Recommender.topRated(aff, m, emptyList(), emptySet()))).containsExactly(1, 4).inOrder()
    }

    @Test fun aTasteOfEveryGenreOnePerGenre() {
        val m = (1..3).map { movie(it, "קומדיה", rating = 7.0 + it / 10.0) } + (4..6).map { movie(it, "אימה", rating = 7.5) } +
            (7..9).map { movie(it, "ילדים", rating = 9.0) } + listOf(movie(10, "קומדיה,אימה", rating = 9.5))
        val picks = ids(Recommender.sampler(m, emptyList(), emptySet()))
        assertThat(picks).hasSize(2)                      // comedy and horror (kids' genres are no surprise)
        assertThat(picks).contains(10)
        assertThat(picks.toSet().size).isEqualTo(picks.size)
    }

    @Test fun theHourChoosesTheMood() {
        assertThat(Recommender.mood(LocalDateTime.of(2026, 11, 4, 23, 0))).isEqualTo(Recommender.Mood.NIGHT)      // Wednesday night
        assertThat(Recommender.mood(LocalDateTime.of(2026, 11, 7, 15, 0))).isEqualTo(Recommender.Mood.WEEKEND)    // Saturday
        assertThat(Recommender.mood(LocalDateTime.of(2026, 11, 4, 18, 0))).isEqualTo(Recommender.Mood.BINGE)
        val m = listOf(movie(1, runtime = 90), movie(2, runtime = 150, rating = 7.5), movie(3, runtime = 120))
        val s = listOf(series(1, rating = 8.0), series(2, rating = 7.0))
        val aff = mapOf("דרמה" to 1.0)
        assertThat(Recommender.forMood(Recommender.Mood.NIGHT, aff, m, s, emptySet())).containsExactly('m' to 1)
        assertThat(Recommender.forMood(Recommender.Mood.WEEKEND, aff, m, s, emptySet())).containsExactly('m' to 2)
        assertThat(Recommender.forMood(Recommender.Mood.BINGE, aff, m, s, emptySet())).containsExactly('s' to 1)
    }

    @Test fun aMostlyMoviesProfileIsMostlyMovies() {
        val m = (1..5).map { movie(it) }
        assertThat(Recommender.mainKind(Recommender.History((1..5).map { watched(it) }, emptyList()), now)).isEqualTo('m')
        val ep = WatchProgressEntity("episode:1", "episode", "e1", 1, 10, 100, now)
        assertThat(Recommender.mainKind(Recommender.History(listOf(watched(1), ep), emptyList()), now)).isNull()
        assertThat(m).hasSize(5)
    }
}
