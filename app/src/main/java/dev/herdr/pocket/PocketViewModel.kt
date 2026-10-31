package dev.herdr.pocket

import android.app.Application
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

data class MachineState(val connecting: Boolean = false, val connected: Boolean = false, val version: String = "",
    val terminals: List<CoreTerminal> = emptyList(), val spaces: List<PocketSpace> = emptyList(), val error: String? = null)
data class TerminalRef(val hostId: String, val address: String)
data class PocketState(
    val hosts: List<HostProfile> = emptyList(), val machines: Map<String, MachineState> = emptyMap(),
    val screen: String = "overview", val space: Pair<String, String>? = null, val selected: TerminalRef? = null,
    val publicKey: String = "", val trust: HostTrustRequired? = null, val message: String? = null,
    val terminalStatus: String = "", val demo: Boolean = false, val sending: Boolean = false,
    val nativeDisplay: Boolean = false, val screenText: String = "",
    val terminalFullscreen: Boolean = true, val nativeAlternateScreen: Boolean = false
)

class PocketViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("pocket", 0)
    private val keys = SshKeys(application.noBackupFilesDir)
    private val _state = MutableStateFlow(PocketState(nativeDisplay = prefs.getBoolean("native_display", false), hosts = runCatching {
        JSONArray(prefs.getString("hosts", "[]")).objects().map(HostProfile::parse)
    }.getOrDefault(emptyList())))
    val state = _state.asStateFlow()
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
    @Volatile private var nativeModes = TerminalModes()
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
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }
    private fun saveHosts(hosts: List<HostProfile>) {
        prefs.edit().putString("hosts", JSONArray(hosts.map { it.json() }).toString()).apply()
        _state.update { it.copy(hosts = hosts) }
    }
    fun saveHost(profile: HostProfile) {
        if (state.value.demo) leaveDemo()
        saveHosts(state.value.hosts.filter { it.id != profile.id } + profile)
        _state.update { it.copy(screen = "machines") }
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
        if (state.value.demo) leaveDemo()
        if (!password.isNullOrBlank()) temporaryPasswords[profile.id] = password
        jobs[profile.id] = viewModelScope.launch(Dispatchers.IO) {
            updateMachine(profile.id) { it.copy(connecting = true, connected = false, error = null) }
            var client: CoreClient? = null
            try {
                val transport = keys.open(profile, password)
                ensureActive()
                val connectionScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                val c = CoreClient(transport.input, transport.output, transport, connectionScope) { reason ->
                    clients.remove(profile.id)
                    updateMachine(profile.id) { it.copy(connected = false, connecting = false, error = "Disconnected: $reason") }
                    if (state.value.selected?.hostId == profile.id) _state.update { it.copy(terminalStatus = "Disconnected • reconnect from Machines") }
                }
                client = c
                val hello = c.start()
                ensureActive()
                clients[profile.id] = c
                temporaryPasswords.remove(profile.id)
                updateMachine(profile.id) { it.copy(connecting = false, connected = true, version = hello.optString("version"), error = null) }
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
                else { temporaryPasswords.remove(profile.id); updateMachine(profile.id) { it.copy(error = e.message ?: "Connection failed") } }
                updateMachine(profile.id) { it.copy(connecting = false, connected = false) }
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
    fun rejectHost() { state.value.trust?.profile?.id?.let { temporaryPasswords.remove(it) }; _state.update { it.copy(trust = null) } }
    fun disconnect(id: String) {
        temporaryPasswords.remove(id)
        if (state.value.selected?.hostId == id) detach()
        jobs.remove(id)?.cancel()
        clients.remove(id)?.close()
        updateMachine(id) { it.copy(connected = false, connecting = false) }
    }
    private fun updateMachine(id: String, transform: (MachineState) -> MachineState) {
        _state.update { it.copy(machines = it.machines + (id to transform(it.machines[id] ?: MachineState()))) }
    }
    private suspend fun refresh(id: String, client: CoreClient) {
        val list = client.call("terminal.list")
        val terminals = list.optJSONArray("terminals")?.objects()?.map(CoreTerminal::parse).orEmpty()
        val local = client.call("doc.get", JSONObject().put("name", "herdr-app"))
        val spaces = LayoutReader.spaces(local.optJSONObject("value")).toMutableList()
        for (machine in list.optJSONArray("machines")?.objects().orEmpty()) {
            if (machine.clean("state") != "connected") continue
            val name = machine.getString("name")
            runCatching { client.call("doc.get", JSONObject().put("name", "herdr-app").put("on", name)) }
                .onSuccess { spaces.addAll(LayoutReader.spaces(it.optJSONObject("value"), name)) }
        }
        updateMachine(id) { it.copy(terminals = terminals, spaces = spaces, error = null) }
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
        attachJob = viewModelScope.launch(Dispatchers.IO) {
            activeChannel?.let { (old, channel) -> runCatching { old.detach(channel) } }; activeChannel = null
            var opened: Int? = null
            var streamEnded = false
            try {
                val channel = client.attach(selected.address, cols, rows, { ch, bytes ->
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
            nativeModes = modes
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
    fun type(bytes: ByteArray) {
        val pair = activeChannel ?: return
        if (!keystrokes.trySend(pair to bytes).isSuccess) _state.update { it.copy(message = "Input queue is full. Wait for the connection to catch up.") }
    }
    // Native snapshots have local shell history. Only alternate-screen apps receive scroll keys.
    fun scrollNative(lines: Int) {
        if (!state.value.nativeAlternateScreen || lines == 0) return
        val modes = nativeModes
        if (modes.mouseTracking && modes.sgrMouse) {
            val button = if (lines < 0) 64 else 65
            repeat(kotlin.math.abs(lines).coerceAtMost(12)) { type("\u001b[<$button;${(cols / 2).coerceAtLeast(1)};${(rows / 2).coerceAtLeast(1)}M".toByteArray()) }
        } else {
            type((if (lines < 0) "\u001b[5~" else "\u001b[6~").toByteArray())
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
    override fun onCleared() { clients.values.forEach { it.close() }; clients.clear() }
}
