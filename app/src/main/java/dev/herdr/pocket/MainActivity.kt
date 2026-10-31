package dev.herdr.pocket

import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.UUID

val Canvas = Color(0xff131518)
val Chrome = Color(0xff101114)
val Surface = Color(0xff17191d)
val Raised = Color(0xff23252a)
val Border = Color(0xff2d3035)
val Ink = Color(0xffeeeeef)
val Muted = Color(0xff989ba2)
val Accent = Color(0xff96b2d7)
val Working = Color(0xffe5c07b)
val Blocked = Color(0xfff0705f)
val Done = Color(0xff76a8ff)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xff101114.toInt())
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Chrome, background = Canvas,
                onBackground = Ink, surface = Surface, onSurface = Ink, surfaceVariant = Raised,
                onSurfaceVariant = Muted, outline = Border, secondary = Accent, error = Blocked)) {
                PocketApp()
            }
        }
    }
}

@Composable fun Brand(size: Int = 28) {
    Image(painterResource(R.drawable.herdr), "Herdr", Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)))
}
fun stateColor(word: String) = when (word) { "blocked", "error" -> Blocked; "working" -> Working; "done" -> Done; else -> Muted }
fun stateName(word: String) = when (word) { "blocked" -> "Needs you"; "working" -> "Working"; "done" -> "Done"; "error" -> "Error"; "idle" -> "Idle"; else -> "Terminal" }

@Composable fun Dot(color: Color, size: Int = 6) { Box(Modifier.size(size.dp).background(color, CircleShape)) }
@Composable fun SmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PocketApp(vm: PocketViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var addHost by remember { mutableStateOf(false) }
    var connectHost by remember { mutableStateOf<HostProfile?>(null) }
    var newTerminal by remember { mutableStateOf(false) }
    val selected = state.selected
    val space = state.space?.let { (host, id) -> state.machines[host]?.spaces?.find { it.id == id } }
    val terminal = selected?.let { state.machines[it.hostId]?.terminals?.find { t -> t.address == it.address } }
    val immersive = selected != null && state.terminalFullscreen
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(immersive, drawer.isOpen) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive && !drawer.isOpen) bars?.hide(WindowInsetsCompat.Type.systemBars())
        else bars?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    BackHandler(drawer.isOpen || selected != null || state.screen != "overview" || state.space != null) {
        when { drawer.isOpen -> scope.launch { drawer.close() }; selected != null -> vm.backToList(); else -> vm.showAll() }
    }
    fun closeDrawer(action: () -> Unit) { action(); scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = selected == null || drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.width(310.dp), drawerContainerColor = Chrome) {
            Row(Modifier.fillMaxWidth().padding(22.dp, 22.dp, 14.dp, 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Brand(30); Spacer(Modifier.width(10.dp)); Text("Herdr", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                Spacer(Modifier.weight(1f)); IconButton(onClick = { scope.launch { drawer.close() } }) { Icon(Icons.Outlined.Close, "Close navigation", tint = Muted) }
            }
            DrawerRow("All agents", Icons.Outlined.GridView, state.screen == "overview" && state.space == null,
                trailing = state.machines.values.sumOf { it.terminals.count { t -> t.attention } }.takeIf { it > 0 }?.toString()) { closeDrawer(vm::showAll) }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallLabel("Spaces"); Spacer(Modifier.weight(1f))
                Text("${state.machines.values.sumOf { it.spaces.size }}", fontSize = 11.sp, color = Muted)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                state.machines.forEach { (hostId, machine) ->
                    val hostName = if (state.demo) "Preview machine" else state.hosts.find { it.id == hostId }?.name ?: "Machine"
                    item {
                        Row(Modifier.padding(24.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (machine.connected) Color(0xffa6bd91) else Muted); Spacer(Modifier.width(8.dp))
                            Text(hostName, color = Muted, fontSize = 11.sp)
                        }
                    }
                    items(machine.spaces, key = { "$hostId:${it.id}" }) { s ->
                        DrawerRow(s.name, Icons.Outlined.FolderOpen, state.space == (hostId to s.id), trailing = s.addresses.size.toString()) {
                            closeDrawer { vm.selectSpace(hostId, s.id) }
                        }
                    }
                }
                if (state.machines.values.all { it.spaces.isEmpty() }) item {
                    Text("Your spaces appear here when you connect.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(24.dp, 12.dp))
                }
            }
            HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 18.dp))
            Spacer(Modifier.height(12.dp))
            DrawerRow("Machines", Icons.Outlined.Computer, state.screen == "machines") { closeDrawer { vm.navigate("machines") } }
            DrawerRow("Settings", Icons.Outlined.Settings, state.screen == "settings") { closeDrawer { vm.navigate("settings") } }
            Row(Modifier.fillMaxWidth().padding(24.dp, 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = Muted); Spacer(Modifier.width(6.dp))
                Text("herdr-core · SSH", fontSize = 11.sp, color = Muted)
                Spacer(Modifier.weight(1f)); Text(BuildConfig.VERSION_NAME, color = Muted, fontSize = 10.sp)
            }
        }
    }) {
        Scaffold(containerColor = Canvas, snackbarHost = { SnackbarHost(snackbar) },
            modifier = Modifier.imePadding(),
            contentWindowInsets = if (immersive) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!immersive) Column(Modifier.background(Chrome).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Open navigation") }
                        if (selected == null) { Brand(25); Spacer(Modifier.width(9.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(when { selected != null -> terminal?.title ?: selected.address; state.screen == "machines" -> "Machines"; state.screen == "settings" -> "Settings"; space != null -> space.name; else -> "Herdr" },
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected != null) Text(space?.name ?: "Terminal", fontSize = 10.sp, color = Muted)
                        }
                        if (selected != null) IconButton(onClick = { vm.setTerminalFullscreen(true) }) { Icon(Icons.Outlined.Fullscreen, "Full screen terminal", tint = Accent) }
                        if (selected != null) IconButton(onClick = vm::backToList) { Icon(Icons.Outlined.Close, "Back to agents", tint = Muted) }
                        else if (state.screen == "overview" && state.machines.values.any { it.connected } && !state.demo)
                            IconButton(onClick = { newTerminal = true }) { Icon(Icons.Outlined.Add, "New terminal", tint = Accent) }
                        else if (state.screen == "machines") IconButton(onClick = { addHost = true }) { Icon(Icons.Outlined.Add, "Add machine", tint = Accent) }
                        else Surface(color = Raised, shape = RoundedCornerShape(5.dp), modifier = Modifier.padding(end = 14.dp)) {
                            Text("POCKET", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(7.dp, 4.dp))
                        }
                    }
                    HorizontalDivider(color = Border)
                    if (state.demo) Row(Modifier.fillMaxWidth().background(Accent.copy(alpha = .09f)).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Interface preview · sample data", color = Accent, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::leaveDemo) { Text("Exit", fontSize = 11.sp) }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    selected != null -> TerminalScreen(vm, state, terminal, space) { scope.launch { drawer.open() } }
                    state.screen == "machines" -> MachinesScreen(state, onAdd = { addHost = true }, onConnect = { connectHost = it }, onDisconnect = vm::disconnect, onRemove = vm::removeHost)
                    state.screen == "settings" -> SettingsScreen(state, vm)
                    state.machines.values.none { it.connected || it.terminals.isNotEmpty() } -> WelcomeScreen(onAdd = { if (state.hosts.isEmpty()) addHost = true else vm.navigate("machines") }, onPreview = vm::enterDemo)
                    else -> OverviewScreen(state, space, vm::selectTerminal, onAll = vm::showAll)
                }
            }
        }
    }
    if (addHost) AddHostDialog(onDismiss = { addHost = false }, onSave = { vm.saveHost(it); addHost = false })
    connectHost?.let { host -> ConnectDialog(host, onDismiss = { connectHost = null }, onConnect = { password -> vm.connect(host, password); connectHost = null }) }
    if (newTerminal) NewTerminalDialog(state, onDismiss = { newTerminal = false }) { host, cwd, command -> vm.startTerminal(host, cwd, command); newTerminal = false }
    state.trust?.let { challenge ->
        AlertDialog(onDismissRequest = vm::rejectHost, icon = { Icon(Icons.Outlined.Fingerprint, null, tint = Accent) },
            title = { Text(if (challenge.profile.fingerprint.isBlank()) "Verify this machine" else "Host key has changed") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${challenge.profile.user}@${challenge.profile.host}:${challenge.profile.port}", color = Muted)
                Text("Compare this fingerprint with your machine’s SSH host key before trusting it.")
                SelectionContainer { Text(challenge.presented, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (challenge.profile.fingerprint.isNotBlank()) Text("Previously trusted: ${challenge.profile.fingerprint}", color = Blocked, fontSize = 11.sp)
            } }, confirmButton = { TextButton(onClick = vm::trustHost) { Text("Trust and connect") } }, dismissButton = { TextButton(onClick = vm::rejectHost) { Text("Cancel") } })
    }
}

@Composable fun DrawerRow(text: String, icon: ImageVector, selected: Boolean, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp))
        .background(if (selected) Accent.copy(alpha = .12f) else Color.Transparent).clickable(onClick = onClick).padding(12.dp, 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) Accent else Muted); Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), color = if (selected) Accent else Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.let { Text(it, fontSize = 11.sp, color = if (selected) Accent else Muted) }
    }
}

@Composable fun WelcomeScreen(onAdd: () -> Unit, onPreview: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center) {
        Surface(color = Raised, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) { Brand(50) }
        }
        Spacer(Modifier.height(34.dp)); SmallLabel("Pick up where you left off")
        Spacer(Modifier.height(16.dp)); Text("Your herd,\nin your pocket.", fontSize = 35.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(18.dp)); Text("The same agents. The same terminals.\nConnect to your machine and keep going.", color = Muted, fontSize = 15.sp, lineHeight = 24.sp)
        Spacer(Modifier.height(36.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Connect a machine", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Explore the interface", color = Muted, fontSize = 12.sp) }
        Spacer(Modifier.height(44.dp)); HorizontalDivider(color = Border); Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), tint = Muted); Spacer(Modifier.width(10.dp)); Text("Connects to your existing herdr-core over SSH", color = Muted, fontSize = 11.sp) }
    }
}

@Composable fun OverviewScreen(state: PocketState, space: PocketSpace?, onSelect: (TerminalRef) -> Unit, onAll: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val all = state.machines.flatMap { (host, machine) -> machine.terminals.filter { it.live }.map { TerminalRef(host, it.address) to it } }
    val scoped = all.filter { (ref, _) -> state.space == null || (ref.hostId == state.space.first && ref.address in space?.addresses.orEmpty()) }
    val shown = scoped.filter { (_, t) -> search.isBlank() || "${t.title} ${t.agent} ${t.cwd} ${t.message}".contains(search, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallLabel(if (space == null) "All machines" else space.folder.ifBlank { "Space" }, Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) { Dot(Color(0xffa6bd91)); Spacer(Modifier.width(6.dp)); Text("${state.machines.values.count { it.connected }} connected", color = Muted, fontSize = 10.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (space == null) "Agents" else space.name, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
            Spacer(Modifier.height(6.dp))
            Text(if (scoped.any { it.second.attention }) "A little attention goes a long way." else "Everything you’re working on, in one place.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(vertical = 17.dp)) {
                Stat(scoped.count { it.second.attention }, "Needs you", Blocked, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(32.dp).background(Border))
                Stat(scoped.count { it.second.status == "working" }, "Working", Working, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(32.dp).background(Border))
                Stat(scoped.count { it.second.status == "done" }, "Done", Done, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Find an agent or terminal…", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Border, focusedBorderColor = Accent, unfocusedContainerColor = Surface))
            Spacer(Modifier.height(8.dp))
        }
        val groups = listOf("Needs you" to shown.filter { it.second.attention }, "Working" to shown.filter { it.second.status == "working" },
            "Done" to shown.filter { it.second.status == "done" }, "Ready" to shown.filter { !it.second.attention && it.second.status !in listOf("working", "done") })
        groups.filter { it.second.isNotEmpty() }.forEach { (title, terminals) ->
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallLabel(title); Spacer(Modifier.width(8.dp)); Text(terminals.size.toString(), color = Muted, fontSize = 10.sp)
                }
            }
            items(terminals, key = { "${it.first.hostId}:${it.first.address}" }) { (ref, terminal) ->
                val machine = state.machines[ref.hostId]
                val s = machine?.spaces?.find { ref.address in it.addresses }
                val host = if (state.demo) "dev-machine" else state.hosts.find { it.id == ref.hostId }?.name.orEmpty()
                TerminalCard(terminal, s?.name ?: host, ref.address) { onSelect(ref) }
            }
        }
        if (shown.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(32.dp), tint = Muted); Spacer(Modifier.height(14.dp))
                Text(if (search.isNotBlank()) "No matching terminals" else "No live terminals here", color = Muted)
                if (space != null) TextButton(onClick = onAll) { Text("See all agents") }
            }
        }
    }
}

@Composable fun Stat(number: Int, text: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(number.toString(), color = color, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp)); Text(text, fontSize = 10.sp, color = Muted)
    }
}

@Composable fun TerminalCard(t: CoreTerminal, location: String, address: String, onClick: () -> Unit) {
    val color = stateColor(t.status)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, if (t.attention) Blocked.copy(alpha = .32f) else Border, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(35.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
            Icon(if (t.agent.lowercase().contains("sh")) Icons.Outlined.Terminal else Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.agent, color = Muted, fontSize = 10.sp, maxLines = 1); Spacer(Modifier.weight(1f))
                Dot(color, 5); Spacer(Modifier.width(5.dp)); Text(stateName(t.status), color = color, fontSize = 9.sp)
            }
            Spacer(Modifier.height(7.dp)); Text(t.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.message.isNotBlank()) { Spacer(Modifier.height(7.dp)); Text(t.message, color = Muted, fontSize = 11.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(12.dp), tint = Muted); Spacer(Modifier.width(5.dp))
                Text(location, color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(address, color = Muted.copy(alpha = .6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = Muted)
            }
        }
    }
}
