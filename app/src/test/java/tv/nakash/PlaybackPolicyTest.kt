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

    @Test fun timeshiftStartStaysInsideTheArchiveWindowAndBehindLive() {
        val now=1_000_000L
        assertEquals((now-3600)/60*60,PlaybackPolicy.timeshiftStart(now-3600,now,7))
        assertEquals((now-PlaybackPolicy.LIVE_EDGE_SEC)/60*60,PlaybackPolicy.timeshiftStart(now,now,7))
        assertEquals((now-7*86400L+60)/60*60,PlaybackPolicy.timeshiftStart(now-30*86400L,now,7))
    }
    @Test fun timeshiftRunsPastThePresentNotToTheGuideEnd() {
        val now=1_000_000L
        assertEquals(60+PlaybackPolicy.FUTURE_MARGIN_MIN,PlaybackPolicy.timeshiftMinutes(now-3600,now))
        assertEquals(PlaybackPolicy.CHUNK_MAX_MIN,PlaybackPolicy.timeshiftMinutes(now-86400,now))
        assertEquals(PlaybackPolicy.FUTURE_MARGIN_MIN,PlaybackPolicy.timeshiftMinutes(now-30,now))
    }
    @Test fun rewindingFromLiveLandsWhereTheArchiveExists() {
        val now=1_000_000L
        assertFalse(PlaybackPolicy.nearLive(PlaybackPolicy.rewindLimit(now),now))
    }
    @Test fun nearLiveHandsOverToTheBroadcast() {
        val now=1_000_000L
        assertTrue(PlaybackPolicy.nearLive(now-60,now))
        assertFalse(PlaybackPolicy.nearLive(now-600,now))
    }
    @Test fun scrubbingAcceleratesWhenHeld() {
        assertEquals(60L,PlaybackPolicy.scrubStepSeconds(0))
        assertEquals(300L,PlaybackPolicy.scrubStepSeconds(5))
        assertEquals(900L,PlaybackPolicy.scrubStepSeconds(12))
    }
    @Test fun scrubbingSnapsToTheStartOfTheShowItCrosses() {
        val starts=listOf(1000L,4600L,8200L)
        assertEquals(4600L,PlaybackPolicy.snapToProgramStart(5000,4000,starts))
        assertEquals(1000L,PlaybackPolicy.snapToProgramStart(4600,700,starts))
        assertEquals(4000L,PlaybackPolicy.snapToProgramStart(4100,4000,starts))
        assertEquals(8200L,PlaybackPolicy.snapToProgramStart(5000,9000,starts))
    }
}
