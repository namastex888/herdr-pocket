package dev.herdr.pocket

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// session increases with each successful connection, so open terminal views know to attach again.
data class MachineState(val connecting: Boolean = false, val connected: Boolean = false, val version: String = "",
    val terminals: List<CoreTerminal> = emptyList(), val spaces: List<PocketSpace> = emptyList(), val error: String? = null,
    val session: Int = 0, val needsPassword: Boolean = false, val linked: List<LinkedMachine> = emptyList(),
    /** Desktop avatar colours (shared.json space.colors), by space id. */
    val colors: Map<String, String> = emptyMap(),
    /** Git answers for the drawer, by Sidebar.gitKey: null is not a repository. */
    val git: Map<String, GitInfo?> = emptyMap()) {
    /** Dropped or failed, as opposed to never connected or disconnected on purpose. */
    val offline get() = !connected && (connecting || error != null)
    /** Worth retrying without asking: a dropped or failed connection that isn't waiting on a password. */
    val retryable get() = !connected && !connecting && error != null && !needsPassword
}
data class TerminalRef(val hostId: String, val address: String)
/** Asks for an SSH password after a host rejected this phone's key (or a previous password). */
data class PasswordPrompt(val hostId: String, val rejected: Boolean)
data class PocketState(
    val hosts: List<HostProfile> = emptyList(), val machines: Map<String, MachineState> = emptyMap(),
    val screen: String = "overview", val space: Pair<String, String>? = null, val selected: TerminalRef? = null,
    val publicKey: String = "", val trust: HostTrustRequired? = null, val message: String? = null,
    val terminalStatus: String = "", val demo: Boolean = false, val sending: Boolean = false,
    val nativeDisplay: Boolean = false, val screenText: String = "",
    val terminalFullscreen: Boolean = true, val nativeAlternateScreen: Boolean = false,
    val fontSize: Int = DEFAULT_FONT_SIZE, val modifiers: KeyModifiers = KeyModifiers(),
    val refreshing: Boolean = false, val passwordPrompt: PasswordPrompt? = null,
    /** A Pocket Link pairing is in progress. */
    val pairing: Boolean = false,
    /** A pairing link that arrived from outside the app, waiting for the person to confirm it. */
    val offeredPairing: PairingLink? = null,
    /** Folded drawer machine groups, as "hostId|machine". */
    val folded: Set<String> = emptySet()
)
const val DEFAULT_FONT_SIZE = 13
val FONT_SIZES = 8..24

class PocketViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("pocket", 0)
    val discovery = Discovery(application)
    private val keys = SshKeys(application.noBackupFilesDir)
    private val _state = MutableStateFlow(PocketState(nativeDisplay = prefs.getBoolean("native_display", false),
        fontSize = prefs.getInt("font_size", DEFAULT_FONT_SIZE).coerceIn(FONT_SIZES), folded = prefs.getStringSet("folded_machines", null).orEmpty().toSet(), hosts = runCatching {
        JSONArray(prefs.getString("hosts", "[]")).objects().map(HostProfile::parse)
    }.getOrDefault(emptyList())))
    val state = _state.asStateFlow()
    /** Herdr desktop's program table, for the drawer's names and icons. */
    val programs: Programs by lazy { Programs(JSONObject(application.assets.open("programs.json").bufferedReader().use { it.readText() })) }
    private val colorsRead = ConcurrentHashMap<String, Long>()
    private val homes = ConcurrentHashMap<String, String>()
    private val herdrHomes = ConcurrentHashMap<String, String>()
    // Git for the drawer runs in its own slow loop, never in the event refresh: one pass after connecting, a re-check when
    // a terminal stops working, and the desktop's 60 s fallback only while the drawer is open.
    private val gitWake = Channel<Unit>(Channel.CONFLATED)
    private val gitChecked = ConcurrentHashMap<String, Long>()
    private val gitDirty: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val wasWorking = ConcurrentHashMap<String, Set<String>>()
    @Volatile private var drawerOpen = false
    private val iconsTried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun drawerOpen(open: Boolean) { drawerOpen = open; if (open) gitWake.trySend(Unit) }
    fun toggleFold(key: String) {
        val folded = state.value.folded.let { if (key in it) it - key else it + key }
        prefs.edit { putStringSet("folded_machines", folded) }
        _state.update { it.copy(folded = folded) }
    }
    private val clients = ConcurrentHashMap<String, CoreClient>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val temporaryPasswords = ConcurrentHashMap<String, String>()
    private var attachJob: Job? = null
    private var nativeJob: Job? = null
    @Volatile private var activeChannel: Pair<CoreClient, Int>? = null
    @Volatile private var attachRevision = 0L
    @Volatile var terminalBytes: ((Long, Int, ByteArray) -> Unit)? = null
    @Volatile var resetTerminal: ((Long) -> Unit)? = null
    private var cols = 80
    private var rows = 24
    // Modes of the attached terminal's output stream, for key-bar cursor keys, paste and native scrolling.
    @Volatile private var modes = TerminalModes()
    private val keystrokes = Channel<Pair<Pair<CoreClient, Int>, ByteArray>>(64)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for ((target, bytes) in keystrokes) {
                if (activeChannel === target) runCatching { target.first.send(target.second, Wire.DATA, bytes) }
                    .onFailure { e -> _state.update { it.copy(terminalStatus = "Input not sent: ${e.message}") } }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { keys.publicKey() }.onSuccess { pub -> _state.update { it.copy(publicKey = pub) } }
                .onFailure { e -> _state.update { it.copy(message = "Could not create SSH key: ${e.message}") } }
        }
        Updater.check(quiet = true)
        ProgramIcons.load(application)
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                withTimeoutOrNull(15_000) { gitWake.receive() }
                for ((id, client) in clients.entries.toList()) runCatching { gitPass(id, client) }
            }
        }
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }
    private fun saveHosts(hosts: List<HostProfile>) {
        prefs.edit().putString("hosts", JSONArray(hosts.map { it.json() }).toString()).apply()
        _state.update { it.copy(hosts = hosts) }
    }
    fun saveHost(profile: HostProfile) {
        if (state.value.demo) leaveDemo()
        val hosts = state.value.hosts
        val previous = hosts.find { it.id == profile.id }
        val moved = previous != null && (previous.host != profile.host || previous.port != profile.port || previous.user != profile.user || previous.session != profile.session)
        val live = state.value.machines[profile.id]?.let { it.connected || it.connecting } == true
        saveHosts(if (previous != null) hosts.map { if (it.id == profile.id) profile.copy(linked = if (moved) emptyList() else it.linked) else it } else hosts + profile)
        // A live connection to the old address would otherwise keep running under the new name.
        if (moved && live) connect(profile)
    }
    fun removeHost(id: String) {
        disconnect(id)
        saveHosts(state.value.hosts.filter { it.id != id })
        _state.update { it.copy(machines = it.machines - id) }
    }
    fun navigate(screen: String) { detach(); _state.update { it.copy(screen = screen, selected = null, space = null) } }
    fun selectSpace(hostId: String, spaceId: String) {
        detach()
        _state.update { it.copy(screen = "overview", space = hostId to spaceId, selected = null) }
    }
    fun setTerminalFullscreen(fullscreen: Boolean) { _state.update { it.copy(terminalFullscreen = fullscreen) } }
    fun selectTerminal(ref: TerminalRef) {
        if (state.value.selected == ref) return
        detach()
        val space = state.value.machines[ref.hostId]?.spaces?.find { ref.address in it.addresses }
        _state.update { it.copy(screen = "overview", selected = ref, space = space?.let { s -> ref.hostId to s.id }, terminalStatus = "Connecting terminal…", screenText = "", terminalFullscreen = true, nativeAlternateScreen = false) }
    }
    fun backToList() { detach(); _state.update { it.copy(selected = null, terminalStatus = "") } }
    fun showAll() { detach(); _state.update { it.copy(screen = "overview", selected = null, space = null) } }

    fun connect(profile: HostProfile, password: String? = null) {
        disconnect(profile.id)
        _state.update { if (it.passwordPrompt?.hostId == profile.id) it.copy(passwordPrompt = null) else it }
        if (state.value.demo) leaveDemo()
        if (!password.isNullOrBlank()) temporaryPasswords[profile.id] = password
        jobs[profile.id] = viewModelScope.launch(Dispatchers.IO) {
            updateMachine(profile.id) { it.copy(connecting = true, connected = false, error = null, needsPassword = false) }
            var client: CoreClient? = null
            try {
                val connectionScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                val c = if (profile.paired) {
                    val t = IrohLink.open(getApplication(), profile.ticket, profile.session, connectionScope)
                    ensureActive()
                    CoreClient(t.input, t.output, t, connectionScope) { reason -> lost(profile, reason) }
                } else {
                    val t = keys.open(profile, password)
                    ensureActive()
                    CoreClient(t.input, t.output, t, connectionScope) { reason -> lost(profile, reason) }
                }
                client = c
                val hello = c.start()
                ensureActive()
                clients[profile.id] = c
                gitChecked.keys.removeIf { it.startsWith("${profile.id}|") }
                temporaryPasswords.remove(profile.id)
                updateMachine(profile.id) { it.copy(connecting = false, connected = true, version = hello.optString("version"), error = null, session = it.session + 1) }
                val changes = Channel<Unit>(Channel.CONFLATED)
                c.subscribe { changes.trySend(Unit) }
                refresh(profile.id, c)
                for (ignored in changes) {
                    delay(180)
                    runCatching { refresh(profile.id, c) }.onFailure { e -> updateMachine(profile.id) { it.copy(error = e.message) } }
                }
            } catch (e: Throwable) {
                client?.close()
                if (e is CancellationException) throw e
                var cause: Throwable? = e
                while (cause != null && cause !is HostTrustRequired) cause = cause.cause
                if (cause is HostTrustRequired) _state.update { it.copy(trust = cause as HostTrustRequired) }
                else if (e.isAuthFailure()) {
                    temporaryPasswords.remove(profile.id)
                    val rejected = !password.isNullOrBlank()
                    updateMachine(profile.id) { it.copy(needsPassword = true, error = if (rejected) "The host didn’t accept that password." else "The host didn’t accept this phone’s key.") }
                    // Keep a prompt already open for another machine; this one can be retried from its banner.
                    _state.update { if (it.passwordPrompt == null || it.passwordPrompt.hostId == profile.id) it.copy(passwordPrompt = PasswordPrompt(profile.id, rejected)) else it }
                }
                else { temporaryPasswords.remove(profile.id); updateMachine(profile.id) { it.copy(error = e.message ?: "Connection failed") } }
                updateMachine(profile.id) { it.copy(connecting = false, connected = false) }
            }
        }
    }
    private fun lost(profile: HostProfile, reason: String) {
        clients.remove(profile.id)
        updateMachine(profile.id) { it.copy(connected = false, connecting = false, error = "Disconnected: $reason") }
        if (state.value.selected?.hostId == profile.id) _state.update { it.copy(terminalStatus = "Disconnected") }
    }

    fun offerPairing(text: String) {
        val link = PairingLink.parse(text) ?: run { _state.update { it.copy(message = "That isn’t a Pocket Link pairing link.") }; return }
        _state.update { it.copy(offeredPairing = link) }
    }
    fun dismissOfferedPairing() { _state.update { it.copy(offeredPairing = null) } }

    /** Pairs with the desktop that showed [text] (a Pocket Link QR or pasted link), saves it and connects. */
    fun pair(text: String) {
        pair(PairingLink.parse(text) ?: run { _state.update { it.copy(message = "That isn’t a Pocket Link pairing link.") }; return })
    }
    fun pair(link: PairingLink) {
        if (state.value.demo) leaveDemo()
        _state.update { it.copy(pairing = true, offeredPairing = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).distinct().joinToString(" ").trim().ifBlank { "Android phone" }
                val name = IrohLink.pair(getApplication(), link, device)
                val desktop = IrohLink.desktopId(link.ticket)
                // Pairing the same desktop again keeps its card and history, with the new ticket.
                val previous = state.value.hosts.find { it.paired && runCatching { IrohLink.desktopId(it.ticket) }.getOrNull() == desktop }
                val profile = (previous ?: HostProfile(UUID.randomUUID().toString(), name, desktop.take(12), "", 0, transport = "iroh"))
                    .copy(ticket = link.ticket, transport = "iroh")
                withContext(Dispatchers.Main) {
                    saveHost(profile)
                    _state.update { it.copy(pairing = false, message = "Paired with $name") }
                    connect(profile)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(pairing = false, message = e.message ?: "Pairing failed") }
            }
        }
    }

    fun trustHost() {
        val challenge = state.value.trust ?: return
        val profile = challenge.profile.copy(fingerprint = challenge.presented)
        saveHosts(state.value.hosts.map { if (it.id == profile.id) profile else it })
        _state.update { it.copy(trust = null) }
        connect(profile, temporaryPasswords[profile.id])
    }
    fun dismissPasswordPrompt() { _state.update { it.copy(passwordPrompt = null) } }
    fun reconnect(hostId: String) { state.value.hosts.find { it.id == hostId }?.let { connect(it) } }
    /** Pull to refresh: reload connected inventories, and retry machines whose connection dropped or failed. */
    fun refreshAll() {
        if (state.value.refreshing) return
        if (state.value.demo) { viewModelScope.launch { _state.update { it.copy(refreshing = true) }; delay(400); _state.update { it.copy(refreshing = false) } }; return }
        _state.update { it.copy(refreshing = true) }
        val retry = state.value.hosts.filter { h -> state.value.machines[h.id]?.retryable == true }
        retry.forEach { connect(it) }
        viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            clients.entries.toList().map { (id, c) -> async { runCatching { refresh(id, c) }.onFailure { e -> updateMachine(id) { it.copy(error = e.message) } } } }.awaitAll()
            delay((400 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _state.update { it.copy(refreshing = false) }
        }
    }
    fun setFontSize(size: Int, persist: Boolean = true) {
        val value = size.coerceIn(FONT_SIZES)
        if (persist) prefs.edit().putInt("font_size", value).apply()
        _state.update { it.copy(fontSize = value) }
    }
    fun toggleCtrl() { _state.update { it.copy(modifiers = it.modifiers.copy(ctrl = !it.modifiers.ctrl)) } }
    fun toggleAlt() { _state.update { it.copy(modifiers = it.modifiers.copy(alt = !it.modifiers.alt)) } }
    fun rejectHost() { state.value.trust?.profile?.id?.let { temporaryPasswords.remove(it) }; _state.update { it.copy(trust = null) } }
    fun disconnect(id: String) {
        temporaryPasswords.remove(id)
        if (state.value.selected?.hostId == id) detach()
        jobs.remove(id)?.cancel()
        clients.remove(id)?.close()
        // A deliberate disconnect is not an error to surface or retry.
        updateMachine(id) { it.copy(connected = false, connecting = false, error = null, needsPassword = false) }
    }
    private fun updateMachine(id: String, transform: (MachineState) -> MachineState) {
        _state.update { it.copy(machines = it.machines + (id to transform(it.machines[id] ?: MachineState()))) }
    }
    private suspend fun refresh(id: String, client: CoreClient) {
        val list = client.call("terminal.list")
        val terminals = list.optJSONArray("terminals")?.objects()?.map(CoreTerminal::parse).orEmpty()
        val local = client.call("doc.get", JSONObject().put("name", "herdr-app"))
        val home = homes.getOrPut(id) { exec(client, "printenv", "HOME") }
        val spaces = LayoutReader.spaces(local.optJSONObject("value"), terminals = terminals, home = home).toMutableList()
        for (machine in list.optJSONArray("machines")?.objects().orEmpty()) {
            if (machine.clean("state") != "connected") continue
            val name = machine.getString("name")
            runCatching { client.call("doc.get", JSONObject().put("name", "herdr-app").put("on", name)) }
                .onSuccess { spaces.addAll(LayoutReader.spaces(it.optJSONObject("value"), name, terminals)) }
        }
        val linked = LinkedMachine.list(list, terminals)
        updateMachine(id) { it.copy(terminals = terminals, spaces = spaces, linked = linked, error = null) }
        rememberLinked(id, linked.map { it.name })
        // The desktop assigns colours as spaces appear; read them again now and then, never write them.
        val now = System.currentTimeMillis()
        if (now - (colorsRead[id] ?: 0L) > 30_000) { colorsRead[id] = now; runCatching { readColors(id, client) } }
        runCatching { fetchIcons(id, client, terminals) }
        gitWake.trySend(Unit)
    }
    /** Herdr desktop's config folder on the core's machine (it may have none: an SSH host without the app). */
    private suspend fun herdrHome(id: String, client: CoreClient): String? = herdrHomes[id] ?: exec(client, "printenv", "HERDR_APP_HOME")
        .ifBlank { (homes[id] ?: exec(client, "printenv", "HOME")).ifBlank { return null } + "/.config/herdr-app" }.also { herdrHomes[id] = it }
    /** The desktop's own icons for the programs on screen, read from its icons folder once per launch; the APK bundles none. */
    private suspend fun fetchIcons(id: String, client: CoreClient, terminals: List<CoreTerminal>) {
        val wanted = (terminals.map { programs.of(it).icon } + listOf(programs.agent.icon, programs.shell.icon, programs.program.icon))
            .filter { ProgramIcons.valid(it) && "$id|$it" !in iconsTried && !ProgramIcons.fresh(it) }.distinct()
        if (wanted.isEmpty()) return
        val home = herdrHome(id, client) ?: return
        for (icon in wanted) {
            iconsTried += "$id|$icon"
            runCatching { client.call("file.read", JSONObject().put("path", "$home/$icon")) }.getOrNull()
                ?.takeIf { it.optInt("last") >= it.optInt("lines") }?.optString("text")?.takeIf { it.contains("<svg") }
                ?.let { ProgramIcons.save(icon, it) }
        }
    }
    private suspend fun gitPass(id: String, client: CoreClient) {
        val m = state.value.machines[id]?.takeIf { it.connected } ?: return
        val byAddress = m.terminals.associateBy { it.address }
        val working = m.terminals.filter { it.status == "working" }.map { it.address }.toSet()
        val stopped = wasWorking.put(id, working).orEmpty() - working
        val places = m.spaces.groupBy({ it.machine to Sidebar.place(it, byAddress) }) { it }
        for ((where, spaces) in places) {
            val (machine, place) = where
            // Only folders the core reported, on machines that are up.
            if (!place.startsWith("/") || (machine.isNotBlank() && m.linked.find { it.name == machine }?.connected != true)) continue
            val key = Sidebar.gitKey(machine, place); val full = "$id|$key"
            // A terminal that stops working may have committed or switched branch.
            if (spaces.any { s -> s.tabs.any { t -> t.terminals.any { it in stopped } } }) gitDirty += full
            val now = System.currentTimeMillis()
            val last = gitChecked[full]
            val dirty = gitDirty.remove(full)
            val known = state.value.machines[id]?.git?.containsKey(key) == true
            val repo = state.value.machines[id]?.git?.get(key) != null
            if (!(last == null || dirty || (drawerOpen && (repo || !known) && now - last > 60_000))) continue
            gitChecked[full] = now
            runCatching { gitCheck(client, machine, place) }
                .onSuccess { info -> updateMachine(id) { it.copy(git = it.git + (key to info)) } }
                .onFailure { gitChecked[full] = now - 45_000 }   // unsure (machine away, odd error): keep the last answer, retry in 15 s
        }
    }
    /** Not a repository is an answer (null); anything else that fails is unsure and throws. */
    private suspend fun gitCheck(client: CoreClient, machine: String, place: String): GitInfo? {
        val status = git(client, machine, place, GitInfo.STATUS)
        if (status.first != 0) { if ("not a git repository" in status.third) return null; error(status.third.ifBlank { "git failed" }) }
        val rev = git(client, machine, place, GitInfo.REV_PARSE)
        if (rev.first != 0) error(rev.third.ifBlank { "git rev-parse failed" })
        return GitInfo.parse(status.second, rev.second, place) ?: error("unreadable git answer")
    }
    private suspend fun git(client: CoreClient, machine: String, cwd: String, args: List<String>): Triple<Int, String, String> {
        val params = JSONObject().put("argv", JSONArray(listOf("git", "--no-optional-locks") + args)).put("cwd", cwd)
        if (machine.isNotBlank()) params.put("on", machine)
        val r = withTimeout(20_000) { client.call("machine.exec", params) }
        fun text(k: String) = String(java.util.Base64.getDecoder().decode(r.optString(k)), Charsets.UTF_8)
        return Triple(if (r.optBoolean("timed_out")) -1 else r.optInt("code", -1), text("stdout"), text("stderr"))
    }
    private suspend fun readColors(id: String, client: CoreClient) {
        val home = herdrHome(id, client) ?: return
        val shared = JSONObject(client.call("file.read", JSONObject().put("path", "$home/shared.json")).optString("text", "{}"))
        val colors = shared.optJSONObject("space.colors") ?: return
        updateMachine(id) { it.copy(colors = colors.keys().asSequence().associateWith { k -> colors.optString(k) }) }
    }
    /** A program's trimmed output on the core's machine, or "" if it failed. */
    private suspend fun exec(client: CoreClient, vararg argv: String): String = runCatching {
        val r = client.call("machine.exec", JSONObject().put("argv", JSONArray(argv.toList())))
        if (r.optInt("code", 1) != 0) "" else String(java.util.Base64.getDecoder().decode(r.optString("stdout")), Charsets.UTF_8).trim()
    }.getOrDefault("")
    /** Saves which machines a core reaches, so the start screen lists them before the next connection. */
    private fun rememberLinked(id: String, names: List<String>) {
        val hosts = state.value.hosts
        if (hosts.none { it.id == id && it.linked != names }) return
        saveHosts(hosts.map { if (it.id == id) it.copy(linked = names) else it })
    }

    // Called only once the local terminal page has measured its actual viewport.
    fun terminalReady(columns: Int, lines: Int) {
        cols = columns.coerceIn(2, 500); rows = lines.coerceIn(2, 300)
        val selected = state.value.selected ?: return
        if (state.value.demo) {
            val rev = ++attachRevision
            resetTerminal?.invoke(rev)
            val t = state.value.machines[selected.hostId]?.terminals?.find { it.address == selected.address }
            terminalBytes?.invoke(rev, -1, ("\u001b[38;2;150;178;215mHerdr Pocket\u001b[0m  ·  Interface preview\r\n\r\n" +
                "\u001b[38;2;166;189;145m❯\u001b[0m ${t?.agent ?: "shell"}\r\n\r\n" +
                "  \u001b[1m${t?.title}\u001b[0m\r\n\r\n  ${t?.message ?: "Ready for your next task."}\r\n\r\n" +
                "  This is sample output. Connect your machine for a live terminal.\r\n\r\n").toByteArray())
            _state.update { it.copy(terminalStatus = "Preview • sample output") }
            return
        }
        val client = clients[selected.hostId] ?: run { _state.update { it.copy(terminalStatus = "Connect this machine to open its terminal") }; return }
        val rev = ++attachRevision
        resetTerminal?.invoke(rev)
        val streamModes = TerminalModes()
        modes = streamModes
        attachJob = viewModelScope.launch(Dispatchers.IO) {
            activeChannel?.let { (old, channel) -> runCatching { old.detach(channel) } }; activeChannel = null
            var opened: Int? = null
            var streamEnded = false
            try {
                val channel = client.attach(selected.address, cols, rows, { ch, bytes ->
                    streamModes.feed(bytes)
                    if (attachRevision == rev) terminalBytes?.invoke(rev, ch, bytes)
                }, { reason ->
                    streamEnded = true
                    if (attachRevision == rev) { activeChannel = null; _state.update { it.copy(terminalStatus = reason) } }
                })
                opened = channel
                if (attachRevision == rev && !streamEnded) {
                    activeChannel = client to channel
                    _state.update { it.copy(terminalStatus = "Live • SSH encrypted") }
                } else client.detach(channel)
            } catch (e: CancellationException) { opened?.let { runCatching { client.detach(it) } }; throw e }
            catch (e: Exception) { if (attachRevision == rev) _state.update { it.copy(terminalStatus = e.message ?: "Could not attach") } }
        }
    }
    fun resized(columns: Int, lines: Int) {
        cols = columns.coerceIn(2, 500); rows = lines.coerceIn(2, 300)
        val pair = activeChannel ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { pair.first.send(pair.second, Wire.RESIZE, Wire.resize(cols, rows)) } }
    }
    fun rendererFailed() {
        detach()
        _state.update { it.copy(nativeDisplay = true, terminalStatus = "Connecting text display…") }
    }
    fun preferNativeDisplay(native: Boolean) {
        detach()
        prefs.edit().putBoolean("native_display", native).apply()
        _state.update { it.copy(nativeDisplay = native) }
    }
    fun nativeReady(columns: Int, lines: Int) {
        if (state.value.demo) {
            _state.update { it.copy(screenText = "Herdr Pocket · Interface preview\n\nThis is sample output.\nConnect your machine for a live terminal.", terminalStatus = "Preview • sample output") }
            return
        }
        val ref = state.value.selected ?: return
        val client = clients[ref.hostId] ?: return
        if (activeChannel != null) { resized(columns, lines); return }
        nativeJob?.cancel()
        val rev = ++attachRevision
        cols = columns.coerceIn(2, 500); rows = lines.coerceIn(2, 300)
        nativeJob = viewModelScope.launch(Dispatchers.IO) {
            val modes = TerminalModes()
            this@PocketViewModel.modes = modes
            val updates = Channel<Unit>(Channel.CONFLATED)
            var snapshotJob: Job? = null
            try {
                // Wait for an attach reply even when navigation cancels the view,
                // so a late stream can be explicitly closed rather than orphaned.
                val channel = withContext(NonCancellable) {
                    client.attach(ref.address, cols, rows, { ch, bytes ->
                        modes.feed(bytes)
                        client.send(ch, Wire.CREDIT, Wire.credit(bytes.size))
                        updates.trySend(Unit)
                    }, { reason -> if (rev == attachRevision) _state.update { it.copy(terminalStatus = reason) } }, history = 0)
                }
                if (rev != attachRevision) { client.detach(channel); return@launch }
                activeChannel = client to channel
                _state.update { it.copy(terminalStatus = "Live • SSH encrypted · text display") }
                snapshotJob = launch {
                    for (ignored in updates) {
                        delay(100)
                        try {
                            val alternate = modes.alternate
                            val screen = client.call("terminal.read", JSONObject().put("terminal", ref.address).put("lines", if (alternate) 0 else 500))
                            if (rev == attachRevision) _state.update { it.copy(screenText = screen.optString("text"), nativeAlternateScreen = alternate) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (rev == attachRevision) _state.update { it.copy(terminalStatus = e.message ?: "Could not read terminal") } }
                    }
                }
                updates.trySend(Unit)
                awaitCancellation()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (rev == attachRevision) _state.update { it.copy(terminalStatus = e.message ?: "Could not open terminal") } }
            finally {
                snapshotJob?.cancel(); updates.close()
                if (rev == attachRevision) {
                    val active = activeChannel; activeChannel = null
                    active?.let { runCatching { it.first.detach(it.second) } }
                }
            }
        }
    }
    fun acknowledge(revision: Long, channel: Int, bytes: Int) {
        if (revision != attachRevision || channel < 0) return
        val ref = state.value.selected ?: return
        val c = clients[ref.hostId] ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { c.send(channel, Wire.CREDIT, Wire.credit(bytes)) } }
    }
    /** Sends input to the attached terminal. Typed keys consume any Ctrl/Alt armed on the key bar. */
    fun type(bytes: ByteArray, modifiable: Boolean = true) {
        val pair = activeChannel ?: return
        val mods = state.value.modifiers
        val modified = if (modifiable) KeyInput.modify(bytes, mods) else null
        if (modified != null) _state.update { it.copy(modifiers = KeyModifiers()) }
        if (!keystrokes.trySend(pair to (modified ?: bytes)).isSuccess) _state.update { it.copy(message = "Input queue is full. Wait for the connection to catch up.") }
    }
    /** A key-bar key: honors the program's cursor-key mode, then any armed modifiers. */
    fun pressKey(bytes: ByteArray) {
        type(if (state.value.modifiers.any) bytes else KeyInput.cursorKeys(bytes, modes.applicationCursor))
    }
    fun paste(text: String) {
        if (text.isEmpty() || activeChannel == null) return
        if (text.length > 256 * 1024) { _state.update { it.copy(message = "Clipboard is too large to paste into a terminal.") }; return }
        type(KeyInput.paste(text, modes.bracketedPaste), modifiable = false)
    }
    // Native snapshots have local shell history. Only alternate-screen apps receive scroll keys.
    fun scrollNative(lines: Int) {
        if (!state.value.nativeAlternateScreen || lines == 0) return
        val current = modes
        if (current.mouseTracking && current.sgrMouse) {
            val button = if (lines < 0) 64 else 65
            repeat(kotlin.math.abs(lines).coerceAtMost(12)) { type("\u001b[<$button;${(cols / 2).coerceAtLeast(1)};${(rows / 2).coerceAtLeast(1)}M".toByteArray(), modifiable = false) }
        } else {
            type((if (lines < 0) "\u001b[5~" else "\u001b[6~").toByteArray(), modifiable = false)
        }
    }
    fun sendMessage(text: String, confirmed: () -> Unit = {}) {
        val ref = state.value.selected ?: return
        val c = clients[ref.hostId] ?: return
        if (text.isBlank() || state.value.sending) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.call("terminal.send", JSONObject().put("terminal", ref.address).put("text", text)) }
                .onSuccess { withContext(Dispatchers.Main) { confirmed() } }
                .onFailure { e -> _state.update { it.copy(message = "Message not confirmed: ${e.message}. Check the terminal before retrying.") } }
            _state.update { it.copy(sending = false) }
        }
    }
    fun startTerminal(hostId: String, cwd: String, command: String) {
        val c = clients[hostId] ?: return
        if (state.value.demo) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val params = JSONObject().put("cwd", cwd.ifBlank { null })
                if (command.isNotBlank()) params.put("line", command)
                val spaceId = state.value.space?.takeIf { it.first == hostId }?.second
                val space = state.value.machines[hostId]?.spaces?.find { it.id == spaceId }
                if (space != null) {
                    if (space.machine.isNotBlank()) params.put("on", space.machine)
                    val localId = if (space.machine.isBlank()) space.id else space.id.removePrefix(space.machine + "/")
                    params.put("labels", JSONObject().put("herdr.space", localId).put("herdr.tab", "tb" + UUID.randomUUID().toString().replace("-", "")))
                }
                val reply = c.call("terminal.run", params)
                refresh(hostId, c)
                val address = reply.optJSONObject("terminal")?.optString("address") ?: reply.optString("address")
                if (address.isNotBlank()) selectTerminal(TerminalRef(hostId, address))
            } catch (e: Exception) { _state.update { it.copy(message = e.message ?: "Could not start terminal") } }
        }
    }
    fun detach() {
        ++attachRevision; attachJob = null
        if (state.value.modifiers.any) _state.update { it.copy(modifiers = KeyModifiers()) }
        nativeJob?.cancel(); nativeJob = null
        val previous = activeChannel; activeChannel = null
        if (previous != null) viewModelScope.launch(Dispatchers.IO) { runCatching { previous.first.detach(previous.second) } }
    }
    fun enterDemo() {
        clients.keys.toList().forEach(::disconnect)
        val id = "preview"
        val terminals = listOf(
            CoreTerminal("t1", "Authentication flow", "~/work/herdr", "live", "blocked", "Claude Code", "Waiting for your approval to run the test suite", 80, 24),
            CoreTerminal("t2", "Android companion", "~/work/herdr", "live", "working", "Codex", "Building the mobile navigation", 80, 24),
            CoreTerminal("t3", "API review", "~/work/herdr", "live", "done", "Claude Code", "Reviewed 12 files · ready when you are", 80, 24),
            CoreTerminal("t4", "Shell", "~/work/website", "live", "", "zsh", "", 80, 24))
        val spaces = listOf(PocketSpace("sp1", "herdr", "~/work/herdr", "", listOf(PocketTab("tb1", "Authentication", listOf("t1")), PocketTab("tb2", "Android", listOf("t2", "t3")))),
            PocketSpace("sp2", "Website", "~/work/website", "", listOf(PocketTab("tb3", "Shell", listOf("t4")))))
        _state.update { it.copy(demo = true, screen = "overview", selected = null, space = null,
            machines = mapOf(id to MachineState(connected = true, version = "preview", terminals = terminals, spaces = spaces))) }
    }
    fun leaveDemo() { detach(); _state.update { it.copy(demo = false, machines = emptyMap(), selected = null, space = null) } }
    fun hostName(hostId: String) = if (state.value.demo) "Preview machine" else state.value.hosts.find { it.id == hostId }?.name ?: "Machine"
    override fun onCleared() { discovery.stop(); clients.values.forEach { it.close() }; clients.clear() }
}
