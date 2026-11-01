package dev.herdr.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Who the side-button assistant offers: the cockpit's Needs you first, then Done; never working or idle agents. */
class AssistTest {
    private fun t(address: String, status: String, ms: Long) = TerminalRef("h", address) to CoreTerminal.parse(JSONObject().put("address", address)
        .put("state", "live").put("status_ms", ms).put("status", JSONObject().put("state", status).put("app", "claude-code")))

    @Test fun needsYouThenDoneNewestFirst() {
        val sections = Cockpit.sections(listOf(t("t1", "done", 50), t("t2", "blocked", 10), t("t3", "working", 90), t("t4", "blocked", 30), t("t5", "idle", 99)), { true }) { _, _ -> false }
        assertEquals(listOf("t4", "t2", "t1"), Assist.targets(sections).map { it.ref.address })
        assertEquals(listOf("t4", "t2"), Assist.targets(sections, max = 2).map { it.ref.address })
    }
}
