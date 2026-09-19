package tv.nakash
import org.junit.Assert.*
import org.junit.Test
import tv.nakash.domain.PlaybackPolicy
class PlaybackPolicyTest {
    private val now=2_000_000L
    @Test fun currentAndRetainedProgramsCanRestart() {
        assertTrue(PlaybackPolicy.archiveAvailable(now-60,now+60,false,7,now))
        assertTrue(PlaybackPolicy.archiveAvailable(now-7*86400,now-7*86400+60,false,7,now))
    }
    @Test fun futureExpiredFillerAndInvalidIntervalsCannotPlay() {
        assertFalse(PlaybackPolicy.archiveAvailable(now+1,now+60,false,7,now))
        assertFalse(PlaybackPolicy.archiveAvailable(now-7*86400-1,now-7*86400+60,false,7,now))
        assertFalse(PlaybackPolicy.archiveAvailable(now-60,now+60,true,7,now))
        assertFalse(PlaybackPolicy.archiveAvailable(now-60,now+60,false,0,now))
        assertFalse(PlaybackPolicy.archiveAvailable(now-60,now-90,false,7,now))
    }
    @Test fun archiveOffsetStaysWithinAiredWholeMinutes() {
        assertEquals(120L,PlaybackPolicy.archiveOffsetSeconds(1000,2000,1500,125))
        assertEquals(480L,PlaybackPolicy.archiveOffsetSeconds(1000,2000,1500,Long.MAX_VALUE))
        assertEquals(0L,PlaybackPolicy.archiveOffsetSeconds(1000,2000,900,60))
        assertEquals(0L,PlaybackPolicy.archiveOffsetSeconds(1000,2000,3000,-1))
        assertEquals(960L,PlaybackPolicy.archiveOffsetSeconds(1000,2000,3000,Long.MAX_VALUE))
    }
    @Test fun durationRoundsUpSoLastSecondsAreNotTruncated() {
        assertEquals(2,PlaybackPolicy.archiveMinutes(100,161))
        assertEquals(1,PlaybackPolicy.archiveMinutes(100,160))
        assertEquals(1,PlaybackPolicy.archiveMinutes(100,100))
    }
}
