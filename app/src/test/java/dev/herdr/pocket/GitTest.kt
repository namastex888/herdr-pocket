package dev.herdr.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Git tails and worktree nesting against Herdr desktop's spaces-agents rules, on synthetic repositories. */
class GitTest {
    private fun status(head: String?, ab: String? = null) = listOfNotNull("# branch.oid 0123456789abcdef0123456789abcdef01234567",
        head?.let { "# branch.head $it" }, ab?.let { "# branch.upstream origin/$head" }, ab?.let { "# branch.ab $it" }, "1 .M N... 100644 100644 100644 a b src/x.kt").joinToString("\n")
    private fun info(top: String, common: String = "$top/.git", head: String? = "main", ab: String? = null) =
        GitInfo.parse(status(head, ab), "$top\n$common\n", top)!!

    @Test fun readsBranchAndAheadBehind() {
        val g = info("/w/brain-v3", head = "dev", ab = "+2 -471")
        assertEquals("dev", g.branch); assertEquals(2, g.ahead); assertEquals(471, g.behind)
        assertEquals("brain-v3", g.repo); assertFalse(g.linked)
        val noUpstream = info("/w/x", head = "feature")
        assertEquals(0, noUpstream.ahead); assertEquals(0, noUpstream.behind)
        assertEquals("(detached)", info("/w/x", head = "(detached)").branch)
        assertNull(info("/w/x", head = null).branch)
    }

    @Test fun readsOldGitAndBareRepos() {
        // Before git 2.31, rev-parse echoes --path-format and gives the common dir relative to where it ran.
        val old = GitInfo.parse(status("main"), "--path-format=absolute\n/w/app-wt\n../app/.git\n", "/w/app-wt")!!
        assertEquals("/w/app/.git", old.common); assertTrue(old.linked); assertEquals("app", old.repo)
        assertEquals("tools", info("/w/wt", common = "/srv/git/tools.git").repo)
        assertNull(GitInfo.parse(status("main"), "", "/w/x"))
    }

    @Test fun tailsAsTheDesktopWritesThem() {
        val brain = info("/w/brain-v3", head = "dev", ab = "+0 -471")
        assertEquals("dev", brain.tail("brain-v3", nested = false))              // spec: brain-v3 on dev → "dev ↓471"
        assertEquals("brain-v3 · dev", brain.tail("herdr-pocket", nested = false)) // same checkout, other name
        assertEquals("granited", info("/w/granited", head = "master").tail("LocalFleet", nested = false))
        assertEquals("(detached)", info("/w/x", head = "(detached)").tail("x", nested = false))
        assertEquals("detached", info("/w/x", head = null).tail("x", nested = false))
        assertEquals("fix-login", info("/w/app-fix", common = "/w/app/.git", head = "fix-login").tail("app-fix", nested = true))
        assertEquals("", info("/w/app", head = "main").tail("App", nested = false))
    }

    @Test fun nestsWorktreesUnderTheFirstMainCheckout() {
        val git = mapOf(
            "app" to info("/w/app"), "notes" to null,
            "app-fix" to info("/w/app-fix", "/w/app/.git", "fix"), "app-ui" to info("/w/app-ui", "/w/app/.git", "ui"),
            "app-copy" to info("/w/app-copy", "/w/app-copy/.git"),
            "lib-wt" to info("/w/lib-wt", "/w/lib/.git", "wip"),
            "app2" to info("/w/app2", "/w/app/.git").copy(top = "/w/app", common = "/w/app/.git"))
        val order = listOf("app-fix", "notes", "app", "lib-wt", "app-ui", "app2")
        assertEquals(listOf("notes" to false, "app" to false, "app-fix" to true, "app-ui" to true, "lib-wt" to false, "app2" to false),
            GitInfo.ordered(order) { git[it] })
    }

    @Test fun drawerShowsTailsAndNesting() {
        val programs = Programs(JSONObject(File("src/main/assets/programs.json").readText()))
        fun t(address: String, cwd: String) = CoreTerminal.parse(JSONObject().put("address", address).put("state", "live").put("cwd", cwd)
            .put("status", JSONObject().put("state", "idle").put("app", "claude-code")))
        val spaces = listOf(
            PocketSpace("s1", "app-fix", "/w/app-fix", "", listOf(PocketTab("b1", "1", listOf("t1")))),
            PocketSpace("s2", "App", "/w/app", "", listOf(PocketTab("b2", "1", listOf("t2")))),
            PocketSpace("s3", "notes", "/w/notes", "", emptyList()))
        val git = mapOf(Sidebar.gitKey("", "/w/app-fix/src") to info("/w/app-fix", "/w/app/.git", "fix", "+1 -0"),
            Sidebar.gitKey("", "/w/app") to info("/w/app", head = "dev", ab = "+0 -3"), Sidebar.gitKey("", "/w/notes") to null)
        val host = SidebarHost("mac", "Mac", true, spaces, listOf(t("t1", "/w/app-fix/src"), t("t2", "/w/app")), emptyList(), git = git)
        val rows = Sidebar.rows(listOf(host), programs).map { r -> when (r) {
            is SpaceRow -> "${if (r.nested) "  ⎇ " else ""}${r.space.name} [${r.tail}]${if (r.ahead > 0) " ↑${r.ahead}" else ""}${if (r.behind > 0) " ↓${r.behind}" else ""}"
            is AgentRow -> "${if (r.nested) "      " else "    "}${r.program.key}"
            else -> r.javaClass.simpleName
        } }
        assertEquals(listOf("App [dev] ↓3", "    claude", "  ⎇ app-fix [fix] ↑1", "      claude", "notes []"), rows)
    }
}
