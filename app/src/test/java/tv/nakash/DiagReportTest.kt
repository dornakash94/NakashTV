package tv.nakash
import org.junit.Assert.*
import org.junit.Test
import tv.nakash.domain.DiagReport
class DiagReportTest {
    private val secrets = listOf("dad123", "s3cretPass", "iptv.example.net")

    @Test fun streamUrlsAndLoginNeverLeave() {
        val msg = "Response code: 404 for http://iptv.example.net:8080/series/dad123/s3cretPass/9911.mkv (user dad123)"
        val out = DiagReport.redact(msg, secrets)
        assertFalse(out.contains("dad123")); assertFalse(out.contains("s3cretPass")); assertFalse(out.contains("iptv.example.net"))
        assertTrue(out.contains("<url>")); assertTrue(out.contains("404"))
    }

    @Test fun shortOrEmptySecretsAreIgnored() {
        assertEquals("a b", DiagReport.redact("a b", listOf("", "a")))
        assertEquals("", DiagReport.redact(null, secrets))
    }

    @Test fun causeChainIsRedactedAndAppFramesComeFirst() {
        val e = RuntimeException("outer", IllegalStateException("inner http://x.y/dad123/s3cretPass/1.ts"))
        val causes = DiagReport.causes(e, secrets)
        assertEquals(2, causes.size)
        assertTrue(causes[1].startsWith("java.lang.IllegalStateException: inner <url>"))
        assertTrue(DiagReport.stack(e).first().startsWith("tv.nakash."))
    }
}
