package dev.herdr.pocket

import org.json.JSONObject

/** One entry of Herdr desktop's program table (assets/programs.json): what a terminal runs, and its icon. */
data class Program(val key: String, val name: String, val icon: String, val tint: Boolean)

/** Herdr desktop's program table and its rules for naming what a terminal runs. */
class Programs(table: JSONObject) {
    private val shells = table.optJSONArray("shells")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
    private val interpreters = table.optJSONArray("interpreters")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
    private val byKey = LinkedHashMap<String, Program>()
    private val byAlias = HashMap<String, Program>()
    init {
        val programs = table.optJSONObject("programs") ?: JSONObject()
        for (key in programs.keys()) {
            val p = programs.getJSONObject(key)
            val program = Program(key, p.optString("name", key), p.optString("icon"), p.optBoolean("tint", true))
            byKey[key] = program
            for (field in listOf("run", "match")) p.optJSONArray(field)?.let { a -> for (i in 0 until a.length()) byAlias.putIfAbsent(a.getString(i), program) }
        }
    }
    private fun find(name: String) = byKey[name] ?: byAlias[name]
    val agent get() = byKey["agent"] ?: Program("agent", "Agent", "icons/agent.svg", true)
    val shell get() = byKey["shell"] ?: Program("shell", "Shell", "icons/shell.svg", true)
    val program get() = byKey["program"] ?: Program("program", "Program", "icons/program.svg", true)

    /** The agent's own report wins; otherwise what it runs, looking through interpreters and wrappers.
     *  As on the desktop, a shell or an unlisted program is named by its process ("zsh", "my-tool"). */
    fun of(t: CoreTerminal): Program {
        if (t.app.isNotBlank()) return find(t.app) ?: agent
        val command = command(t.argv) ?: return program
        return if (command in shells) shell.copy(name = command)
            else find(command) ?: find(command.substringBefore('.')) ?: program.copy(name = command)
    }

    /** The command an argv runs: past `env NAME=v`, `sudo`, `node`, `sh -c '"$@"' sh cmd` and the like. */
    fun command(argv: List<String>): String? {
        var rest = argv.map { it.substringAfterLast('/') }.zip(argv)
        while (rest.isNotEmpty()) {
            val (name, _) = rest.first()
            when {
                name.contains('=') && !name.startsWith("-") -> rest = rest.drop(1)
                name in shells && rest.getOrNull(1)?.first == "-c" && rest.getOrNull(2)?.second == "\"\$@\"" -> rest = rest.drop(4)
                name in interpreters -> rest = rest.drop(1).dropWhile { it.first.startsWith("-") }
                else -> return name.ifBlank { null }
            }
        }
        return argv.firstOrNull()?.substringAfterLast('/')?.ifBlank { null }
    }
}

/** A row of the drawer, as Herdr desktop's spaces-agents sidebar draws it. Keys are unique even when a terminal shows twice. */
sealed interface SidebarRow { val key: String }
data class MachineHeaderRow(override val key: String, val hostId: String, val machine: String, val title: String,
    val connected: Boolean, val state: String, val folded: Boolean) : SidebarRow
/** [tail] is the desktop's repo · branch text, with [ahead]/[behind] beside it; a [nested] worktree draws a branch glyph. */
data class SpaceRow(override val key: String, val hostId: String, val space: PocketSpace, val letter: String, val color: String,
    val nested: Boolean, val tail: String = "", val ahead: Int = 0, val behind: Int = 0) : SidebarRow
data class AgentRow(override val key: String, val hostId: String, val terminal: CoreTerminal, val label: String, val program: Program,
    val status: String, val machineLabel: String?, val nested: Boolean) : SidebarRow
data class KidRow(override val key: String, val hostId: String, val terminal: CoreTerminal, val label: String, val program: Program,
    val status: String, val lines: List<String>, val nested: Boolean) : SidebarRow

/** What one connection contributes: its spaces (local first in document order, then remote), terminals and machines. */
data class SidebarHost(val hostId: String, val name: String, val connected: Boolean, val spaces: List<PocketSpace>,
    val terminals: List<CoreTerminal>, val machines: List<LinkedMachine>, val colors: Map<String, String> = emptyMap(),
    /** Git answers by [Sidebar.gitKey]; null means not a repository, missing means not known yet. */
    val git: Map<String, GitInfo?> = emptyMap())

object Sidebar {
    /** Herdr desktop's avatar palette (spaces-agents MARK), by the space.colors names it stores. */
    val MARK = linkedMapOf("ansi6" to 0xff22a99a, "ansi3" to 0xffe3814f, "ansi4" to 0xff5b8def,
        "ansi5" to 0xff8b7cf6, "ansi2" to 0xff3fae6a, "ansi1" to 0xffd9649a)

    fun rows(hosts: List<SidebarHost>, programs: Programs, folded: Set<String> = emptySet()): List<SidebarRow> {
        val out = mutableListOf<SidebarRow>()
        for (host in hosts) {
            val remotes = (host.spaces.map { it.machine } + host.machines.map { it.name }).filter { it.isNotBlank() }.distinct().sorted()
            // Desktop draws machine headers only once another machine is known; the phone also needs them to tell hosts apart.
            val headers = remotes.isNotEmpty() || hosts.size > 1
            for (machine in listOf("") + remotes) {
                val foldKey = "${host.hostId}|$machine"
                val linked = host.machines.find { it.name == machine }
                if (headers) out += MachineHeaderRow("m:$foldKey", host.hostId, machine, if (machine.isBlank()) host.name else machine,
                    if (machine.isBlank()) host.connected else host.connected && linked?.connected == true,
                    if (machine.isBlank()) "" else linked?.state?.replace('_', ' ')?.ifBlank { null } ?: "asleep", foldKey in folded)
                if (headers && foldKey in folded) continue
                val byAddress = host.terminals.associateBy { it.address }
                fun info(space: PocketSpace) = host.git[gitKey(machine, place(space, byAddress))]
                for ((space, nested) in GitInfo.ordered(host.spaces.filter { it.machine == machine }, ::info)) {
                    val git = info(space)
                    out += SpaceRow("s:${host.hostId}:${space.id}", host.hostId, space, letter(space.name), host.colors[space.id] ?: "ansi6", nested,
                        git?.tail(space.name, nested).orEmpty(), git?.ahead ?: 0, git?.behind ?: 0)
                    agents(host, space, programs, nested, out)
                }
            }
        }
        return out
    }

    fun letter(name: String) = name.firstOrNull()?.uppercase() ?: "?"

    /** Where git runs for a space: the first of its terminals with a working directory, else the space's folder. */
    fun place(space: PocketSpace, byAddress: Map<String, CoreTerminal>): String =
        space.tabs.flatMap { it.terminals }.firstNotNullOfOrNull { byAddress[it]?.cwd?.ifBlank { null } } ?: space.folder
    fun gitKey(machine: String, place: String) = "$machine|$place"

    // agents() order: the space's tabs, then panes; plain shells and programs with a pane get no row.
    private fun agents(host: SidebarHost, space: PocketSpace, programs: Programs, nested: Boolean, out: MutableList<SidebarRow>) {
        val byAddress = host.terminals.associateBy { it.address }
        data class Candidate(val terminal: CoreTerminal, val tab: PocketTab, val program: Program)
        val candidates = space.tabs.flatMap { tab ->
            tab.terminals.mapNotNull { byAddress[it] }.filter { it.live }.map { Candidate(it, tab, programs.of(it)) }
                .filter { it.terminal.status.isNotBlank() || it.program.key == "hermes" }
        }
        val labels = candidates.map { c -> label(c.tab, lead(c.tab, byAddress, programs), programs) }
        val details = labels.map { if (it.isNumeric()) null else it }
        val seen = details.groupingBy { it.orEmpty() }.eachCount()
        candidates.forEachIndexed { i, c ->
            var detail = details[i]
            if (detail == null && (seen[""] ?: 0) > 1) detail = labels[i]
            val ownTitle = clean(c.terminal.rawTitle)
            if (detail != null && ownTitle.isNotBlank() && (seen[detail] ?: 0) > 1) detail = ownTitle
            val name = detail ?: if (labels[i].isNumeric()) c.program.name.ifBlank { labels[i] } else labels[i]
            val path = "a:${host.hostId}:${space.id}:${c.tab.id}:${c.terminal.address}"
            out += AgentRow(path, host.hostId, c.terminal, name, c.program, agentStatus(c.terminal),
                c.terminal.address.substringBefore('/', "").ifBlank { null }, nested)
            kids(host, c.terminal.address, emptyList(), path, programs, nested, out, setOf(c.terminal.address))
        }
    }

    // Every live terminal started by this one, on any machine, depth first, in core order.
    private fun kids(host: SidebarHost, parent: String, lines: List<String>, path: String, programs: Programs, nested: Boolean,
                     out: MutableList<SidebarRow>, ancestors: Set<String>) {
        if (lines.size >= 8) return
        val children = host.terminals.filter { it.live && it.address !in ancestors && isChild(it, parent) }
        children.forEachIndexed { i, k ->
            val last = i == children.lastIndex
            val program = programs.of(k)
            val key = "$path>${k.address}"
            out += KidRow(key, host.hostId, k, k.hcName.ifBlank { k.template }.ifBlank { program.name }.ifBlank { k.address },
                program, kidStatus(k.status), lines + if (last) "└" else "├", nested)
            kids(host, k.address, lines + if (last) "" else "│", key, programs, nested, out, ancestors + k.address)
        }
    }

    // A parent is recorded relative to the child's machine; addresses here are qualified by machine.
    private fun isChild(t: CoreTerminal, parent: String): Boolean {
        if (t.parent.isBlank()) return false
        if (t.parent == parent) return true
        val machine = t.address.substringBefore('/', "")
        return machine.isNotBlank() && "$machine/${t.parent}" == parent && !t.parent.contains('/')
    }

    private fun lead(tab: PocketTab, byAddress: Map<String, CoreTerminal>, programs: Programs): Pair<CoreTerminal, Program>? {
        val terminals = tab.terminals.mapNotNull { byAddress[it] }
        val agent = terminals.firstOrNull { it.status.isNotBlank() || it.app.isNotBlank() } ?: terminals.firstOrNull() ?: return null
        return agent to programs.of(agent)
    }

    private fun label(tab: PocketTab, lead: Pair<CoreTerminal, Program>?, programs: Programs): String {
        val (terminal, program) = lead ?: return tab.name
        val live = clean(terminal.rawTitle)
        val telling = live.isNotBlank() && live != program.name && !Regex("""^[~/]|^[A-Za-z]:\\""").containsMatchIn(live) &&
            '@' !in live && live.lowercase() != program.key
        return if (tab.name == program.name && telling) live else tab.name
    }

    /** A title without leading decoration (spinners, bullets), as desktop's clean(). */
    fun clean(title: String) = title.dropWhile { !it.isLetterOrDigit() }.trim()

    private fun String.isNumeric() = isNotEmpty() && all { it.isDigit() }
    fun agentStatus(t: CoreTerminal) = when (t.status) { "error" -> "blocked"; "" -> if (programsHermes(t)) "unknown" else ""; else -> t.status }
    private fun programsHermes(t: CoreTerminal) = t.app.isBlank() && t.argv.any { it.substringAfterLast('/') == "hermes" }
    /** Sub-agents never show the finished dot: their done reads as idle, an error as needing you. */
    fun kidStatus(status: String) = when (status) { "error" -> "blocked"; "done" -> "idle"; else -> status }
}
