package dev.herdr.pocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

/** Machines a host's core reaches: live from the connection, or remembered from the last one. */
fun PocketState.linkedFor(host: HostProfile): List<LinkedMachine> =
    machines[host.id]?.linked?.takeIf { it.isNotEmpty() } ?: host.linked.map { LinkedMachine(it, "") }

@Composable fun LinkedMachineRows(linked: List<LinkedMachine>, via: String) {
    if (linked.isEmpty()) return
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallLabel("Through $via")
        linked.forEach { m ->
            Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (m.connected) Color(0xffa6bd91) else Muted); Spacer(Modifier.width(10.dp))
                Text(m.name, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when { m.state.isBlank() -> "Connect to see status"; m.connected -> if (m.terminals == 1) "1 terminal" else "${m.terminals} terminals"; else -> m.state.replaceFirstChar(Char::uppercase) },
                    fontSize = 12.sp, color = Muted)
            }
        }
    }
}

@Composable fun NearbySection(nearby: List<NearbyHost>, onPick: (NearbyHost) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallLabel("On this network")
        if (nearby.isEmpty()) Text("Looking for computers with SSH turned on…", fontSize = 12.sp, color = Muted)
        nearby.forEach { n ->
            Surface(shape = RoundedCornerShape(14.dp), color = Surface, border = BorderStroke(1.dp, Border),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(n) }) {
                Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Wifi, null, Modifier.size(20.dp), tint = Muted); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (n.port == 22) n.host else "${n.host}:${n.port}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Add", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

fun NearbyHost.prefill() = HostProfile("", name, host, "", port)

@Composable fun MachinesScreen(state: PocketState, nearby: List<NearbyHost>, onAdd: () -> Unit, onPair: () -> Unit, onAddNearby: (NearbyHost) -> Unit, onConnect: (HostProfile) -> Unit, onEdit: (HostProfile) -> Unit, onDisconnect: (String) -> Unit, onRemove: (String) -> Unit) {
    var remove by remember { mutableStateOf<HostProfile?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Your work lives here.", fontSize = 26.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp)); Text("Connect to the machine running herdr-core. Use its local address or Tailscale hostname.", fontSize = 13.sp, lineHeight = 21.sp, color = Muted); Spacer(Modifier.height(12.dp)) }
        items(state.hosts, key = { it.id }) { host ->
            val machine = state.machines[host.id] ?: MachineState()
            Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), color = Surface) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Computer, null, Modifier.size(22.dp), tint = Accent); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp)); Text(if (host.paired) host.address else "${host.user}@${host.host}:${host.port}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Dot(if (machine.connected) Color(0xffa6bd91) else Muted)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(if (machine.connected) "Connected · hc ${machine.version} · ${machine.terminals.size} terminals" else if (machine.connecting) "Connecting…" else "Not connected", fontSize = 12.sp, color = Muted)
                    machine.error?.let { Spacer(Modifier.height(10.dp)); SelectionContainer { Text(it, color = Blocked, fontSize = 12.sp, lineHeight = 18.sp) } }
                    LinkedMachineRows(state.linkedFor(host), host.name)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (machine.connected) OutlinedButton(onClick = { onDisconnect(host.id) }) { Text("Disconnect", fontSize = 13.sp) }
                        else Button(onClick = { onConnect(host) }, enabled = !machine.connecting) {
                            if (machine.connecting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text("Connect", fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onEdit(host) }) { Icon(Icons.Outlined.Edit, "Edit ${host.name}", Modifier.size(19.dp), tint = Muted) }
                        IconButton(onClick = { remove = host }) { Icon(Icons.Outlined.DeleteOutline, "Remove ${host.name}", Modifier.size(19.dp), tint = Muted) }
                    }
                }
            }
        }
        item { Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pair with Herdr desktop") } }
        item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add an SSH machine") } }
        item { Spacer(Modifier.height(6.dp)); NearbySection(nearby, onAddNearby) }
        item {
            Spacer(Modifier.height(10.dp)); SmallLabel("First connection")
            Spacer(Modifier.height(12.dp)); Text("Add this phone’s public key to ~/.ssh/authorized_keys on your machine. You can copy the key from Settings. If the host doesn’t accept the key, you’ll be asked for its SSH password.", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
        }
    }
    remove?.let { host -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${host.name}?") },
        text = { Text("This removes the saved connection from this phone. Its remote terminals keep running.") },
        confirmButton = { TextButton(onClick = { onRemove(host.id); remove = null }) { Text("Remove", color = Blocked) } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable fun SettingsScreen(state: PocketState, vm: PocketViewModel) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        SmallLabel("Connection identity"); Spacer(Modifier.height(14.dp))
        Text("This phone’s SSH key", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp)); Text("Add the public key below to ~/.ssh/authorized_keys on each host. Your private key stays in this app’s private storage.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(20.dp))
        Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border)) {
            SelectionContainer { Text(state.publicKey.ifBlank { "Creating identity…" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = Muted, modifier = Modifier.padding(16.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.publicKey)); copied = true }, enabled = state.publicKey.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(if (copied) "Public key copied" else "Copy public key")
        }
        Spacer(Modifier.height(28.dp)); SmallLabel("Terminal display"); Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !state.nativeDisplay, onClick = { vm.preferNativeDisplay(false) }, label = { Text("Full color") })
            FilterChip(selected = state.nativeDisplay, onClick = { vm.preferNativeDisplay(true) }, label = { Text("Native text") })
        }
        Spacer(Modifier.height(8.dp))
        Text("Text display uses a simpler native view and keeps message and key controls. It also opens automatically if the color display is unavailable.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(28.dp)); SmallLabel("Terminal text size"); Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = { vm.setFontSize(state.fontSize - 1) }, enabled = state.fontSize > FONT_SIZES.first) { Icon(Icons.Outlined.Remove, "Smaller terminal text") }
            Text("${state.fontSize}", Modifier.width(52.dp), textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
            OutlinedIconButton(onClick = { vm.setFontSize(state.fontSize + 1) }, enabled = state.fontSize < FONT_SIZES.last) { Icon(Icons.Outlined.Add, "Larger terminal text") }
            Spacer(Modifier.width(16.dp))
            Text("~/work $", fontFamily = FontFamily.Monospace, fontSize = state.fontSize.sp, color = Muted, maxLines = 1, modifier = Modifier.weight(1f))
            if (state.fontSize != DEFAULT_FONT_SIZE) TextButton(onClick = { vm.setFontSize(DEFAULT_FONT_SIZE) }) { Text("Reset") }
        }
        Spacer(Modifier.height(8.dp))
        Text("You can also pinch a terminal with two fingers to change its text size.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(34.dp)); HorizontalDivider(color = Border); Spacer(Modifier.height(26.dp))
        SmallLabel("About"); Spacer(Modifier.height(15.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Brand(36); Spacer(Modifier.width(12.dp)); Column { Text("Herdr Pocket", fontWeight = FontWeight.SemiBold); Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android companion", color = Muted, fontSize = 12.sp) } }
        Spacer(Modifier.height(16.dp)); UpdateRow()
        Spacer(Modifier.height(18.dp)); Text("A native companion for your existing herdr-core. Graphite colors, spaces and tabs follow the desktop’s design.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(10.dp)); Text("Connections run while the app is open. Android may close them in the background; pull down on the agent list or tap Reconnect. Your remote programs keep running.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp)); TextButton(onClick = vm::enterDemo, contentPadding = PaddingValues(0.dp)) { Text("Explore the interface with sample data", fontSize = 13.sp) }
    }
}

/** Settings → About: check for a newer build, and install it. */
@Composable fun UpdateRow() {
    val update by Updater.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val u = update) {
            is UpdateState.Available, is UpdateState.NeedsPermission -> {
                val release = (u as? UpdateState.Available)?.release ?: (u as UpdateState.NeedsPermission).release
                Button(onClick = { activity?.let(Updater::start) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Update to ${release.label}") }
                if (release.notes.isNotBlank()) Text(release.notes, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (u is UpdateState.NeedsPermission) Text("Allow Herdr Pocket to install apps, then come back here. Android asks this once.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${u.release.label}…", fontSize = 13.sp)
                LinearProgressIndicator(progress = { u.progress }, Modifier.fillMaxWidth(), color = Accent, trackColor = Border)
            }
            is UpdateState.Installing, is UpdateState.Confirm -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Installing…", fontSize = 13.sp)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { Updater.check() }, enabled = u != UpdateState.Checking) { Text(if (u == UpdateState.Checking) "Checking…" else "Check for updates") }
                Spacer(Modifier.width(12.dp))
                when (u) {
                    UpdateState.Current -> Text("You have the latest build.", color = Muted, fontSize = 12.sp)
                    is UpdateState.Failed -> Text(u.message, color = Blocked, fontSize = 12.sp, lineHeight = 17.sp)
                    else -> {}
                }
            }
        }
        if ((update as? UpdateState.Failed)?.release != null) TextButton(onClick = { activity?.let(Updater::start) }, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
    }
}

/** Hostnames, users and commands: no auto-capitalization or autocorrect from the phone keyboard. */
fun plainKeys(type: KeyboardType = KeyboardType.Text, ime: ImeAction = ImeAction.Next) =
    KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = type, imeAction = ime)

/** [initial] edits a saved machine; [prefill] only seeds the fields of a new one. */
@Composable fun HostDialog(initial: HostProfile?, onDismiss: () -> Unit, onSave: (HostProfile) -> Unit, prefill: HostProfile? = null) {
    if (initial?.paired == true) { PairedHostDialog(initial, onDismiss, onSave); return }
    val seed = initial ?: prefill
    var name by remember { mutableStateOf(seed?.name.orEmpty()) }; var host by remember { mutableStateOf(seed?.host.orEmpty()) }
    var user by remember { mutableStateOf(seed?.user.orEmpty()) }; var port by remember { mutableStateOf((seed?.port ?: 22).toString()) }
    var session by remember { mutableStateOf(seed?.session ?: "default") }; var error by remember { mutableStateOf<String?>(null) }
    val userFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initial == null && prefill != null) runCatching { userFocus.requestFocus() } }
    // A different address may be a different machine, so its host key must be verified again.
    val keepsKey = initial != null && initial.host == host.trim() && initial.port == port.toIntOrNull()
    fun save() {
        when {
            host.isBlank() || user.isBlank() -> error = "Enter a hostname and SSH username."
            host.any { it.isWhitespace() } || user.any { it.isWhitespace() } -> error = "Hostname and username cannot contain spaces."
            port.toIntOrNull() !in 1..65535 -> error = "Enter a port between 1 and 65535."
            session.isBlank() -> error = "Enter the core session name (usually default)."
            else -> onSave(HostProfile(initial?.id ?: UUID.randomUUID().toString(), name.trim().ifBlank { host.trim() }, host.trim(), user.trim(),
                port.toInt(), session.trim(), if (keepsKey) initial?.fingerprint.orEmpty() else "", initial?.linked.orEmpty()))
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Add a machine" else "Edit ${initial.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initial == null) Text("Connect to your existing herdr-core over SSH.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("My workstation") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
            OutlinedTextField(host, { host = it; error = null }, label = { Text("Hostname or IP") }, placeholder = { Text("workstation.tailnet.ts.net") }, singleLine = true, keyboardOptions = plainKeys(KeyboardType.Uri))
            OutlinedTextField(user, { user = it; error = null }, label = { Text("SSH username") }, singleLine = true, keyboardOptions = plainKeys(), modifier = Modifier.focusRequester(userFocus))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); error = null }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(.4f), keyboardOptions = plainKeys(KeyboardType.Number))
                OutlinedTextField(session, { session = it; error = null }, label = { Text("Core session") }, singleLine = true, modifier = Modifier.weight(.6f),
                    keyboardOptions = plainKeys(ime = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
            }
            if (initial != null && initial.fingerprint.isNotBlank() && !keepsKey) Text("You’ll verify the host key again after changing its address.", fontSize = 12.sp, color = Muted)
            error?.let { Text(it, fontSize = 12.sp, color = Blocked) }
        }
    }, confirmButton = { TextButton(onClick = { save() }) { Text(if (initial == null) "Save and connect" else "Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun ConnectDialog(host: HostProfile, rejected: Boolean, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun submit() { if (password.isNotEmpty()) { val entered = password; password = ""; onConnect(entered) } }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.Key, null, tint = Accent) }, title = { Text("Password for ${host.name}") }, text = {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (rejected) "That password wasn’t accepted. Check it and try again."
                else "${host.user}@${host.host} didn’t accept this phone’s key. Enter the SSH password, or add the key from Settings to ~/.ssh/authorized_keys.",
                color = if (rejected) Blocked else Muted, fontSize = 13.sp, lineHeight = 20.sp)
            OutlinedTextField(password, { password = it }, label = { Text("SSH password") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide password" else "Show password") } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }))
            Text("Used for this connection only and never saved.", color = Muted, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { submit() }, enabled = password.isNotEmpty()) { Text("Connect") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun NewTerminalDialog(state: PocketState, onDismiss: () -> Unit, onStart: (String, String, String) -> Unit) {
    val available = state.hosts.filter { state.machines[it.id]?.connected == true }
    var host by remember { mutableStateOf(state.space?.first ?: available.firstOrNull()?.id.orEmpty()) }
    var cwd by remember { mutableStateOf(state.space?.let { (h, s) -> state.machines[h]?.spaces?.find { it.id == s }?.folder }.orEmpty()) }
    var command by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New terminal") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallLabel("Machine")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                available.forEach { h -> FilterChip(selected = host == h.id, onClick = { host = h.id }, label = { Text(h.name) }) }
            }
            OutlinedTextField(cwd, { cwd = it }, label = { Text("Working directory (optional)") }, singleLine = true, keyboardOptions = plainKeys(KeyboardType.Uri))
            OutlinedTextField(command, { command = it }, label = { Text("Command (optional)") }, placeholder = { Text("claude, codex, or a shell command") }, maxLines = 3,
                keyboardOptions = plainKeys(ime = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { if (host.isNotBlank()) onStart(host, cwd, command) }))
            Text("Leave the command empty to start the host’s default shell.", fontSize = 12.sp, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { onStart(host, cwd, command) }, enabled = host.isNotBlank()) { Text("Start terminal") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
