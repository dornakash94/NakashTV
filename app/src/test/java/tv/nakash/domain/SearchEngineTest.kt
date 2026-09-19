package tv.nakash.domain
import org.junit.Assert.*
import org.junit.Test
class SearchEngineTest {
    @Test fun titleMatchesRankAheadOfMetadataAndIgnoreHebrewMarks() {
        val docs=listOf(SearchDocument("cast","סיפור אחר","ג'ו כהן"),SearchDocument("prefix","גו חוזר"),SearchDocument("exact","ג׳ו"))
        assertEquals(listOf("exact","prefix","cast"),SearchEngine.search(docs,"גּו").items.map {it.id})
    }
    @Test fun numericChannelWinsOverMovieTitleContainingNumber() {
        val docs=listOf(SearchDocument("movie","סיפור 11"),SearchDocument("channel","כאן",channelNumber=11))
        assertEquals("channel",SearchEngine.search(docs,"11").items.first().id)
    }
    @Test fun multiwordQueryCanMatchTitleAndCastTogether() {
        val docs=listOf(SearchDocument("a","המסע","דנה ישראלי"),SearchDocument("b","המסע השני","אדם אחר"))
        assertEquals(listOf("a"),SearchEngine.search(docs,"המסע דנה").items.map {it.id})
    }
    @Test fun suggestionsAreUniqueAndResultLimitDoesNotChangeTotal() {
        val docs=listOf(SearchDocument("a","ג׳ו"),SearchDocument("b","גו"),SearchDocument("c","גו חוזר"))
        val result=SearchEngine.search(docs,"גו",limit=1)
        assertEquals(3,result.total)
        assertEquals(1,result.items.size)
        assertEquals(listOf("ג׳ו","גו חוזר"),result.suggestions)
    }
    @Test fun blankQueryHasNoAutocompleteAndMissingQueryHasNoResults() {
        val docs=listOf(SearchDocument("a","כאן 11"))
        assertTrue(SearchEngine.search(docs," ").suggestions.isEmpty())
        assertEquals(0,SearchEngine.search(docs,"לא קיים").total)
    }
}
