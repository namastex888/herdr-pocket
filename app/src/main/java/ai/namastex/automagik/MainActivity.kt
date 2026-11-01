package ai.namastex.automagik

import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import android.os.Build
import android.Manifest
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.UUID


class MainActivity : ComponentActivity() {
    private val vm get() = (application as PocketApplication).vm
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { openLink(intent); openAlert(intent) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(0xfffbf6ee.toInt(), 0xff262029.toInt())
        )
        setContent {
            AutomagikTheme {
                PocketApp(vm)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openLink(intent); openAlert(intent) }
    // A notification's tap opens its agent.
    private fun openAlert(intent: Intent?) {
        val ref = Notify.opened(intent) ?: return
        vm.selectTerminal(ref)
        setIntent(Intent(this, MainActivity::class.java))
    }
    // A pairing link from outside the app is only offered: the person confirms it in the app.
    private fun openLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        vm.offerPairing(link)
        setIntent(Intent(this, MainActivity::class.java))
    }
}

@Composable fun Brand(size: Int = 28) {
    Image(painterResource(R.drawable.automagik), "Automagik", Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)))
}
@Composable @ReadOnlyComposable fun stateColor(word: String) = when (word) { "blocked", "error" -> Blocked; "working" -> Working; "done" -> Done; else -> Muted }
fun stateName(word: String) = when (word) { "blocked" -> "Needs you"; "working" -> "Working"; "done" -> "Done"; "error" -> "Error"; "idle" -> "Idle"; else -> "Terminal" }

@Composable fun Dot(color: Color, size: Int = 6) { Box(Modifier.size(size.dp).background(color, CircleShape)) }
@Composable fun SmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PocketApp(vm: PocketViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var addHost by remember { mutableStateOf(false) }
    var pairDesktop by remember { mutableStateOf(false) }
    // Pocket Link's QR code; a cancelled scan returns no contents.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::pair) }
    // The pairing dialog closes once a pairing it started finishes (the outcome shows as a message).
    var wasPairing by remember { mutableStateOf(false) }
    LaunchedEffect(state.pairing) { if (wasPairing && !state.pairing) pairDesktop = false; wasPairing = state.pairing }
    var addPrefill by remember { mutableStateOf<HostProfile?>(null) }
    var editHost by remember { mutableStateOf<HostProfile?>(null) }
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
    val welcome = selected == null && state.screen == "overview" && state.machines.values.none { it.connected || it.terminals.isNotEmpty() }
    val browsing = !state.demo && (welcome || (selected == null && state.screen == "machines"))
    DisposableEffect(browsing) { if (browsing) vm.discovery.start(); onDispose { vm.discovery.stop() } }
    val discovered by vm.discovery.hosts.collectAsStateWithLifecycle()
    val nearby = (listOfNotNull(Discovery.emulatorHost) + discovered).filter { n -> state.hosts.none { it.host == n.host && it.port == n.port } }
    fun addNearby(n: NearbyHost) { addPrefill = n.prefill(); addHost = true }
    // Git tails refresh while the drawer is open.
    LaunchedEffect(drawer) { snapshotFlow { drawer.targetValue == DrawerValue.Open }.collect(vm::drawerOpen) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    // Self-update: offer each new build once, open Android's install prompt, and continue after the install permission screen.
    val update by Updater.state.collectAsStateWithLifecycle()
    var offered by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(update) {
        when (val u = update) {
            is UpdateState.Available -> if (u.release.versionCode != offered) {
                offered = u.release.versionCode
                if (snackbar.showSnackbar("Automagik ${u.release.label} is available", "Update", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                    activity?.let(Updater::start)
            }
            is UpdateState.Confirm -> { runCatching { activity?.startActivity(u.intent) }; Updater.confirming(u.release) }
            is UpdateState.Failed -> if (state.screen != "settings") snackbar.showSnackbar(u.message)
            else -> {}
        }
    }
    LifecycleResumeEffect(Unit) { activity?.let(Updater::resume); onPauseOrDispose { } }
    LifecycleStartEffect(Unit) { vm.setForeground(true); onStopOrDispose { vm.setForeground(false) } }
    // Android 13+ asks before an app's first notification: once, when a machine first connects.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val anyConnected = state.machines.values.any { it.connected }
    LaunchedEffect(anyConnected) {
        if (anyConnected && Build.VERSION.SDK_INT >= 33 && vm.shouldAskNotifications()) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        if (CrashLog.announce() && snackbar.showSnackbar("Automagik closed unexpectedly last time", "Report", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
            vm.navigate("settings")
    }
    BackHandler(drawer.isOpen || selected != null || state.screen != "overview" || state.space != null) {
        when { drawer.isOpen -> scope.launch { drawer.close() }; selected != null -> vm.backToList(); else -> vm.showAll() }
    }
    fun closeDrawer(action: () -> Unit) { action(); scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = selected == null || drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.width(310.dp), drawerContainerColor = SidebarChrome) {
            Row(Modifier.fillMaxWidth().padding(22.dp, 22.dp, 14.dp, 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Brand(30); Spacer(Modifier.width(10.dp)); Text("Automagik", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                Spacer(Modifier.weight(1f)); IconButton(onClick = { scope.launch { drawer.close() } }) { Icon(Icons.Outlined.Close, "Close navigation", tint = Muted) }
            }
            DrawerRow("Cockpit", Icons.Outlined.GridView, state.screen == "overview" && state.space == null,
                trailing = state.machines.values.sumOf { it.terminals.count { t -> t.attention } }.takeIf { it > 0 }?.toString()) { closeDrawer(vm::showAll) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(31.dp).padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Spaces", color = SidebarDim, fontSize = 11.5.sp)
            }
            // Hosts in the order they were saved; each connection's spaces as the desktop groups and orders them.
            val sidebar = state.hosts.mapNotNull { h -> state.machines[h.id]?.let { m -> SidebarHost(h.id, h.name, m.connected, m.spaces, m.terminals, m.linked, m.colors, m.git) } } +
                state.machines.filterKeys { id -> state.hosts.none { it.id == id } }.map { (id, m) -> SidebarHost(id, vm.hostName(id), m.connected, m.spaces, m.terminals, m.linked, m.colors, m.git) }
            val rows = remember(sidebar, state.folded) { Sidebar.rows(sidebar, vm.programs, state.folded) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp)) {
                sidebarRows(rows, state.space, state.selected, onToggle = { vm.toggleFold(it.key.removePrefix("m:")) },
                    onSpace = { closeDrawer { vm.selectSpace(it.hostId, it.space.id) } },
                    onTerminal = { closeDrawer { vm.selectTerminal(it) } })
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
                Text("herdr-core · SSH", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.weight(1f)); Text(BuildConfig.VERSION_NAME, color = Muted, fontSize = 11.sp)
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
                            if (selected != null) Text(space?.name ?: "Terminal", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    (update as? UpdateState.Downloading)?.let { LinearProgressIndicator(progress = { it.progress }, Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Border) }
                        ?: HorizontalDivider(color = Border)
                    if (state.demo) Row(Modifier.fillMaxWidth().background(Accent.copy(alpha = .09f)).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Interface preview · sample data", color = Accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::leaveDemo) { Text("Exit", fontSize = 12.sp) }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    selected != null -> TerminalScreen(vm, state, terminal, space) { scope.launch { drawer.open() } }
                    state.screen == "machines" -> MachinesScreen(state, nearby, onAdd = { addHost = true }, onPair = { pairDesktop = true }, onAddNearby = ::addNearby, onConnect = { vm.connect(it) }, onEdit = { editHost = it }, onDisconnect = { vm.disconnect(it) }, onRemove = vm::removeHost)
                    state.screen == "settings" -> SettingsScreen(state, vm)
                    state.machines.values.none { it.connected || it.terminals.isNotEmpty() } -> WelcomeScreen(state, nearby, onAdd = { addHost = true }, onPair = { pairDesktop = true }, onAddNearby = ::addNearby, onConnect = { vm.connect(it) }, onPreview = vm::enterDemo)
                    else -> OverviewScreen(state, space, vm, onAll = vm::showAll)
                }
            }
        }
    }
    if (pairDesktop) PairDialog(state.pairing, onDismiss = { pairDesktop = false }, onScan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code in Pocket Link").setBeepEnabled(false).setOrientationLocked(false)) }, onPair = vm::pair)
    state.offeredPairing?.let { link -> ConfirmPairDialog(link, onPair = { vm.pair(link) }, onCancel = vm::dismissOfferedPairing) }
    if (addHost) HostDialog(null, onDismiss = { addHost = false; addPrefill = null }, onSave = { vm.saveHost(it); addHost = false; addPrefill = null; vm.connect(it) }, prefill = addPrefill)
    editHost?.let { host -> HostDialog(host, onDismiss = { editHost = null }, onSave = { vm.saveHost(it); editHost = null }) }
    state.passwordPrompt?.let { prompt -> state.hosts.find { it.id == prompt.hostId }?.let { host ->
        ConnectDialog(host, prompt.rejected, onDismiss = vm::dismissPasswordPrompt, onConnect = { password -> vm.connect(host, password) })
    } }
    if (newTerminal) NewTerminalDialog(state, onDismiss = { newTerminal = false }) { host, cwd, command -> vm.startTerminal(host, cwd, command); newTerminal = false }
    state.trust?.let { challenge ->
        AlertDialog(onDismissRequest = vm::rejectHost, icon = { Icon(Icons.Outlined.Fingerprint, null, tint = Accent) },
            title = { Text(if (challenge.profile.fingerprint.isBlank()) "Verify this machine" else "Host key has changed") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${challenge.profile.user}@${challenge.profile.host}:${challenge.profile.port}", color = Muted)
                Text("Compare this fingerprint with your machine’s SSH host key before trusting it.")
                SelectionContainer { Text(challenge.presented, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                if (challenge.profile.fingerprint.isNotBlank()) Text("Previously trusted: ${challenge.profile.fingerprint}", color = Blocked, fontSize = 12.sp)
            } }, confirmButton = { TextButton(onClick = vm::trustHost) { Text("Trust and connect") } }, dismissButton = { TextButton(onClick = vm::rejectHost) { Text("Cancel") } })
    }
}

@Composable fun DrawerRow(text: String, icon: ImageVector, selected: Boolean, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp))
        .background(if (selected) Accent.copy(alpha = .12f) else Color.Transparent).clickable(onClick = onClick).padding(12.dp, 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) Accent else Muted); Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), color = if (selected) Accent else Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.let { Text(it, fontSize = 12.sp, color = if (selected) Accent else Muted) }
    }
}

@Composable fun WelcomeScreen(state: PocketState, nearby: List<NearbyHost>, onAdd: () -> Unit, onPair: () -> Unit, onAddNearby: (NearbyHost) -> Unit, onConnect: (HostProfile) -> Unit, onPreview: () -> Unit) {
    val returning = state.hosts.isNotEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp), verticalArrangement = Arrangement.Center) {
        Surface(color = Raised, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(if (returning) 64.dp else 80.dp)) {
            Box(contentAlignment = Alignment.Center) { Brand(if (returning) 40 else 50) }
        }
        Spacer(Modifier.height(if (returning) 26.dp else 34.dp)); SmallLabel("Pick up where you left off")
        Spacer(Modifier.height(16.dp)); Text("Your herd,\nin your pocket.", fontSize = if (returning) 30.sp else 35.sp, lineHeight = if (returning) 36.sp else 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        if (returning) {
            // Returning users get one-tap connect instead of a detour through Machines.
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.hosts.forEach { host -> QuickConnectRow(host, state.machines[host.id] ?: MachineState(), state.linkedFor(host)) { onConnect(host) } }
            }
            Row(Modifier.padding(top = 6.dp)) {
                TextButton(onClick = onPair) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Pair a desktop") }
                TextButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("SSH machine") }
            }
        } else {
            Spacer(Modifier.height(18.dp)); Text("The same agents. The same terminals.\nConnect to your machine and keep going.", color = Muted, fontSize = 15.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(36.dp))
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Pair with Herdr desktop", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Connect over SSH")
            }
        }
        if (nearby.isNotEmpty()) { Spacer(Modifier.height(24.dp)); NearbySection(nearby, onAddNearby) }
        TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Explore the interface", color = Muted, fontSize = 13.sp) }
        Spacer(Modifier.height(36.dp)); HorizontalDivider(color = Border); Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), tint = Muted); Spacer(Modifier.width(10.dp)); Text("Connects to your existing herdr-core, paired or over SSH", color = Muted, fontSize = 12.sp) }
    }
}

@Composable fun QuickConnectRow(host: HostProfile, machine: MachineState, linked: List<LinkedMachine>, onConnect: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Surface, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !machine.connecting, onClick = onConnect)) {
        Column(Modifier.padding(16.dp, 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Computer, null, Modifier.size(22.dp), tint = Accent); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(host.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(host.address, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                if (machine.connecting) CircularProgressIndicator(Modifier.padding(horizontal = 18.dp).size(20.dp), strokeWidth = 2.dp)
                else FilledTonalButton(onClick = onConnect) { Text("Connect") }
            }
            if (linked.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Also reaches " + linked.joinToString(", ") { it.name }, fontSize = 12.sp, color = Muted, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            machine.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Blocked, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OverviewScreen(state: PocketState, space: PocketSpace?, vm: PocketViewModel, onAll: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val all = state.machines.flatMap { (host, machine) -> machine.terminals.filter { it.live }.map { TerminalRef(host, it.address) to it } }
    val scoped = all.filter { (ref, _) -> state.space == null || (ref.hostId == state.space.first && ref.address in space?.addresses.orEmpty()) }
    val shown = scoped.filter { (_, t) -> search.isBlank() || "${t.title} ${t.agent} ${t.cwd} ${t.message}".contains(search, true) }
    // The cockpit (every machine, no search): what needs the person, then what finished; the rest one tap away.
    val cockpit = space == null && search.isBlank()
    val paned = remember(state.machines) { state.machines.flatMap { (h, m) -> m.spaces.flatMap { s -> s.addresses.map { TerminalRef(h, it) } } }.toSet() }
    val sections = remember(all, paned, state.seen) { Cockpit.sections(all, { it in paned }) { r, t -> Cockpit.seenKey(r, t) in state.seen }.associateBy { it.rank } }
    fun count(rank: Int) = sections[rank]?.entries?.size ?: 0
    var everything by rememberSaveable { mutableStateOf(false) }
    // Machines whose connection dropped or failed; their terminal list may be stale.
    val offline = if (state.demo) emptyList() else state.hosts.filter { h -> state.machines[h.id]?.offline == true }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refreshAll, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(offline, key = { "offline:${it.id}" }) { host ->
            val machine = state.machines[host.id] ?: MachineState()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Blocked.copy(alpha = .1f)).border(1.dp, Blocked.copy(alpha = .3f), RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Dot(if (machine.connecting) Working else Blocked, 7); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(if (machine.connecting) "Reconnecting to ${host.name}…" else "${host.name} is disconnected", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!machine.connecting) machine.error?.let { Text(it, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (machine.connecting) CircularProgressIndicator(Modifier.padding(14.dp).size(18.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { vm.reconnect(host.id) }) { Text("Reconnect") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallLabel(if (space == null) "All machines" else space.folder.ifBlank { "Space" }, Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) { Dot(Ok); Spacer(Modifier.width(6.dp)); Text("${state.machines.values.count { it.connected }} connected", color = Muted, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (space == null) "Cockpit" else space.name, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
            Spacer(Modifier.height(6.dp))
            Text(when {
                space != null -> if (scoped.any { it.second.attention }) "A little attention goes a long way." else "Everything in this space, in one place."
                count(Cockpit.BLOCKED) == 1 -> "One agent needs you."
                count(Cockpit.BLOCKED) > 1 -> "${count(Cockpit.BLOCKED)} agents need you."
                else -> "Nothing needs you right now."
            }, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(vertical = 17.dp)) {
                Stat(if (space == null) count(Cockpit.BLOCKED) else scoped.count { it.second.attention }, "Needs you", Blocked, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(32.dp).background(Border))
                Stat(if (space == null) count(Cockpit.WORKING) else scoped.count { it.second.status == "working" }, "Working", Working, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(32.dp).background(Border))
                Stat(if (space == null) count(Cockpit.DONE) else scoped.count { (r, t) -> t.status == "done" && Cockpit.seenKey(r, t) !in state.seen }, "Done", Done, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            val focus = LocalFocusManager.current
            OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Find an agent or terminal…", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Border, focusedBorderColor = Accent, unfocusedContainerColor = Surface))
            Spacer(Modifier.height(8.dp))
        }
        fun location(ref: TerminalRef) = state.machines[ref.hostId]?.spaces?.find { ref.address in it.addresses }?.name ?: vm.hostName(ref.hostId)
        if (cockpit) {
            fun section(rank: Int) = sections[rank]?.let { section ->
                item(key = "section:$rank") { SectionHeader(section.title, section.entries.size) }
                section.entries.forEach { e ->
                    item(key = "c:${e.ref.hostId}:${e.ref.address}") {
                        TerminalCard(e.terminal, location(e.ref), e.ref.address, status = Cockpit.status(e.terminal, Cockpit.seenKey(e.ref, e.terminal) in state.seen).let { word ->
                            if (e.rank == Cockpit.BLOCKED && word != "blocked") "blocked" else word }) { vm.selectTerminal(e.ref) }
                    }
                    items(e.kids, key = { "k:${it.first.hostId}:${it.first.address}" }) { (ref, kid) -> KidRow(kid, ref.address) { vm.selectTerminal(ref) } }
                }
            }
            section(Cockpit.BLOCKED); section(Cockpit.DONE)
            if (sections[Cockpit.BLOCKED] == null && sections[Cockpit.DONE] == null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.TaskAlt, null, Modifier.size(32.dp), tint = Muted); Spacer(Modifier.height(14.dp))
                    Text("All clear", fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp))
                    Text("Agents that need you or finish show up here.", color = Muted, fontSize = 13.sp)
                }
            }
            // Working and idle agents, then terminals without a status: the drawer has them too, by space.
            val listed = sections.values.flatMap { it.entries.map { e -> e.ref } }.toSet()
            val plain = all.filter { it.first !in listed }
            val rest = count(Cockpit.WORKING) + count(Cockpit.IDLE) + plain.size
            if (rest > 0) item(key = "everything") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { everything = !everything }.padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (everything) "Hide the rest" else "Everything else", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text(listOfNotNull(count(Cockpit.WORKING).takeIf { it > 0 }?.let { "$it working" }, count(Cockpit.IDLE).takeIf { it > 0 }?.let { "$it idle" },
                        plain.size.takeIf { it > 0 }?.let { "$it other" }).joinToString(" · "), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Icon(if (everything) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = Muted)
                }
            }
            if (everything) {
                section(Cockpit.WORKING); section(Cockpit.IDLE)
                if (plain.isNotEmpty()) {
                    item(key = "section:plain") { SectionHeader("Terminals", plain.size) }
                    items(plain, key = { "p:${it.first.hostId}:${it.first.address}" }) { (ref, t) -> TerminalCard(t, location(ref), ref.address) { vm.selectTerminal(ref) } }
                }
            }
            return@LazyColumn
        }
        // A finished agent looked at here counts as idle, as in the cockpit.
        fun done(e: Pair<TerminalRef, CoreTerminal>) = e.second.status == "done" && Cockpit.seenKey(e.first, e.second) !in state.seen
        val groups = listOf("Needs you" to shown.filter { it.second.attention }, "Working" to shown.filter { it.second.status == "working" },
            "Done" to shown.filter(::done), "Ready" to shown.filter { !it.second.attention && it.second.status != "working" && !done(it) })
        groups.filter { it.second.isNotEmpty() }.forEach { (title, terminals) ->
            item { SectionHeader(title, terminals.size) }
            items(terminals, key = { "${it.first.hostId}:${it.first.address}" }) { (ref, terminal) ->
                TerminalCard(terminal, location(ref), ref.address, status = if (done(ref to terminal) || terminal.status != "done") terminal.status else "idle") { vm.selectTerminal(ref) }
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
}

@Composable fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallLabel(title); Spacer(Modifier.width(8.dp)); Text(count.toString(), color = Muted, fontSize = 11.sp)
    }
}

/** A sub-agent that needs the person, under its parent's card. */
@Composable fun KidRow(t: CoreTerminal, address: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp).clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Blocked.copy(alpha = .32f), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("↳", color = Muted, fontSize = 13.sp); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.message.isNotBlank()) Text(t.message, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp)); Text(statusLabel("blocked", t.kind), color = Blocked, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp)); Text(address, color = Muted.copy(alpha = .6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/** "Needs you", with what kind of answer when the agent said (permission, question, auth). */
fun statusLabel(word: String, kind: String) = stateName(word).let { if (word == "blocked" && kind.isNotBlank()) "$it · ${kind.replace('_', ' ')}" else it }

@Composable fun Stat(number: Int, text: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(number.toString(), color = color, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp)); Text(text, fontSize = 12.sp, color = Muted)
    }
}

@Composable fun TerminalCard(t: CoreTerminal, location: String, address: String, status: String = t.status, onClick: () -> Unit) {
    val color = stateColor(status)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, if (status == "blocked" || status == "error") Blocked.copy(alpha = .32f) else Border, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(35.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
            Icon(if (t.agent.lowercase().contains("sh")) Icons.Outlined.Terminal else Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.agent, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)); Spacer(Modifier.weight(1f))
                Dot(color, 6); Spacer(Modifier.width(6.dp)); Text(statusLabel(status, t.kind), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp)); Text(t.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.message.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(t.message, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(12.dp), tint = Muted); Spacer(Modifier.width(5.dp))
                Text(location, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(address, color = Muted.copy(alpha = .6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = Muted)
            }
        }
    }
}
