package ai.namastex.automagik

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The status word a rank reads as on a task or project row. */
private fun word(rank: Int) = when (rank) { Cockpit.BLOCKED -> "blocked"; Cockpit.WORKING -> "working"; Cockpit.DONE -> "done"; else -> "idle" }
private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

/** Projects: one card per repository, or the desktop's spaces tree as its sidebar draws it. */
@Composable fun ProjectsScreen(state: PocketState, vm: PocketViewModel, pulse: Projects.Pulse, rows: List<SidebarRow>) {
    var tree by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "views") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("By repository", !tree) { tree = false }
                Pill("Spaces", tree) { tree = true }
            }
        }
        if (tree) {
            sidebarRows(rows, state.space, state.selected, onToggle = { vm.toggleFold(it.key.removePrefix("m:")) },
                onSpace = { vm.selectSpace(it.hostId, it.space.id) }, onTerminal = vm::selectTerminal)
            if (rows.none { it is SpaceRow }) item { Empty(Icons.Outlined.FolderOpen, "No spaces yet", "Your spaces appear here when you connect.") }
        } else {
            items(pulse.projects, key = { "p:${it.key}" }) { ProjectCard(it) { vm.openProject(it.key) } }
            if (pulse.projects.isEmpty()) item { Empty(Icons.Outlined.GridView, "No projects yet", "Each repository your spaces work in shows up here.") }
        }
    }
}

/**
 * A project's card: name, the share of its agents that need the person, work and finished, and its counts. The
 * title row's end is where the usage meter's spend goes once a machine publishes it; nothing shows there until then.
 */
@Composable fun ProjectCard(p: Projects.Project, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface)
        .border(1.dp, if (p.needsYou > 0) Blocked.copy(alpha = .32f) else Border, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.remote.isNotBlank()) Text(p.remote, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(plural(p.spaces.size, "space"), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
        }
        StatusBar(p.needsYou, p.working, p.done, p.agents.size)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Count(p.needsYou, "needs you", Blocked); Count(p.working, "working", Working); Count(p.done, "done", Done)
        }
    }
}

@Composable private fun Count(n: Int, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(n.toString(), color = if (n > 0) color else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp)); Text(text, color = Muted, fontSize = 12.sp)
    }
}

/** Shares of [total] agents: needs you, working and done; the rest of the track is idle. */
@Composable fun StatusBar(needsYou: Int, working: Int, done: Int, total: Int) {
    Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Raised)) {
        listOf(needsYou to Blocked, working to Working, done to Done).filter { it.first > 0 }.forEach { (n, color) ->
            Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(color))
        }
        val rest = total - needsYou - working - done
        if (rest > 0 && rest < total) Spacer(Modifier.weight(rest.toFloat()))
    }
}

/** A project: its counts, its tasks (branches), and its spaces with their agents. */
@Composable fun ProjectScreen(state: PocketState, vm: PocketViewModel, project: Projects.Project, pulse: Projects.Pulse, tree: List<SidebarRow>) {
    val tasks = pulse.tasks.filter { it.project == project.key }
    val spaceRows = tree.filterIsInstance<SpaceRow>()
    val bySpace = project.agents.groupBy { a -> a.space?.let { a.ref.hostId to it.id } }
    val listed = spaceRows.map { it.hostId to it.space.id }.toSet()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "counts") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountCard(project.needsYou, "needs you", Blocked, Modifier.weight(1f))
                CountCard(project.working, "working", Working, Modifier.weight(1f))
                CountCard(project.done, "done", Done, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(listOf(plural(project.spaces.size, "space"), plural(tasks.count { !it.untasked }, "task"),
                project.agents.map { it.machine }.filter { it.isNotBlank() }.distinct().let { if (it.isEmpty()) "" else it.joinToString(", ") })
                .filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
        }
        if (tasks.isNotEmpty()) {
            item(key = "tasks") { SectionHeader("Tasks", tasks.size) }
            items(tasks, key = { "t:${it.branch}" }) { TaskRow(it, showProject = false, onOpen = vm::selectTerminal) }
        }
        item(key = "spaces") { SectionHeader("Spaces", project.spaces.size) }
        for (row in spaceRows) {
            sidebarRows(listOf(row), state.space, state.selected, onToggle = {}, onSpace = { vm.selectSpace(it.hostId, it.space.id) }, onTerminal = vm::selectTerminal)
            agentCards(state, vm, bySpace[row.hostId to row.space.id].orEmpty(), "s:${row.key}")
        }
        // Agents working in this repository from a space that belongs to another project, or from no space.
        val elsewhere = bySpace.filterKeys { it == null || it !in listed }.values.flatten()
        if (elsewhere.isNotEmpty()) {
            item(key = "elsewhere") { SectionHeader("From other spaces", elsewhere.size) }
            agentCards(state, vm, elsewhere, "e")
        }
    }
}

private fun LazyListScope.agentCards(state: PocketState, vm: PocketViewModel, agents: List<Projects.Agent>, prefix: String) {
    items(agents.sortedBy { Projects.urgency(it.rank) }, key = { "$prefix:${it.ref.hostId}:${it.ref.address}" }) { a ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val status = Cockpit.status(a.terminal, Cockpit.seenKey(a.ref, a.terminal) in state.seen).let { if (a.rank == Cockpit.BLOCKED) "blocked" else it }
            TerminalCard(a.terminal, listOfNotNull(a.space?.name, a.branch).joinToString(" · ").ifBlank { a.machine }, a.ref.address, status) { vm.selectTerminal(a.ref) }
            a.entry.kids.forEach { (ref, kid) -> KidRow(kid, ref.address) { vm.selectTerminal(ref) } }
        }
    }
}

/** Tasks: every project's branches by status, Needs you first, with a project filter. */
@Composable fun TasksScreen(state: PocketState, vm: PocketViewModel, pulse: Projects.Pulse) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var idle by rememberSaveable { mutableStateOf(false) }
    val tasks = pulse.tasks.filter { filter == null || it.project == filter }
    val board = Projects.board(tasks)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "filter") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("All ${pulse.tasks.size}", filter == null) { filter = null }
                pulse.projects.forEach { p ->
                    val n = pulse.tasks.count { it.project == p.key }
                    if (n > 0) Pill("${p.name} $n", filter == p.key) { filter = p.key }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${plural(tasks.count { !it.untasked }, "task")} on branches · ${tasks.count { it.untasked }} untasked", color = Muted, fontSize = 12.sp)
        }
        for ((rank, list) in board) {
            val folded = rank == Cockpit.IDLE && !idle
            item(key = "h:$rank") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp).let { if (rank == Cockpit.IDLE) it.clickable { idle = !idle } else it },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${stateName(word(rank)).uppercase()} · ${list.size}", color = if (rank == Cockpit.IDLE) Muted else stateColor(word(rank)),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                    if (rank == Cockpit.IDLE) Text(if (idle) "Hide" else "Show", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (!folded) items(list, key = { "t:${it.project}:${it.branch}" }) { TaskRow(it, showProject = true, onOpen = vm::selectTerminal) }
        }
        if (board.isEmpty()) item { Empty(Icons.Outlined.Checklist, "No tasks yet", "Agents show up here by branch: each branch is a task.") }
    }
}

/** A task: its branch (or Untasked), then project · agent · machine; several agents each get a line to open. */
@Composable fun TaskRow(task: Projects.Task, showProject: Boolean, onOpen: (TerminalRef) -> Unit) {
    val color = stateColor(word(task.rank))
    val lead = task.agents.first()
    fun where(a: Projects.Agent) = listOfNotNull(a.terminal.agent, a.machine.ifBlank { null }).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface)
        .border(1.dp, if (task.rank == Cockpit.BLOCKED) Blocked.copy(alpha = .34f) else Border, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().clickable { onOpen(lead.ref) }.padding(12.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 8); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, color = if (task.untasked) Muted else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = listOfNotNull(task.projectName.takeIf { showProject }, if (task.agents.size == 1) where(lead) else "${task.agents.size} agents")
                Text(parts.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(statusLabel(word(task.rank), if (task.rank == Cockpit.BLOCKED) lead.terminal.kind else ""), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (task.agents.size > 1) task.agents.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(a.ref) }.padding(start = 30.dp, end = 12.dp, top = 2.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↳", color = Muted, fontSize = 12.sp); Spacer(Modifier.width(6.dp))
                Text("${a.terminal.title} · ${where(a)}", color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp)); Dot(stateColor(word(a.rank)), 6)
            }
        }
    }
}

/** A choice chip in Stage colours: filled ink when chosen. */
@Composable fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) Canvas else Ink, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Ink else Color.Transparent)
            .border(1.dp, if (selected) Ink else Border, RoundedCornerShape(50)).clickable(onClick = onClick).padding(12.dp, 7.dp))
}

@Composable private fun Empty(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(32.dp), tint = Muted); Spacer(Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}
