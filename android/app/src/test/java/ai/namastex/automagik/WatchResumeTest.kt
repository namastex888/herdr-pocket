package ai.namastex.automagik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchResumeTest {
    private val saved = listOf("a", "b", "c")

    @Test fun watchingBeforeTheUpdateRestartsIt() {
        assertTrue(WatchResume.wanted(watch = true, watched = setOf("a")))
        assertEquals(listOf("a", "c"), WatchResume.hosts(watch = true, watched = setOf("c", "a"), saved = saved))
    }

    @Test fun watchingOffDoesNothing() {
        assertFalse(WatchResume.wanted(watch = false, watched = setOf("a")))
        assertEquals(emptyList<String>(), WatchResume.hosts(watch = false, watched = setOf("a", "b"), saved = saved))
    }

    @Test fun nothingWatchedDoesNothing() {
        assertFalse("never watched", WatchResume.wanted(watch = true, watched = null))
        assertFalse("stopped or disconnected", WatchResume.wanted(watch = true, watched = emptySet()))
        assertEquals(emptyList<String>(), WatchResume.hosts(watch = true, watched = emptySet(), saved = saved))
    }

    @Test fun aRemovedMachineIsntReconnected() {
        assertEquals(listOf("b"), WatchResume.hosts(watch = true, watched = setOf("b", "gone"), saved = saved))
    }

    @Test fun keepsWhatIsWatchedButNotPasswordsOrTheDemo() {
        assertEquals(setOf("a", "b"), WatchResume.watched(watch = true, demo = false, active = setOf("a", "b", "p"), passwordHosts = setOf("p")))
        assertEquals(emptySet<String>(), WatchResume.watched(watch = false, demo = false, active = setOf("a"), passwordHosts = emptySet()))
        assertEquals(emptySet<String>(), WatchResume.watched(watch = true, demo = true, active = setOf("a"), passwordHosts = emptySet()))
    }
}
