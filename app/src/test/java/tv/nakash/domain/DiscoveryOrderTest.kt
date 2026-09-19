package tv.nakash.domain

import org.junit.Assert.*
import org.junit.Test

class DiscoveryOrderTest {
    @Test fun mixesNewMoviesAndSeriesWithoutChangingRecencyWithinEachKind() {
        assertEquals(listOf("movie-new","series-new","movie-old","series-old"),DiscoveryOrder.interleave(listOf("movie-new","movie-old"),listOf("series-new","series-old")))
    }
    @Test fun includesTheLongerCatalogTail() {
        assertEquals(listOf("m1","s1","s2","s3"),DiscoveryOrder.interleave(listOf("m1"),listOf("s1","s2","s3")))
    }
    @Test fun emptyCatalogDoesNotHideOtherKind() {
        assertEquals(listOf("s1","s2"),DiscoveryOrder.interleave(emptyList(),listOf("s1","s2")))
    }
}
