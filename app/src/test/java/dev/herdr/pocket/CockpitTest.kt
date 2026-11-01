package dev.herdr.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cockpit's sections against Herdr desktop's agents mod rules, on synthetic terminals. */
class CockpitTest {
    private val host = "h"
    private fun t(address: String, status: String?, parent: String = "", ms: Long = 0, turn: Long = 1, state: String = "live", kind: String? = null) =
        TerminalRef(host, address) to CoreTerminal.parse(JSONObject().put("address", address).put("state", state).put("turn", turn).put("status_ms", ms)
            .put("parent", parent.ifBlank { null } ?: JSONObject.NULL)
            .put("status", status?.let { JSONObject().put("state", it).put("app", "claude-code").put("kind", kind ?: JSONObject.NULL) } ?: JSONObject.NULL))

    private fun sections(vararg terminals: Pair<TerminalRef, CoreTerminal>, paned: Set<String>? = null, seen: Set<String> = emptySet()) =
        Cockpit.sections(terminals.toList(), { paned == null || it.address in paned }) { r, x -> Cockpit.seenKey(r, x) in seen }
            .associate { s -> s.rank to s.entries.map { it.ref.address } }

    @Test fun ranksNeedsYouThenDoneThenWorkingThenIdle() {
        val s = Cockpit.sections(listOf(t("t1", "idle"), t("t2", "working"), t("t3", "done"), t("t4", "blocked"), t("t5", "error")), { true }) { _, _ -> false }
        assertEquals(listOf("Needs you", "Done", "Working", "Idle"), s.map { it.title })
        assertEquals(setOf("t4", "t5"), s[0].entries.map { it.ref.address }.toSet())
    }

    @Test fun newestStatusChangeFirstWithinARank() {
        assertEquals(listOf("t2", "t3", "t1"), sections(t("t1", "done", ms = 10), t("t2", "done", ms = 30), t("t3", "done", ms = 20))[Cockpit.DONE])
    }

    @Test fun lookingAtAFinishedTurnTurnsItIdleUntilTheNextTurn() {
        val done = t("t1", "done", turn = 4)
        val key = Cockpit.seenKey(done.first, done.second)
        assertEquals(listOf("t1"), sections(done, seen = setOf(key))[Cockpit.IDLE])
        // It finished another turn since: Done again.
        assertEquals(listOf("t1"), sections(t("t1", "done", turn = 5), seen = setOf(key))[Cockpit.DONE])
    }

    @Test fun parentTakesItsMostUrgentSubAgentsPlace() {
        val s = Cockpit.sections(listOf(t("t1", "done"), t("t2", "blocked", parent = "t1", kind = "permission"), t("t3", "working", parent = "t2")),
            { it.address == "t1" }) { _, _ -> false }
        // t2 has no pane and a live parent: it is no row of its own; t1 moves to Needs you and lists it.
        assertEquals(1, s.size)
        val e = s.single().entries.single()
        assertEquals(Cockpit.BLOCKED, e.rank); assertEquals("t1", e.ref.address)
        assertEquals(listOf("t2"), e.kids.map { it.first.address })
        assertEquals("Needs you · permission", statusLabel("blocked", e.kids.single().second.kind))
    }

    @Test fun aSubAgentsDoneIsNotTheParentsDone() {
        // The kid finished; the parent is idle. The parent stays idle (a kid's done reads as idle).
        assertEquals(listOf("t1"), sections(t("t1", "idle"), t("t2", "done", parent = "t1"), paned = setOf("t1"))[Cockpit.IDLE])
        // A kid with its own pane is a row of its own, like on the desktop, and shows its own done there.
        val both = sections(t("t1", "idle"), t("t2", "done", parent = "t1"), paned = setOf("t1", "t2"))
        assertEquals(listOf("t2"), both[Cockpit.DONE]); assertEquals(listOf("t1"), both[Cockpit.IDLE])
    }

    @Test fun headlessAgentsWithoutALiveParentStillShow() {
        // Blocked and in no pane, with no parent or an ended one: it still needs the person.
        val s = sections(t("t1", "blocked"), t("t2", "blocked", parent = "t9"), t("t9", "done", state = "exited"), paned = emptySet())
        assertEquals(setOf("t1", "t2"), s[Cockpit.BLOCKED]!!.toSet())
    }

    @Test fun terminalsWithoutAStatusOrNotLiveAreNotAgents() {
        val s = sections(t("t1", null), t("t2", "blocked", state = "exited"))
        assertTrue(s.isEmpty())
    }

    @Test fun parentCyclesDoNotLoop() {
        val s = sections(t("t1", "working", parent = "t2"), t("t2", "blocked", parent = "t1"))
        assertEquals(setOf("t1", "t2"), s[Cockpit.BLOCKED]!!.toSet())
    }
}
