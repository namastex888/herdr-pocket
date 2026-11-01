package dev.herdr.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The drawer against Herdr desktop's sidebar rules, on a synthetic copy of a real layout's shape. */
class SidebarTest {
    private val programs = Programs(JSONObject(File("src/main/assets/programs.json").readText()))

    private fun t(address: String, status: String = "", app: String = "", parent: String = "", name: String = "",
                  argv: String = "", title: String = "", live: Boolean = true) = CoreTerminal.parse(JSONObject().apply {
        put("address", address); put("state", if (live) "live" else "finished"); put("name", name); put("title", title); put("parent", parent)
        if (status.isNotBlank() || app.isNotBlank()) put("status", JSONObject().put("state", status).put("app", app))
        if (argv.isNotBlank()) put("argv", org.json.JSONArray(argv.split(' ')))
    })
    private fun tab(id: String, name: String, vararg terminals: String) = PocketTab(id, name, terminals.toList())
    private fun space(id: String, name: String, machine: String = "", vararg tabs: PocketTab) =
        PocketSpace(if (machine.isBlank()) id else "$machine/$id", name, "/work/$name", machine, tabs.toList())

    private val terminals = listOf(
        t("t1", "working", "claude-code"),                                      // Unfold
        t("t2", argv = "zsh"),                                                  // LocalFleet: a plain shell
        t("t10", "idle", "claude-code"),                                        // the orchestrator
        t("t11", "done", "omp", parent = "t10", name = "pm"),
        t("t12", "done", "omp", parent = "t10", name = "dogfood"),
        t("t13", "idle", "omp", parent = "t12", name = "df-install"),
        t("t14", "working", "codex", parent = "t12", name = "df-components"),
        t("t15", "error", "codex", parent = "t10", name = "t1-cap"),
        t("t16", parent = "t10", argv = "env PG=1 sleep 99", name = "fixture"),
        t("t17", "done", "omp", parent = "t10", name = "old", live = false),
        t("hx/t18", parent = "t10", argv = "zsh"),
        t("t20", "working", "claude-code", title = "✳ Android setup"),          // a lone numbered tab
        t("hx/t1", "done", "claude-code"),
        t("hx/t2", "blocked", "codex"),
    )
    private val spaces = listOf(
        space("sp1", "Unfold", "", tab("tb1", "Council brainstorm", "t1")),
        space("sp2", "LocalFleet", "", tab("tb2", "1", "t2")),
        space("sp3", "brain-v3", "", tab("tb3", "orchestrator", "t10"), tab("tb4", "2", "t12"), tab("tb5", "3", "t11")),
        space("sp4", "herdr-pocket", "", tab("tb6", "1", "t20")),
        space("sp9", "Tactu", "hx", tab("hx/tb9", "Claude Code", "hx/t1"), tab("hx/tb10", "review", "hx/t2")),
    )
    private val host = SidebarHost("mac", "MacBook Pro", true, spaces, terminals,
        listOf(LinkedMachine("zeta", "disconnected"), LinkedMachine("hx", "connected", 2)), mapOf("sp3" to "ansi3", "hx/sp9" to "ansi5"))

    private fun render(rows: List<SidebarRow>) = rows.map { r ->
        when (r) {
            is MachineHeaderRow -> "# ${r.title}${if (r.connected) "" else " (${r.state})"}${if (r.folded) " +" else ""}"
            is SpaceRow -> "[${r.letter} ${r.color}] ${r.space.name}"
            is AgentRow -> "  ${r.program.key}: ${r.label}${r.machineLabel?.let { " @$it" } ?: ""} <${r.status}>"
            is KidRow -> "    ${r.lines.joinToString("")} ${r.program.key}: ${r.label} <${r.status}>"
        }
    }

    @Test fun drawsTheDesktopTreeForOneCore() {
        assertEquals(listOf(
            "# MacBook Pro",
            "[U ansi6] Unfold",
            "  claude: Council brainstorm <working>",
            "[L ansi6] LocalFleet",                       // a plain shell gets no row
            "[B ansi3] brain-v3",
            "  claude: orchestrator <idle>",
            "    ├ agent: pm <idle>",                     // a sub-agent's done reads as idle
            "    ├ agent: dogfood <idle>",
            "    │├ agent: df-install <idle>",
            "    │└ codex: df-components <working>",
            "    ├ codex: t1-cap <blocked>",              // error needs you
            "    ├ program: fixture <>",                  // finished sub-agents (t17) are left out
            "    └ shell: zsh <>",                        // a sub-agent on another machine, named by its process
            "  agent: 2 <done>",                          // numbered tabs keep their numbers when there are several
            "    ├ agent: df-install <idle>",             // and its sub-agents repeat under the second copy
            "    └ codex: df-components <working>",
            "  agent: 3 <done>",
            "[H ansi6] herdr-pocket",
            "  claude: Claude Code <working>",            // a lone numbered tab shows the program's name
            "# hx",
            "[T ansi5] Tactu",
            "  claude: Claude Code @hx <done>",
            "  codex: review @hx <blocked>",
            "# zeta (disconnected)",
        ), render(Sidebar.rows(listOf(host), programs)))
    }

    @Test fun rowKeysAreUniqueEvenWhenATerminalShowsTwice() {
        val keys = Sidebar.rows(listOf(host), programs).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun foldedMachinesKeepOnlyTheirHeader() {
        val rows = render(Sidebar.rows(listOf(host), programs, setOf("mac|hx")))
        assertEquals(listOf("# hx +", "# zeta (disconnected)"), rows.takeLast(2))
    }

    @Test fun oneCoreWithoutOtherMachinesHasNoHeaders() {
        val rows = Sidebar.rows(listOf(host.copy(spaces = spaces.take(1), machines = emptyList())), programs)
        assertEquals(listOf("[U ansi6] Unfold", "  claude: Council brainstorm <working>"), render(rows))
    }

    @Test fun programsResolveLikeTheDesktop() {
        assertEquals("claude", programs.of(t("a", "idle", "claude-code")).key)
        assertEquals("agent", programs.of(t("a", "idle", "omp")).key)        // reports an app the table doesn't know
        assertEquals("shell", programs.of(t("a", argv = "/bin/zsh -l")).key)
        assertEquals("vite", programs.of(t("a", argv = "env NODE_ENV=dev node /repo/node_modules/.bin/vite.js --port 1")).key)
        assertEquals("hermes", programs.of(t("a", argv = "sh -c \"\$@\" sh hermes chat")).key)
        assertEquals("program", programs.of(t("a", argv = "my-tool --x")).key)
        assertEquals("my-tool", programs.of(t("a", argv = "my-tool --x")).name)
        assertEquals("zsh", programs.of(t("a", argv = "/bin/zsh -l")).name)
        assertEquals("Claude Code", programs.of(t("a", app = "claude")).name)
    }
}
