package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.player.FrameRateMatcher

class FrameRateMatcherTest {
    @Test fun evenMultiplesFit() {
        assertThat(FrameRateMatcher.fits(50f, 25f)).isNotNull()
        assertThat(FrameRateMatcher.fits(59.94f, 29.97f)).isNotNull()
        assertThat(FrameRateMatcher.fits(47.952f, 23.976f)).isNotNull()
        assertThat(FrameRateMatcher.fits(23.976f, 23.976f)).isNotNull()
    }

    @Test fun nearMissesDoNotFit() {
        assertThat(FrameRateMatcher.fits(60f, 25f)).isNull()       // 2.4 refreshes per frame: uneven
        assertThat(FrameRateMatcher.fits(60f, 29.97f)).isNull()    // 0.1 % off: one frame held longer every ~17 s
        assertThat(FrameRateMatcher.fits(60f, 23.976f)).isNull()
    }
}
