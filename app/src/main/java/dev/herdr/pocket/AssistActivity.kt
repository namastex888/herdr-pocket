package dev.herdr.pocket

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.outlined.Send
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Who the assistant offers to talk to: agents that need the person, then finished ones, most recent first. */
object Assist {
    fun targets(sections: List<Cockpit.Section>, max: Int = 5): List<Cockpit.Entry> =
        sections.filter { it.rank == Cockpit.BLOCKED || it.rank == Cockpit.DONE }.sortedByDescending { it.rank }.flatMap { it.entries }.take(max)
}

/**
 * The phone's assistant (hold the side button): a panel over whatever is on screen, the lock screen included, with
 * the agents that need the person and a message box with voice input, sent like the composer.
 */
class AssistActivity : ComponentActivity() {
    private val vm get() = (application as PocketApplication).vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val keyguard = getSystemService(KeyguardManager::class.java)
        // Off in Settings: unlock first, as the notifications' Reply asks.
        if (keyguard.isKeyguardLocked && !vm.state.value.lockAnswers) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Chrome, background = Canvas, onBackground = Ink,
                surface = Surface, onSurface = Ink, surfaceVariant = Raised, onSurfaceVariant = Muted, outline = Border)) {
                AssistPanel(vm, onClose = ::finish, onOpen = { ref ->
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { ref?.let { putExtra(Notify.EXTRA_HOST, it.hostId).putExtra(Notify.EXTRA_ADDRESS, it.address) } })
                    finish()
                })
            }
        }
    }
}

@Composable private fun AssistPanel(vm: PocketViewModel, onClose: () -> Unit, onOpen: (TerminalRef?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val all = state.machines.flatMap { (host, machine) -> machine.terminals.filter { it.live }.map { TerminalRef(host, it.address) to it } }
    val paned = state.machines.flatMap { (h, m) -> m.spaces.flatMap { s -> s.addresses.map { TerminalRef(h, it) } } }.toSet()
    val targets = Assist.targets(Cockpit.sections(all, { it in paned }) { r, t -> Cockpit.seenKey(r, t) in state.seen })
    var target by remember { mutableStateOf<TerminalRef?>(null) }
    val chosen = targets.find { it.ref == target } ?: targets.firstOrNull()
    var text by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connected = state.machines.values.any { it.connected }
    // Opened from the side button the app may not be running: it is in the foreground now, so it can connect and watch.
    LifecycleStartEffect(Unit) { vm.setForeground(true); onStopOrDispose { vm.setForeground(false) } }
    Box(Modifier.fillMaxSize().background(Chrome.copy(alpha = .55f)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Canvas).clickable(remember { MutableInteractionSource() }, null) {}
            .navigationBarsPadding().imePadding().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Brand(26); Spacer(Modifier.width(10.dp))
                Text("Herdr", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpen(chosen?.ref) }) { Text("Open app") }
            }
            Spacer(Modifier.height(10.dp))
            when {
                !connected -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val connecting = state.machines.values.any { it.connecting }
                    Text(if (connecting) "Connecting…" else "Not connected.", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!connecting && state.hosts.isNotEmpty()) TextButton(onClick = { state.hosts.forEach { vm.connect(it) } }) { Text("Connect") }
                }
                targets.isEmpty() -> Text("All clear: no agent needs you.", color = Muted, fontSize = 13.sp)
                else -> targets.forEach { e ->
                    val on = e.ref == chosen?.ref
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Raised else Surface)
                        .border(1.dp, if (on) Accent.copy(alpha = .5f) else Border, RoundedCornerShape(10.dp)).clickable { target = e.ref }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Dot(stateColor(if (e.rank == Cockpit.BLOCKED) "blocked" else "done"), 7); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.terminal.title.ifBlank { e.terminal.agent }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val said = e.terminal.message.ifBlank { statusLabel(if (e.rank == Cockpit.BLOCKED) "blocked" else "done", e.terminal.kind) }
                            Text(said, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            outcome?.let { Spacer(Modifier.height(8.dp)); Text(it, color = if (it.startsWith("You:")) Accent else Blocked, fontSize = 12.sp) }
            if (connected && chosen != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message ${chosen.terminal.title.ifBlank { chosen.terminal.agent }}") }, maxLines = 4)
                    Spacer(Modifier.width(6.dp))
                    Dictate { heard -> text = if (text.isBlank()) heard else "$text $heard" }
                    IconButton(enabled = text.isNotBlank(), onClick = {
                        val send = text.trim(); val ref = chosen.ref
                        scope.launch {
                            val result = vm.reply(ref, send)
                            outcome = result
                            if (result.startsWith("You:")) { text = ""; delay(1_200); onClose() }
                        }
                    }) { Icon(Icons.Outlined.Send, "Send", tint = Accent) }
                }
            }
        }
    }
}

/** The mic: Android's speech recognizer fills the message box. Hidden without the microphone permission (asked in Settings). */
@Composable private fun Dictate(onHeard: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
        !SpeechRecognizer.isRecognitionAvailable(context)) return
    var listening by remember { mutableStateOf(false) }
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }
    IconButton(onClick = {
        if (listening) { recognizer.stopListening(); return@IconButton }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) { listening = false; results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onHeard) }
            override fun onError(error: Int) { listening = false }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        listening = true
    }) { Icon(Icons.Outlined.Mic, if (listening) "Stop listening" else "Speak", tint = if (listening) Blocked else Accent) }
}
