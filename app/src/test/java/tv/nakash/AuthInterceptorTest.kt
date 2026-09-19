package tv.nakash

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import tv.nakash.data.local.Account
import tv.nakash.data.remote.AuthInterceptor

class AuthInterceptorTest {
    private fun intercepted(url: String, account: Account? = Account("https://provider.example/", "test-user", "test-pass")): Request {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { account })
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return captured!!
    }
    @Test fun `API and XMLTV receive account credentials`() {
        for (path in listOf("player_api.php", "xmltv.php")) {
            val url = intercepted("https://provider.example/$path?action=test").url
            assertThat(url.queryParameter("username")).isEqualTo("test-user")
            assertThat(url.queryParameter("password")).isEqualTo("test-pass")
            assertThat(url.queryParameter("action")).isEqualTo("test")
        }
    }
    @Test fun `images and other origins never receive account credentials`() {
        for (url in listOf("https://images.example/player_api.php", "https://provider.example/poster.jpg",
            "http://provider.example/player_api.php", "https://provider.example:8443/player_api.php",
            "https://provider.example/live/u/p/1.m3u8")) {
            assertThat(intercepted(url).url.toString()).isEqualTo(url)
        }
    }
    @Test fun `signed out requests stay unchanged`() {
        assertThat(intercepted("https://provider.example/player_api.php", null).url.query).isNull()
    }
}
