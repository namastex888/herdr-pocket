package dev.herdr.pocket

/**
 * The home screen: Herdr desktop's agents mod in its priority order (mods/agents/main.js), of which the cockpit
 * shows Needs you, then Done, and keeps Working and Idle one tap away.
 */
object Cockpit {
    const val BLOCKED = 4
    const val DONE = 3
    const val WORKING = 2
    const val IDLE = 1
    private val TITLES = mapOf(BLOCKED to "Needs you", DONE to "Done", WORKING to "Working", IDLE to "Idle")

    /** An agent and its rank; [kids] are sub-agents that need the person and have no row of their own. */
    data class Entry(val ref: TerminalRef, val terminal: CoreTerminal, val rank: Int, val kids: List<Pair<TerminalRef, CoreTerminal>> = emptyList())
    data class Section(val rank: Int, val entries: List<Entry>) {
        val title get() = TITLES.getValue(rank)
    }

    /** The agents mod's word for an agent: error reads as blocked, and done as idle once the person looked at it here. */
    fun status(t: CoreTerminal, seen: Boolean) = when {
        t.status == "error" -> "blocked"
        t.status == "done" && seen -> "idle"
        else -> t.status
    }
    /** A sub-agent's word: never done (it reports to its parent, and the parent's done is the news). */
    fun kidStatus(t: CoreTerminal, seen: Boolean) = status(t, seen).let { if (it == "done") "idle" else it }
    fun rank(status: String) = when (status) { "blocked" -> BLOCKED; "done" -> DONE; "working" -> WORKING; "idle" -> IDLE; else -> 0 }

    /**
     * [terminals] from every machine. Agents are live terminals with a status that the desktop shows in a pane ([paned]),
     * or that have no live parent to report to. A parent ranks as its most urgent sub-agent. Within a rank, the most
     * recent status change comes first. [seen]: the person has looked at this finished turn on the phone.
     */
    fun sections(terminals: List<Pair<TerminalRef, CoreTerminal>>, paned: (TerminalRef) -> Boolean,
                 seen: (TerminalRef, CoreTerminal) -> Boolean): List<Section> {
        val live = terminals.filter { it.second.live }
        val byRef = live.toMap()
        val children = live.filter { it.second.parent.isNotBlank() }.groupBy { (ref, t) -> TerminalRef(ref.hostId, t.parent) }
        fun descendants(ref: TerminalRef, path: Set<TerminalRef> = setOf(ref)): List<Pair<TerminalRef, CoreTerminal>> =
            children[ref].orEmpty().filter { it.first !in path }.flatMap { listOf(it) + descendants(it.first, path + it.first) }
        val agents = live.filter { (ref, t) ->
            t.status.isNotBlank() && (paned(ref) || t.parent.isBlank() || TerminalRef(ref.hostId, t.parent) !in byRef)
        }
        val rows = agents.map { it.first }.toSet()
        val entries = agents.map { (ref, t) ->
            val kids = descendants(ref)
            val kidRanks = kids.map { (r, k) -> rank(kidStatus(k, seen(r, k))) }
            val rank = maxOf(rank(status(t, seen(ref, t))), kidRanks.maxOrNull() ?: 0).coerceAtLeast(IDLE)
            val waiting = if (rank == BLOCKED) kids.filterIndexed { i, (r, _) -> kidRanks[i] == BLOCKED && r !in rows } else emptyList()
            Entry(ref, t, rank, waiting)
        }
        return entries.sortedWith(compareByDescending<Entry> { it.rank }.thenByDescending { it.terminal.statusMs }.thenBy { it.ref.hostId }.thenBy { it.ref.address })
            .groupBy { it.rank }.map { (rank, list) -> Section(rank, list) }
    }

    /** The key a looked-at finished turn is remembered by; a new turn finishing shows as Done again. */
    fun seenKey(ref: TerminalRef, t: CoreTerminal) = "${ref.hostId}|${ref.address}|${t.turn}"
}
