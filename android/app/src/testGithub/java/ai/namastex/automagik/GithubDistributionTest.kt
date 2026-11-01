package ai.namastex.automagik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The github flavor keeps today's self-update from the GitHub releases. */
class GithubDistributionTest {
    @Test fun updatesItself() = assertTrue(BuildConfig.SELF_UPDATE)

    @Test fun readsLatestJsonOverHttps() {
        assertTrue(BuildConfig.UPDATE_URL, BuildConfig.UPDATE_URL.startsWith("https://"))
        assertTrue(BuildConfig.UPDATE_URL, BuildConfig.UPDATE_URL.endsWith("/latest.json"))
    }

    @Test fun startsIdle() = assertEquals(UpdateState.Idle, Updater.state.value)
}
