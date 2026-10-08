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
}
