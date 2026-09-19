package tv.nakash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tv.nakash.data.remote.AuthResponse
import tv.nakash.data.remote.LiveStreamDto
import tv.nakash.data.remote.NetworkModule
import tv.nakash.data.remote.SeriesDto
import tv.nakash.data.remote.SeriesInfoResponse
import tv.nakash.data.remote.ShortEpgResponse
import tv.nakash.data.remote.VodStreamDto

fun fixture(name: String) = ClassLoader.getSystemResource("fixtures/$name").readText()

/** The DTOs must swallow every inconsistency seen on the real server (fixtures captured 18.9.2026). */
class DtoParsingTest {
    private val json = NetworkModule.json

    @Test fun `auth parses and reports active account`() {
        val a = json.decodeFromString<AuthResponse>(fixture("auth.json"))
        assertThat(a.userInfo.isActive).isTrue()
        assertThat(a.userInfo.maxConnections).isEqualTo(5)
        assertThat(a.userInfo.allowedOutputFormats).containsExactly("m3u8", "ts")
        assertThat(a.serverInfo.xui).isTrue()
        assertThat(a.serverInfo.serverProtocol).isEqualTo("https")
    }

    @Test fun `live streams parse with int tv_archive and category_ids array`() {
        val list = json.decodeFromString<List<LiveStreamDto>>(fixture("live_streams_sample.json"))
        assertThat(list).hasSize(16)
        val kan = list.first { it.streamId == 44550 }
        assertThat(kan.tvArchive).isEqualTo(1); assertThat(kan.tvArchiveDuration).isEqualTo(32)
        assertThat(list.first { it.streamId == 80152 }.categoryIds).containsExactly(1, 30)
    }

    @Test fun `vod rating 0 becomes null, string year, http poster kept`() {
        val list = json.decodeFromString<List<VodStreamDto>>(fixture("vod_streams_sample.json"))
        assertThat(list.first { it.streamId == 323327 }.rating).isNull()
        assertThat(list.first { it.streamId == 323278 }.rating).isEqualTo(7.3)
        assertThat(list.first { it.streamId == 323327 }.runTimeMin).isEqualTo(0)
        assertThat(list.first { it.streamId == 323277 }.streamIcon).startsWith("http://ilvip11.net")
    }

    @Test fun `series tolerates null vs empty string and string rating`() {
        val list = json.decodeFromString<List<SeriesDto>>(fixture("series_sample.json"))
        val dulce = list.first { it.seriesId == 4857 }; val prahim = list.first { it.seriesId == 689 }
        assertThat(dulce.cast).isNull(); assertThat(dulce.year).isNull(); assertThat(dulce.rating).isNull()
        assertThat(prahim.cast).isNull(); assertThat(prahim.rating).isEqualTo(8.0); assertThat(prahim.year).isEqualTo("2022")
    }

    @Test fun `series info episodes keyed by season string`() {
        val r = json.decodeFromString<SeriesInfoResponse>(fixture("series_info_sample.json"))
        assertThat(r.info!!.title).isEqualTo("פרחי דם")
        assertThat(r.seasons.map { it.episodeCount }).containsExactly(144, 210)
        assertThat(r.episodes["1"]!!.first().info!!.durationSecs).isEqualTo(2761)
    }

    @Test fun `short epg parses timestamps as long`() {
        val r = json.decodeFromString<ShortEpgResponse>(fixture("short_epg_kan11.json"))
        assertThat(r.listings.first().startTs).isEqualTo(1789714800L)
    }
}
