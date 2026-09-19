package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.data.remote.LiveStreamDto
import tv.nakash.data.remote.NetworkModule
import tv.nakash.domain.ChannelGrouper
import tv.nakash.domain.Normalizer

class NormalizerTest {
    private val streams = NetworkModule.json.decodeFromString<List<LiveStreamDto>>(fixture("live_streams_sample.json"))

    @Test fun `names are cleaned and overridden`() {
        assertThat(Normalizer.displayName("Eurosport  2")).isEqualTo("Eurosport 2")
        assertThat(Normalizer.displayName("'ויוה וינטג")).isEqualTo("ויוה וינטג'")
        assertThat(Normalizer.displayName("!הופ")).isEqualTo("הופ!")
        assertThat(Normalizer.displayName("4K ספורט 5")).isEqualTo("ספורט 5 4K")
        assertThat(Normalizer.displayName("ערוץ כאן 11-(גיבוי)")).isEqualTo("ערוץ כאן 11")
        assertThat(Normalizer.displayName("ספורט 5 - (גיבוי 1)")).isEqualTo("ספורט 5")
        assertThat(Normalizer.displayName("yes TV Drama RUS")).isEqualTo("yes TV Drama")
    }

    @Test fun `no display name starts or ends with junk`() {
        streams.map { Normalizer.displayName(it.name) }.forEach { n ->
            assertThat(n.first().isLetterOrDigit()).isTrue(); assertThat(n.last().isLetterOrDigit() || n.last() == '!' || n.last() == '\'').isTrue()
        }
    }

    @Test fun `category names prefer hebrew`() {
        assertThat(Normalizer.categoryName("israel  - ישראל")).isEqualTo("ישראל")
        assertThat(Normalizer.categoryName("\u200f\u200fArab - ערבית")).isEqualTo("ערבית")
        assertThat(Normalizer.categoryName("UK - Movie")).isEqualTo("UK")
        assertThat(Normalizer.categoryName("רדיו")).isEqualTo("רדיו")
    }

    @Test fun `episode title parsing`() {
        val e = Normalizer.episodeTitle("פרחי דם - S02E187 - פרק 187")
        assertThat(e.season).isEqualTo(2); assertThat(e.episode).isEqualTo(187); assertThat(e.title).isEqualTo("פרק 187")
    }

    @Test fun `genres split and plot normalization`() {
        assertThat(Normalizer.genres("פשע, דרמה")).containsExactly("פשע", "דרמה")
        assertThat(Normalizer.plot("א\u00a0ב  \n\n\n\nג")).isEqualTo("א ב \n\nג")
        assertThat(Normalizer.titleWithoutYear("לוריין הקטנה (2026)")).isEqualTo("לוריין הקטנה")
    }

    @Test fun `filler detection`() {
        assertThat(Normalizer.isFiller("ערוץ ההודעות", "ערוץ ההודעות", 8, 180)).isTrue()
        assertThat(Normalizer.isFiller("ערוץ כאן 11", "מבזק חדשות", 6, 4)).isFalse()
        assertThat(Normalizer.isFiller("X", "סרט", 4, 180)).isTrue()
    }

    @Test fun `placeholder epg ids are not epg`() {
        assertThat(Normalizer.hasEpg(streams.first { it.streamId == 11765 })).isFalse() // 150100
        assertThat(Normalizer.hasEpg(streams.first { it.streamId == 14101 })).isTrue()  // BBCNews.uk
        assertThat(Normalizer.hasEpg(streams.first { it.streamId == 44550 })).isTrue()
    }

    @Test fun `backups collapse into one logical channel with archive-bearing primary`() {
        val groups = ChannelGrouper.group(streams)
        val kan = groups.first { it.displayName == "ערוץ כאן 11" }
        assertThat(kan.sources.map { it.streamId }).containsExactly(44550, 267379, 289079).inOrder()
        assertThat(kan.primary.tvArchive).isTrue(); assertThat(kan.archiveDays).isEqualTo(32)
        val sport5 = groups.first { it.displayName == "ספורט 5" }
        assertThat(sport5.sources.map { it.streamId }).containsExactly(10230, 182388) // different epg id, same base name
        assertThat(groups.first { it.displayName == "ספורט 5 4K" }.sources).hasSize(1)   // NOT merged into ספורט 5
        val yes = groups.first { it.displayName == "yes TV Drama" }
        assertThat(yes.sources.map { it.kind.name }).containsExactly("PRIMARY", "RUSSIAN").inOrder()
        // foreign channels sharing placeholder id 150100 must stay separate
        assertThat(groups.count { it.displayName in setOf("ABC News", "FOX News Now", "NBA TV") }).isEqualTo(3)
        assertThat(groups.first { it.displayName == "NBA TV" }.categoryIds).containsExactly(1, 30)
    }

    @Test fun `base64 epg decodes to hebrew`() {
        assertThat(Normalizer.decodeBase64("15DXktefINeU15nXnSDXlNeq15nXm9eV158=")).isEqualTo("אגן הים התיכון")
        assertThat(Normalizer.decodeBase64("not base64!")).isEqualTo("not base64!")
        assertThat(Normalizer.initials("ערוץ כאן 11")).isEqualTo("עכ")
        assertThat(Normalizer.initials("HOT 3")).isEqualTo("H3")
    }
}
