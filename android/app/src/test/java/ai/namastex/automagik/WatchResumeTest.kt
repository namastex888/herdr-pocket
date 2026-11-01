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

    // The literal action names, so these also pin what the manifest's intent filter must say.
    private val boot = "android.intent.action.BOOT_COMPLETED"
    private val replaced = "android.intent.action.MY_PACKAGE_REPLACED"

    @Test fun watchingBeforeARestartRestartsIt() {
        assertTrue(WatchResume.resumes(boot, watch = true, watched = setOf("a")))
        assertTrue("an update still does", WatchResume.resumes(replaced, watch = true, watched = setOf("a")))
    }

    @Test fun aRestartWithWatchingOffDoesNothing() {
        assertFalse(WatchResume.resumes(boot, watch = false, watched = setOf("a")))
    }

    @Test fun aRestartWithNothingWatchedDoesNothing() {
        assertFalse("never watched", WatchResume.resumes(boot, watch = true, watched = null))
        assertFalse("stopped or disconnected", WatchResume.resumes(boot, watch = true, watched = emptySet()))
    }

    @Test fun anotherBroadcastDoesNothing() {
        assertFalse("before the first unlock", WatchResume.resumes("android.intent.action.LOCKED_BOOT_COMPLETED", watch = true, watched = setOf("a")))
        assertFalse(WatchResume.resumes("android.intent.action.USER_PRESENT", watch = true, watched = setOf("a")))
        assertFalse(WatchResume.resumes(null, watch = true, watched = setOf("a")))
    }

    @Test fun keepsWhatIsWatchedButNotPasswordsOrTheDemo() {
        assertEquals(setOf("a", "b"), WatchResume.watched(watch = true, demo = false, active = setOf("a", "b", "p"), passwordHosts = setOf("p")))
        assertEquals(emptySet<String>(), WatchResume.watched(watch = false, demo = false, active = setOf("a"), passwordHosts = emptySet()))
        assertEquals(emptySet<String>(), WatchResume.watched(watch = true, demo = true, active = setOf("a"), passwordHosts = emptySet()))
    }
}
