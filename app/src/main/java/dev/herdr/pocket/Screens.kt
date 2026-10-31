package dev.herdr.pocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable fun MachinesScreen(state: PocketState, onAdd: () -> Unit, onConnect: (HostProfile) -> Unit, onDisconnect: (String) -> Unit, onRemove: (String) -> Unit) {
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
                            Text(host.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp)); Text("${host.user}@${host.host}:${host.port}", fontSize = 10.sp, color = Muted)
                        }
                        Dot(if (machine.connected) Color(0xffa6bd91) else Muted)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(if (machine.connected) "Connected · hc ${machine.version} · ${machine.terminals.size} terminals" else if (machine.connecting) "Connecting…" else "Not connected", fontSize = 11.sp, color = Muted)
                    machine.error?.let { Spacer(Modifier.height(10.dp)); SelectionContainer { Text(it, color = Blocked, fontSize = 11.sp, lineHeight = 17.sp) } }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (machine.connected) OutlinedButton(onClick = { onDisconnect(host.id) }) { Text("Disconnect", fontSize = 12.sp) }
                        else Button(onClick = { onConnect(host) }, enabled = !machine.connecting) {
                            if (machine.connecting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text("Connect", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { remove = host }) { Icon(Icons.Outlined.DeleteOutline, "Remove machine", Modifier.size(19.dp), tint = Muted) }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add machine") } }
        item {
            Spacer(Modifier.height(10.dp)); SmallLabel("First connection")
            Spacer(Modifier.height(12.dp)); Text("Add this phone’s public key to ~/.ssh/authorized_keys on your machine, or connect with an SSH password. You can copy the key from Settings.", fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
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
        Spacer(Modifier.height(12.dp)); Text("Add the public key below to ~/.ssh/authorized_keys on each host. Your private key stays in this app’s private storage.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(20.dp))
        Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border)) {
            SelectionContainer { Text(state.publicKey.ifBlank { "Creating identity…" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp, color = Muted, modifier = Modifier.padding(16.dp)) }
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
        Text("Text display uses a simpler native view and keeps message and key controls. It also opens automatically if the color display is unavailable.", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(34.dp)); HorizontalDivider(color = Border); Spacer(Modifier.height(26.dp))
        SmallLabel("About"); Spacer(Modifier.height(15.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Brand(36); Spacer(Modifier.width(12.dp)); Column { Text("Herdr Pocket", fontWeight = FontWeight.SemiBold); Text("${BuildConfig.VERSION_NAME} · Android companion", color = Muted, fontSize = 11.sp) } }
        Spacer(Modifier.height(18.dp)); Text("A native companion for your existing herdr-core. Graphite colors, spaces and tabs follow the desktop’s design.", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(10.dp)); Text("Connections run while the app is open. Android may close them in the background; reconnect from Machines. Your remote programs keep running.", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp)); TextButton(onClick = vm::enterDemo, contentPadding = PaddingValues(0.dp)) { Text("Explore the interface with sample data", fontSize = 12.sp) }
    }
}

@Composable fun AddHostDialog(onDismiss: () -> Unit, onSave: (HostProfile) -> Unit) {
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }; var session by remember { mutableStateOf("default") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a machine") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to your existing herdr-core over SSH.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("My workstation") }, singleLine = true)
            OutlinedTextField(host, { host = it }, label = { Text("Hostname or IP") }, placeholder = { Text("workstation.tailnet.ts.net") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(user, { user = it }, label = { Text("SSH username") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(.4f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(session, { session = it }, label = { Text("Core session") }, singleLine = true, modifier = Modifier.weight(.6f))
            }
            error?.let { Text(it, fontSize = 11.sp, color = Blocked) }
        }
    }, confirmButton = { TextButton(onClick = {
        when {
            host.isBlank() || user.isBlank() -> error = "Enter a hostname and SSH username."
            host.any { it.isWhitespace() } || user.any { it.isWhitespace() } -> error = "Hostname and username cannot contain spaces."
            port.toIntOrNull() !in 1..65535 -> error = "Enter a port between 1 and 65535."
            session.isBlank() -> error = "Enter the core session name (usually default)."
            else -> onSave(HostProfile(UUID.randomUUID().toString(), name.trim().ifBlank { host.trim() }, host.trim(), user.trim(), port.toInt(), session.trim()))
        }
    }) { Text("Save machine") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun ConnectDialog(host: HostProfile, onDismiss: () -> Unit, onConnect: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect to ${host.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("The app tries this phone’s SSH key first. Enter a password if your host uses password authentication.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            OutlinedTextField(password, { password = it }, label = { Text("SSH password (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("Passwords are used for this connection and aren’t saved.", color = Muted, fontSize = 11.sp)
        }
    }, confirmButton = { TextButton(onClick = { onConnect(password.takeIf { it.isNotBlank() }); password = "" }) { Text("Connect") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
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
            OutlinedTextField(cwd, { cwd = it }, label = { Text("Working directory (optional)") }, singleLine = true)
            OutlinedTextField(command, { command = it }, label = { Text("Command (optional)") }, placeholder = { Text("claude, codex, or a shell command") }, maxLines = 3)
            Text("Leave the command empty to start the host’s default shell.", fontSize = 11.sp, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { onStart(host, cwd, command) }, enabled = host.isNotBlank()) { Text("Start terminal") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
