package ai.namastex.automagik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val sha = "a".repeat(64)
    private fun json(code: Any = 912345, url: String = "https://github.com/o/r/releases/download/build-912345/herdr-pocket.apk", hash: String = sha, name: String = "0.3.0") =
        """{"versionCode":$code,"versionName":"$name","url":"$url","sha256":"$hash","notes":"Drawer"}"""

    @Test fun parsesThePublishedManifest() {
        val r = Release.parse(json())!!
        assertEquals(912345L, r.versionCode); assertEquals("0.3.0 (912345)", r.label); assertEquals("Drawer", r.notes)
    }

    @Test fun refusesAnythingItCantTrust() {
        assertNull(Release.parse(json(url = "http://example.com/a.apk")))
        assertNull(Release.parse(json(hash = "abc")))
        assertNull(Release.parse(json(code = 0)))
        assertNull(Release.parse(json(code = "\"x\"")))
        assertNull(Release.parse("<html>not found</html>"))
        assertEquals(sha, Release.parse(json(hash = "A".repeat(64)))!!.sha256)
    }

    @Test fun offersOnlyNewerBuilds() {
        val r = Release.parse(json())!!
        assertTrue(Updater.isNewer(r, 912344)); assertFalse(Updater.isNewer(r, 912345)); assertFalse(Updater.isNewer(r, 999999))
    }
}
