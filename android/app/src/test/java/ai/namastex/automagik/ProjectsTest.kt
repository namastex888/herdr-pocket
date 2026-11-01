package ai.namastex.automagik

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Projects (repositories), tasks (branches) and navigation, on synthetic spaces and terminals. */
class ProjectsTest {
    private fun t(address: String, cwd: String, status: String? = "working", ms: Long = 0) = CoreTerminal.parse(JSONObject()
        .put("address", address).put("state", "live").put("cwd", cwd).put("status_ms", ms).put("turn", 1)
        .put("status", status?.let { JSONObject().put("state", it).put("app", "claude-code") } ?: JSONObject.NULL))
    private fun space(id: String, name: String, folder: String, vararg terminals: String, machine: String = "") =
        PocketSpace(id, name, folder, machine, listOf(PocketTab("tab-$id", "1", terminals.toList())))
    private fun git(top: String, branch: String?, remote: String = "", common: String = "$top/.git") =
        GitInfo.parse("# branch.oid 0\n" + (branch?.let { "# branch.head $it\n" } ?: ""), "$top\n$common\n", top, remote)!!

    private fun entries(hosts: List<SidebarHost>, seen: Set<String> = emptySet()) =
        Cockpit.sections(hosts.flatMap { h -> h.terminals.map { TerminalRef(h.hostId, it.address) to it } }, { true }) { r, x ->
            Cockpit.seenKey(r, x) in seen }.flatMap { it.entries }
    private fun projects(vararg hosts: SidebarHost) = Projects.build(hosts.toList(), entries(hosts.toList()))

    // A squad: three spaces in one repository (a main checkout and two worktrees), a second repo, and a folder that is none.
    private val terminals = listOf(
        t("t1", "/w/brain", "blocked", ms = 5), t("t2", "/w/brain-search", "working", ms = 4), t("t3", "/w/brain-recall", "done", ms = 3),
        t("t4", "/w/brain", "working", ms = 2), t("t5", "/w/site", "working", ms = 1), t("t6", "/tmp/scratch", "idle"),
        t("t7", "/w/site", null))
    private val spaces = listOf(
        space("s1", "brain", "/w/brain", "t1", "t4"), space("s2", "search squad", "/w/brain-search", "t2"),
        space("s3", "recall squad", "/w/brain-recall", "t3"), space("s4", "Website", "/w/site", "t5", "t7"),
        space("s5", "scratch", "/tmp/scratch", "t6"))
    private val gitMap = mapOf(
        Sidebar.gitKey("", "/w/brain") to git("/w/brain", "dev", "git@github.com:Example/brain.git"),
        Sidebar.gitKey("", "/w/brain-search") to git("/w/brain-search", "feat/search", "git@github.com:Example/brain.git", "/w/brain/.git"),
        Sidebar.gitKey("", "/w/brain-recall") to git("/w/brain-recall", "fix/recall", "https://bot:secret@github.com/example/brain", "/w/brain/.git"),
        Sidebar.gitKey("", "/w/site") to git("/w/site", "main"),
        Sidebar.gitKey("", "/tmp/scratch") to null)
    private val host = SidebarHost("mac", "Laptop", true, spaces, terminals, listOf(LinkedMachine("box", "connected"), LinkedMachine("old", "asleep")), git = gitMap)

    @Test fun normalisesRemotesToOwnerAndRepo() {
        assertEquals("Example/brain", Projects.remoteKey("git@github.com:Example/brain.git"))
        assertEquals("example/brain", Projects.remoteKey("https://bot:secret@github.com/example/brain/"))
        assertEquals("o/r", Projects.remoteKey("ssh://git@git.example.com:2222/o/r.git"))
        assertEquals("group/sub/repo", Projects.remoteKey("https://gitlab.example.com/group/sub/repo.git"))
        assertEquals("o/r", Projects.remoteKey("example.com:o/r"))
        assertNull(Projects.remoteKey("/srv/git/tools.git"))
        assertNull(Projects.remoteKey("file:///srv/git/tools.git"))
        assertNull(Projects.remoteKey("  "))
        assertEquals("Example/brain", git("/w/x", "main", "git@github.com:Example/brain.git").remote)
    }

    @Test fun groupsSpacesOfOneRepositoryIntoOneProject() {
        val p = projects(host)
        assertEquals(listOf("brain", "site", "scratch"), p.map { it.name })
        val brain = p.first()
        assertEquals("Example/brain", brain.remote)
        assertEquals(listOf("brain", "search squad", "recall squad"), brain.spaces.map { it.second.name })
        // Without a remote, the repository's git dir names it; a folder that is not a repository is its space.
        assertEquals("", p[1].remote); assertEquals(listOf("Website"), p[1].spaces.map { it.second.name })
        assertEquals("space:mac:s5", p[2].key)
    }

    @Test fun countsAgentsByStatus() {
        val brain = projects(host).first()
        assertEquals(1, brain.needsYou); assertEquals(2, brain.working); assertEquals(1, brain.done)
        // A plain shell (t7) is not an agent; a finished turn looked at counts as idle, as on Today.
        val site = projects(host)[1]
        assertEquals(1, site.agents.size)
        val seen = setOf(Cockpit.seenKey(TerminalRef("mac", "t3"), terminals[2]))
        assertEquals(0, Projects.build(listOf(host), entries(listOf(host), seen)).first().done)
    }

    @Test fun sortsNeedsYouFirstThenWorking() {
        val quiet = SidebarHost("q", "Quiet", true, listOf(space("a", "alpha", "/a", "a1"), space("b", "beta", "/b", "b1", "b2"), space("c", "gamma", "/c", "c1")),
            listOf(t("a1", "/a", "idle"), t("b1", "/b", "working"), t("b2", "/b", "working"), t("c1", "/c", "blocked")), emptyList())
        assertEquals(listOf("gamma", "beta", "alpha"), projects(quiet).map { it.name })
    }

    @Test fun mergesTheSameRemoteAcrossMachinesButNotLocalRepos() {
        val other = SidebarHost("pc", "Desktop", true, listOf(space("x1", "brain", "/home/brain", "p1"), space("x2", "site", "/w/site", "p2")),
            listOf(t("p1", "/home/brain", "blocked"), t("p2", "/w/site", "working")), emptyList(),
            git = mapOf(Sidebar.gitKey("", "/home/brain") to git("/home/brain", "feat/search", "git@github.com:example/brain.git"),
                Sidebar.gitKey("", "/w/site") to git("/w/site", "main")))
        val p = projects(host, other)
        assertEquals(4, p.find { it.name == "brain" }!!.spaces.size)
        assertEquals(2, p.count { it.name == "site" })   // same path, two machines: two repositories
    }

    @Test fun anAgentGoesByItsOwnWorkingDirectory() {
        // t4 sits in the main checkout's space but works in the search worktree.
        val moved = host.copy(terminals = terminals.map { if (it.address == "t4") it.copy(cwd = "/w/brain-search") else it })
        val brain = projects(moved).first()
        assertEquals("feat/search", brain.agents.find { it.ref.address == "t4" }!!.branch)
        assertEquals("brain", brain.agents.find { it.ref.address == "t4" }!!.space?.name)
    }

    @Test fun branchesAreTasksAndTrunksAreUntasked() {
        listOf("main", "master", "dev", "develop", "trunk", "HEAD", "(detached)", "", null).forEach { assertNull(it, Projects.task(it)) }
        assertEquals("feat/x", Projects.task("feat/x"))
        val tasks = Projects.tasks(projects(host))
        fun of(project: String) = tasks.filter { it.projectName == project }.map { "${it.title}:${it.agents.joinToString(",") { a -> a.ref.address }}" }
        assertEquals(listOf("Untasked:t1,t4", "feat/search:t2", "fix/recall:t3"), of("brain"))
        assertEquals(listOf("Untasked:t5"), of("site"))
        assertEquals(listOf("Untasked:t6"), of("scratch"))
    }

    @Test fun boardsTasksByStatusMostUrgentFirst() {
        val board = Projects.board(Projects.tasks(projects(host)))
        assertEquals(listOf(Cockpit.BLOCKED, Cockpit.WORKING, Cockpit.DONE, Cockpit.IDLE), board.map { it.first })
        assertEquals(listOf("brain/Untasked"), board[0].second.map { "${it.projectName}/${it.title}" })
        // Named tasks before untasked, then the newest status change.
        assertEquals(listOf("brain/feat/search", "site/Untasked"), board[1].second.map { "${it.projectName}/${it.title}" })
        assertEquals("Laptop", board[0].second[0].agents[0].machine)
    }

    @Test fun namesTheMachineAnAgentRunsOn() {
        val remote = SidebarHost("mac", "Laptop", true, listOf(space("box/s1", "api", "/srv/api", "box/t1", machine = "box")),
            listOf(t("box/t1", "/srv/api", "working")), emptyList(),
            git = mapOf(Sidebar.gitKey("box", "/srv/api") to git("/srv/api", "feat/api", "git@github.com:example/api.git")))
        val agent = projects(remote).single().agents.single()
        assertEquals("box", agent.machine); assertEquals("feat/api", agent.branch)
    }

    @Test fun treeKeepsOnlyTheProjectsSpaces() {
        val programs = Programs(JSONObject(File("src/main/assets/programs.json").readText()))
        val two = SidebarHost("pc", "Desktop", true, listOf(space("x1", "other", "/o", "p1")), listOf(t("p1", "/o", "working")), emptyList())
        val rows = Sidebar.rows(listOf(host, two), programs)
        val brain = Projects.build(listOf(host, two), entries(listOf(host, two))).find { it.name == "brain" }!!
        val tree = Projects.tree(rows, brain).map { r -> when (r) {
            is MachineHeaderRow -> "# ${r.title}"; is SpaceRow -> "${if (r.nested) "  " else ""}${r.space.name}"; else -> "    agent"
        } }
        assertEquals(listOf("# Laptop", "brain", "    agent", "    agent", "  search squad", "    agent", "  recall squad", "    agent"), tree)
    }

    @Test fun countsConnectedMachinesWithTheMachinesTheyReach() {
        val down = SidebarHost("x", "Off", false, emptyList(), emptyList(), listOf(LinkedMachine("y", "connected")))
        assertEquals(2, Projects.machinesConnected(listOf(host, down)))
    }

    @Test fun backReturnsToTheTabThenToday() {
        val ref = TerminalRef("mac", "t1")
        assertEquals(Nav.Place(Nav.TASKS), Nav.back(Nav.Place(Nav.TASKS, selected = ref)))
        assertEquals(Nav.Place(Nav.PROJECTS, "repo:x"), Nav.back(Nav.Place(Nav.PROJECTS, "repo:x", "mac" to "s1")))
        assertEquals(Nav.Place(Nav.PROJECTS), Nav.back(Nav.Place(Nav.PROJECTS, "repo:x")))
        assertEquals(Nav.Place(Nav.TODAY), Nav.back(Nav.Place(Nav.MACHINES)))
        assertEquals(Nav.Place(Nav.TODAY), Nav.back(Nav.Place(Nav.SETTINGS)))
        assertNull(Nav.back(Nav.Place(Nav.TODAY)))
        assertEquals(false, Nav.barShown(Nav.Place(Nav.TASKS, selected = ref)))
        assertEquals(Nav.Place(Nav.PROJECTS), Nav.tab(Nav.PROJECTS))
    }
}
