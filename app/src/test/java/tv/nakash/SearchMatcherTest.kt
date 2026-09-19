package tv.nakash
import org.junit.Assert.*
import org.junit.Test
import tv.nakash.domain.SearchMatcher
class SearchMatcherTest {
    @Test fun hebrewNiqqudAndQuotesDoNotPreventMatch() {
        assertTrue(SearchMatcher.matches("גו","ג׳ו"))
        assertTrue(SearchMatcher.matches("שלום","שָׁלוֹם"))
        assertTrue(SearchMatcher.matches("ג'ו","גו"))
    }
    @Test fun caseWhitespaceAndBidiAreNormalized() {
        assertTrue(SearchMatcher.matches("HOT  cinema","\u2068Hot cinema\u2069"))
        assertFalse(SearchMatcher.matches("ספורט","חדשות"))
    }
    @Test fun searchesEverySuppliedField() {
        assertTrue(SearchMatcher.matches("דרמה",null,"סרט","דרמה,קומדיה"))
        assertFalse(SearchMatcher.matches("דרמה",null,"סרט"))
    }
}
