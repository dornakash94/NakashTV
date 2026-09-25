package tv.nakash

import org.junit.Assert.*
import org.junit.Test
import tv.nakash.domain.LibraryMatch
import tv.nakash.domain.TmdbParser
import tv.nakash.domain.TmdbTitle

class TmdbTest {
    private val body = javaClass.classLoader!!.getResource("fixtures/tmdb_movie.json")!!.readText()

    @Test fun prefersAHebrewYoutubeTrailerOverClipsAndOtherSites() {
        assertEquals("heTrailer01", TmdbParser.details(body, tv = false).trailerKey)
    }
    @Test fun parsesImagesCastAndRecommendations() {
        val d = TmdbParser.details(body, tv = false)
        assertEquals("https://image.tmdb.org/t/p/w1280/bd.jpg", d.backdrop)
        assertEquals("צעיר מתוסכל מקים מועדון.", d.overview)
        assertEquals(listOf("Brad Pitt", "Edward Norton"), d.cast.map { it.name })
        assertNull(d.cast[1].photo)
        assertEquals(1995, d.similar.first().year)
    }
    @Test fun noTrailerWhenOnlyClipsExist() {
        val clipsOnly = """{"videos":{"results":[{"site":"YouTube","key":"k","type":"Featurette"}]}}"""
        assertNull(TmdbParser.details(clipsOnly, tv = false).trailerKey)
    }
    @Test fun searchPrefersTheMatchingYear() {
        val search = """{"results":[{"id":1,"first_air_date":"2010-01-01"},{"id":2,"first_air_date":"2022-12-05"}]}"""
        assertEquals(2, TmdbParser.searchId(search, 2022, tv = true))
        assertEquals(1, TmdbParser.searchId(search, null, tv = true))
    }
    @Test fun recommendationsLinkToLibraryTitlesByNameAndYear() {
        data class Item(val title: String, val year: Int?)
        val library = listOf(Item("שבעה חטאים (1995)", 1995), Item("Se7en", 2020), Item("Other", null))
        val recs = listOf(TmdbTitle(807, "שבעה חטאים", "Se7en", 1995, null), TmdbTitle(2, "Missing", null, null, null))
        assertEquals(listOf(library[0]), LibraryMatch.match(recs, library, { it.title }, { it.year }))
    }
}
