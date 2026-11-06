package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.data.local.MovieLite
import tv.nakash.data.local.SeriesLite
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.repo.Recommender
import tv.nakash.data.repo.ShownMemory

/** Home's rows change from visit to visit: seeded draws, a memory of what was shown, and new kinds of rows. */
class HomeVarietyTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun freshenIsStablePerSeedButChangesBetweenVisits() {
        val ranked = (1..60).toList()
        val a = Recommender.freshen(ranked, 1L)
        assertThat(Recommender.freshen(ranked, 1L)).isEqualTo(a)
        assertThat(a).hasSize(30)
        assertThat(a.toSet().all { it <= 45 }).isTrue()                     // only from the top of the ranking
        val leads = (1L..30L).map { Recommender.freshen(ranked, it).take(5) }.toSet()
        assertThat(leads.size).isGreaterThan(20)                            // the first titles differ almost every visit
        // The best still lead more often than the bottom of the window.
        val top = (1L..300L).count { Recommender.freshen(ranked, it).take(10).contains(1) }
        val low = (1L..300L).count { Recommender.freshen(ranked, it).take(10).contains(40) }
        assertThat(top).isGreaterThan(low * 2)
    }

    @Test fun titlesShownOnTheLastVisitsMoveBack() {
        val ranked = (1..40).toList()
        val shown = (1..10).toSet()
        val inFront = (1L..200L).sumOf { s -> Recommender.freshen(ranked, s) { it in shown }.take(10).count { it in shown } }
        val before = (1L..200L).sumOf { s -> Recommender.freshen(ranked, s).take(10).count { it in shown } }
        assertThat(inFront * 3).isLessThan(before)
    }

    @Test fun drawRankedDoesNotAlwaysKeepTheTop() {
        val ranked = listOf("a", "b", "c", "d", "e", "f", "g")
        val firsts = (1L..200L).map { Recommender.drawRanked(ranked, 1, it).single() }
        assertThat(firsts.count { it == "a" }).isGreaterThan(firsts.count { it == "f" })
        assertThat(firsts.toSet().size).isAtLeast(4)
        assertThat(firsts).doesNotContain("g")                             // beyond the pool of 6
        // A topic shown last time comes back far less.
        val again = (1L..200L).count { Recommender.drawRanked(ranked, 1, it) { t -> t == "a" }.single() == "a" }
        assertThat(again * 2).isLessThan(firsts.count { it == "a" })
    }

    @Test fun rowOrderChangesButPersonalRowsLeanUp() {
        val rows = (0 until 10).toList()
        val orders = (1L..100L).map { Recommender.mixOrder(rows, it) }
        assertThat(orders.all { it.sorted() == rows }).isTrue()
        assertThat(orders.toSet().size).isGreaterThan(80)
        assertThat(orders.count { it.indexOf(0) < 5 }).isGreaterThan(orders.count { it.indexOf(9) < 5 } * 2)
    }

    private fun ep(sid: Int, n: Int, at: Long, done: Boolean = false) = WatchProgressEntity("episode:$sid$n", "episode", "$sid$n", sid, 10, 100, at, done)

    @Test fun seriesLeftHalfWayComeBackOnlyWhenOutOfContinueWatching() {
        val series = (1..5).map { SeriesLite(it, "s$it", 2020, "דרמה", 7.0, null) }
        val progress = listOf(
            ep(1, 1, now - 40 * day, done = true), ep(1, 2, now - 30 * day),          // left mid-episode a month ago -> yes
            ep(2, 1, now - 40 * day, done = true), ep(2, 2, now - 30 * day, done = true), // last one finished -> no
            ep(3, 1, now - 3 * day, done = true), ep(3, 2, now - 2 * day),            // still fresh -> no
            ep(4, 1, now - 30 * day),                                                 // one episode only -> no
        ) + (1..12).map { WatchProgressEntity("movie:$it", "movie", "$it", null, 10, 100, now - it * day) }
        val h = Recommender.History(progress, emptyList())
        assertThat(Recommender.leftSeries(h, series, now)).containsExactly(1)
        // With few other things started, series 1 is still in "המשך צפייה" and is not repeated.
        assertThat(Recommender.leftSeries(Recommender.History(progress.filter { it.kind == "episode" }, emptyList()), series, now)).isEmpty()
    }

    private fun movie(id: Int, genres: String, rating: Double, year: Int) = MovieLite(id, "m$id", year, genres, rating, null, null, 100)

    @Test fun hiddenGemsAreGoodOlderLesserKnownAndToTaste() {
        val newest = (1..400).map { movie(it, "דרמה", 7.5, 2015) }                  // the newest 400 belong to "חדש"
        val older = listOf(movie(1001, "דרמה", 7.6, 2012), movie(1002, "דרמה", 9.0, 2010), movie(1003, "דרמה", 7.6, 2025),
            movie(1004, "אימה", 7.8, 2012), movie(1005, "דרמה", 6.5, 2012))
        val gems = Recommender.hiddenGems(mapOf("דרמה" to 1.0), newest + older, emptyList(), emptySet(), 2026).map { it.second }
        assertThat(gems).containsExactly(1001)
    }

    @Test fun somethingDifferentComesFromGenresHardlyWatched() {
        val movies = (1..10).map { movie(it, "מערבון", 7.5, 2010) } + (11..20).map { movie(it, "דרמה", 7.5, 2010) } +
            (21..30).map { movie(it, "ילדים", 7.5, 2010) } + (31..33).map { movie(it, "מוזיקה", 7.5, 2010) }
        val genres = Recommender.untriedGenres(mapOf("דרמה" to 1.0, "מערבון" to 0.1), movies, emptyList(), emptySet())
        assertThat(genres).containsExactly("מערבון")
        assertThat(Recommender.byGenre("מערבון", movies + movie(99, "מערבון", 6.0, 2010), emptyList(), emptySet(), minRating = 7.2).map { it.second }).doesNotContain(99)
    }

    @Test fun theMemoryKeepsTheLastThreeVisits() {
        var mem = ""
        mem = ShownMemory.add(mem, listOf("m1", "t:actor:Kit Harington"), 1)
        mem = ShownMemory.add(mem, listOf("m2"), 2)
        mem = ShownMemory.add(mem, listOf("m3"), 3)
        assertThat(ShownMemory.recent(mem, 3)).containsExactly("m1", "t:actor:Kit Harington", "m2", "m3")
        mem = ShownMemory.add(mem, listOf("m4", "m2"), 4)
        assertThat(ShownMemory.recent(mem, 4)).containsExactly("m2", "m3", "m4")
        assertThat(ShownMemory.decode(ShownMemory.add("", (1..2000).map { "m$it" }, 1))).hasSize(700)
        assertThat(ShownMemory.decode("garbage\nm1\tx\n\t3")).isEmpty()
    }
}
