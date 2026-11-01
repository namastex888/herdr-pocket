package ai.namastex.automagik

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The play flavor: Google Play updates the app, so no updater, no update feed and no update check is in it. */
class PlayDistributionTest {
    @Test fun doesNotUpdateItself() = assertFalse(BuildConfig.SELF_UPDATE)

    @Test fun hasNoUpdateFeed() =
        assertTrue("UPDATE_URL is in the play BuildConfig", BuildConfig::class.java.fields.none { it.name == "UPDATE_URL" })

    // The updater's classes, including the ones the silent-update work adds, must not be compiled into play.
    @Test fun hasNoUpdaterCode() {
        for (name in listOf("Updater", "UpdateState", "UpdateReceiver", "UpdatedReceiver", "UpdatePolicy", "Release")) {
            try { Class.forName("ai.namastex.automagik.$name"); fail("$name is compiled into the play flavor") } catch (_: ClassNotFoundException) {}
        }
    }

    // The hooks main code calls must not reach for anything: each lambda would fail the test if it ran.
    @Test fun hooksDoNothing() {
        SelfUpdate.onLaunch({ fail("asked"); true }, { fail("asked"); true }, { fail("asked"); true })
        SelfUpdate.foreground(true); SelfUpdate.foreground(false); SelfUpdate.touched()
        assertFalse(SelfUpdate.isInstallIntent(null))
    }
}
