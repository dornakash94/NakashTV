package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.data.local.MovieLite
import tv.nakash.data.local.SeriesLite
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.repo.Recommender

/** The Movies and Series tabs draw from the same kinds as Home, each kept to its own kind of title. */
class TabRowsTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun movie(id: Int, genres: String = "דרמה", rating: Double = 7.5, year: Int = 2012) = MovieLite(id, "m$id", year, genres, rating, null, null, 110)
    private fun series(id: Int, genres: String = "דרמה", rating: Double = 7.5, year: Int = 2012) = SeriesLite(id, "s$id", year, genres, rating, null)
    private fun started(id: Int, pos: Long, ago: Long) = WatchProgressEntity("movie:$id", "movie", "$id", null, pos, 100, now - ago)

    @Test fun moviesStartedAndNotFinishedComeBack() {
        val movies = (1..20).map { movie(it) }
        val progress = listOf(
            started(1, 50, 30 * day),        // half way, a month ago -> yes
            started(2, 5, 30 * day),         // left in the first minutes: did not like it -> no
            started(3, 95, 30 * day),        // credits -> no
            started(4, 50, 2 * day),         // fresh, still in "המשך צפייה" -> no
            started(5, 50, 400 * day),       // over a year ago -> no
        )
        // With few movies started, it still shows in "המשך צפייה": not repeated.
        assertThat(Recommender.leftMovies(Recommender.History(progress, emptyList()), movies, now)).isEmpty()
        // 12 newer unfinished movies push it out of "המשך צפייה": now it comes back.
        val newer = (6..17).map { started(it, 50, it * 1000L) }
        assertThat(Recommender.leftMovies(Recommender.History(progress + newer, emptyList()), movies, now)).containsExactly(1)
        // Disliked: never.
        val h = Recommender.History(progress + newer, emptyList(), listOf(tv.nakash.data.profile.RatingEntity("movie:1", "movie", "1", -1, now)))
        assertThat(Recommender.leftMovies(h, movies, now)).isEmpty()
    }

    @Test fun aTabsRowsHoldOnlyItsOwnKind() {
        val movies = (1..10).map { movie(it) }
        val series = (1..10).map { series(it) }
        val aff = mapOf("דרמה" to 1.0)
        // The tab passes only its own titles; every kind then returns that kind only.
        val forSeries = listOf(
            Recommender.hiddenGems(aff, emptyList(), series, emptySet(), 2026),
            Recommender.classics(aff, emptyList(), series.map { it.copy(year = 1990, rating = 8.0) }, emptySet()),
            Recommender.byGenre("דרמה", emptyList(), series, emptySet()),
            Recommender.sampler(emptyList(), series, emptySet()),
            Recommender.forMood(Recommender.Mood.BINGE, aff, emptyList(), series, emptySet()),
        )
        assertThat(forSeries.flatten()).isNotEmpty()
        assertThat(forSeries.flatten().all { it.first == 's' }).isTrue()
        val forMovies = listOf(
            Recommender.byGenre("דרמה", movies, emptyList(), emptySet()),
            Recommender.byDecade(2010, aff, movies, emptyList(), emptySet()),
            Recommender.forMood(Recommender.Mood.NIGHT, aff, movies.map { it.copy(runtimeMin = 90) }, emptyList(), emptySet()),
        )
        assertThat(forMovies.flatten()).isNotEmpty()
        assertThat(forMovies.flatten().all { it.first == 'm' }).isTrue()
    }
}
