package tv.nakash

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import tv.nakash.data.remote.CategoryDto
import tv.nakash.data.remote.EpisodeDto
import tv.nakash.data.remote.LiveStreamDto
import tv.nakash.data.remote.NetworkModule
import tv.nakash.data.remote.StreamingJsonConverterFactory
import tv.nakash.data.repo.CatalogRepository
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/** Crash and memory guards for weak TV boxes and new installs. */
class StabilityTest {
    private val json = "application/json".toMediaType()

    @OptIn(ExperimentalStdlibApi::class)
    private inline fun <reified T> decode(body: String): T {
        val retrofit = retrofit2.Retrofit.Builder().baseUrl("https://example.invalid/").build()
        val converter = StreamingJsonConverterFactory(NetworkModule.json).responseBodyConverter(typeOf<T>().javaType, emptyArray(), retrofit)
        @Suppress("UNCHECKED_CAST")
        return converter.convert(body.toResponseBody(json)) as T
    }

    @Test fun `streaming converter decodes the real live list like the string decoder`() {
        val raw = fixture("live_streams_sample.json")
        val streamed = decode<List<LiveStreamDto>>(raw)
        assertThat(streamed).isEqualTo(NetworkModule.json.decodeFromString<List<LiveStreamDto>>(raw))
        assertThat(streamed).hasSize(16)
    }

    @Test fun `one item with a null name or title does not fail the whole list`() {
        val streams = decode<List<LiveStreamDto>>("""[{"name":null,"stream_id":1},{"name":"כאן 11","stream_id":2}]""")
        assertThat(streams.map { it.name }).containsExactly("", "כאן 11").inOrder()
        val eps = decode<List<EpisodeDto>>("""[{"id":"7","title":null,"info":[]}]""")
        assertThat(eps.single().title).isEmpty()
    }

    @Test fun `categories accept numeric, missing or empty ids`() {
        val cats = decode<List<CategoryDto>>("""[{"category_id":12,"category_name":"ספורט"},{"category_id":"","category_name":"x"},{"category_name":null}]""")
        assertThat(cats.map { it.categoryId?.toIntOrNull() }).containsExactly(12, null, null).inOrder()
    }

    @Test fun `a sync already running is not started a second time`() = runTest {
        val lock = Mutex(); val gate = CompletableDeferred<Unit>(); var runs = 0
        val first = async { CatalogRepository.single(lock) { runs++; gate.await() } }
        yield()
        val second = async { CatalogRepository.single(lock) { runs++ } }
        yield()
        gate.complete(Unit); first.await(); second.await()
        assertThat(runs).isEqualTo(1)
        CatalogRepository.single(lock) { runs++ }
        assertThat(runs).isEqualTo(2)
    }

    @Test fun `crash summary names the root cause and the app frame`() {
        val root = NoSuchMethodError("getScaledFrameAtTime")
        root.stackTrace = arrayOf(StackTraceElement("android.media.MediaMetadataRetriever", "x", "MediaMetadataRetriever.java", 1),
            StackTraceElement("tv.nakash.player.ThumbnailGenerator", "y", "ThumbnailGenerator.kt", 84))
        val text = CrashLog.format(RuntimeException("wrapper", root), "DefaultDispatcher-worker-1", 0L)
        assertThat(text.lineSequence().first()).isEqualTo("1970-01-01 02:00 · NoSuchMethodError · ThumbnailGenerator.kt:84 · v${BuildConfig.VERSION_NAME}")
        assertThat(text).contains("thread=DefaultDispatcher-worker-1")
    }
}
