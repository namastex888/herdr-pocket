package dev.herdr.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone alerts, against Herdr desktop's alert rule, on synthetic terminals. */
class AlertsTest {
    private fun t(address: String, status: String?, parent: String = "", turn: Long = 1, state: String = "live", title: String = "") =
        CoreTerminal.parse(JSONObject().put("address", address).put("state", state).put("turn", turn).put("title", title)
            .put("parent", parent.ifBlank { null } ?: JSONObject.NULL)
            .put("status", status?.let { JSONObject().put("state", it).put("app", "claude-code") } ?: JSONObject.NULL))

    private fun changes(old: List<CoreTerminal>?, new: List<CoreTerminal>, paned: Set<String>? = null) =
        Alerts.changes(old, new, "h") { paned == null || it.address in paned }.map { it.ref.address to it.kind }

    @Test fun turningBlockedOrFinishingAlerts() {
        assertEquals(listOf("t1" to "blocked", "t2" to "done", "t4" to "blocked"),
            changes(listOf(t("t1", "working"), t("t2", "working"), t("t3", "working"), t("t4", "idle")),
                listOf(t("t1", "blocked"), t("t2", "done"), t("t3", "working"), t("t4", "error"))))
    }

    @Test fun stayingTheSameDoesNotAlertAgain() {
        val same = listOf(t("t1", "blocked"), t("t2", "done", turn = 3), t("t3", "idle"))
        assertTrue(changes(same, same).isEmpty())
        // Blocked again after an error: still the same need.
        assertTrue(changes(listOf(t("t1", "error")), listOf(t("t1", "blocked"))).isEmpty())
    }

    @Test fun aSecondFinishBetweenReadsAlerts() {
        assertEquals(listOf("t1" to "done"), changes(listOf(t("t1", "done", turn = 3)), listOf(t("t1", "done", turn = 4))))
    }

    @Test fun theFirstReadAlertsNothing() {
        assertTrue(changes(null, listOf(t("t1", "blocked"), t("t2", "done"))).isEmpty())
    }

    @Test fun aSubAgentsDoneIsNotNewsButItsNeedIs() {
        val old = listOf(t("t1", "working"), t("t2", "working", parent = "t1"), t("t3", "working", parent = "t1"))
        val new = listOf(t("t1", "working"), t("t2", "done", parent = "t1"), t("t3", "blocked", parent = "t1"))
        assertEquals(listOf("t3" to "blocked"), changes(old, new, paned = setOf("t1")))
        // With a pane of its own it is an agent like any other.
        assertEquals(listOf("t2" to "done", "t3" to "blocked"), changes(old, new, paned = setOf("t1", "t2")))
        // An orphan (its parent ended) reports its own done.
        assertEquals(listOf("t2" to "done"), changes(listOf(t("t2", "working", parent = "t9")), listOf(t("t2", "done", parent = "t9")), paned = emptySet()))
    }

    @Test fun endedOrStatuslessTerminalsDoNotAlert() {
        assertTrue(changes(listOf(t("t1", "working"), t("t2", null)), listOf(t("t1", "done", state = "exited"), t("t2", null))).isEmpty())
    }

    @Test fun needsYouSoundsEvenWhenLookingDoneOnlyWhenNot() {
        assertTrue(Alerts.sounds(Alerts.BLOCKED, looking = true)); assertTrue(Alerts.sounds(Alerts.BLOCKED, looking = false))
        assertFalse(Alerts.sounds(Alerts.DONE, looking = true)); assertTrue(Alerts.sounds(Alerts.DONE, looking = false))
    }

    @Test fun theToastNamesTheAgent() {
        val a = Alerts.changes(listOf(t("t1", "working", title = "Fix login")), listOf(t("t1", "blocked", title = "Fix login")), "h") { true }.single()
        assertEquals("Fix login needs you", a.text)
    }
}
