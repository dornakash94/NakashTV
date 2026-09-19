package tv.nakash

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import tv.nakash.data.local.UserDao
import tv.nakash.data.local.WatchProgressEntity
import tv.nakash.data.repo.UserRepository

class ResumeProgressTest {
    private var saved:WatchProgressEntity?=null
    private var removed:String?=null
    private val dao=Proxy.newProxyInstance(UserDao::class.java.classLoader,arrayOf(UserDao::class.java)) {_,method,args ->
        when(method.name) {
            "upsert" -> {saved=args!![0] as WatchProgressEntity;Unit}
            "remove" -> {removed=args!![0] as String;Unit}
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as UserDao
    @Test fun shortMovieSessionPreservesExactResumePoint()=runBlocking {
        UserRepository(dao).saveProgress("movie","10",30_123,7_200_000)
        assertEquals(30_123L,saved?.positionMs)
        assertEquals("movie:10",saved?.key)
        assertNull(removed)
    }
    @Test fun shortEpisodeSessionPreservesSeriesAssociation()=runBlocking {
        UserRepository(dao).saveProgress("episode","20",12_345,3_600_000,42)
        assertEquals(12_345L,saved?.positionMs)
        assertEquals(42,saved?.seriesId)
        assertFalse(saved!!.completed)
    }
    @Test fun completedEpisodeRemainsCompleted()=runBlocking {
        UserRepository(dao).saveProgress("episode","20",3_550_000,3_600_000,42)
        assertTrue(saved!!.completed)
        assertEquals(3_600_000L,saved?.positionMs)
    }
}
