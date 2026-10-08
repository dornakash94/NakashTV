package tv.nakash

import org.junit.Assert.*
import org.junit.Test
import tv.nakash.data.repo.UserRepository.Companion.progressRecord

class ResumeProgressTest {
    @Test fun shortMovieSessionPreservesExactResumePoint() {
        val r = progressRecord("movie", "10", 30_123, 7_200_000, null, 1)!!
        assertEquals(30_123L, r.positionMs); assertEquals("movie:10", r.key); assertFalse(r.completed)
    }
    @Test fun shortEpisodeSessionPreservesSeriesAssociation() {
        val r = progressRecord("episode", "20", 12_345, 3_600_000, 42, 1)!!
        assertEquals(12_345L, r.positionMs); assertEquals(42, r.seriesId); assertFalse(r.completed)
    }
    @Test fun completedEpisodeRemainsCompleted() {
        val r = progressRecord("episode", "20", 3_550_000, 3_600_000, 42, 1)!!
        assertTrue(r.completed); assertEquals(3_600_000L, r.positionMs)
    }
    @Test fun underASecondStoresNothing() { assertNull(progressRecord("movie", "10", 500, 7_200_000, null, 1)) }
}
