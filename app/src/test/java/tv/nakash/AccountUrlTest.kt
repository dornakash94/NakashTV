package tv.nakash
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.nakash.data.local.Account
class AccountUrlTest {
    @Test fun preservesExplicitHttpAndPort() { assertEquals("http://ilvip.net:80/",Account.normalizeBaseUrl(" http://ilvip.net:80 ")) }
    @Test fun preservesHttpsAndBasePath() { assertEquals("https://example.com:8443/service/",Account.normalizeBaseUrl("https://example.com:8443/service/")) }
    @Test fun bareHostnameDefaultsToHttps() { assertEquals("https://example.com/",Account.normalizeBaseUrl("example.com")) }
}
