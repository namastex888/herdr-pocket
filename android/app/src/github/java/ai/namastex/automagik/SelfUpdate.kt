package ai.namastex.automagik

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * The self-update hooks main code calls, github flavor: they drive Updater, which updates the app from the GitHub
 * releases. src/play has the same declarations as no-ops, and no Updater (docs/rfcs/0006-distribution-flavors.md).
 * A new hook goes in both files.
 */

object SelfUpdate {
    /** Once per process, from [PocketApplication]: say if the last update landed, then check periodically. */
    fun init(app: Application) = Updater.init(app)

    /** The view model started: tells a waiting update when installing would interrupt someone. */
    fun onLaunch(foreground: () -> Boolean, terminalOpen: () -> Boolean, watching: () -> Boolean) {
        Updater.usage = { Updater.Usage(foreground(), terminalOpen(), watching()) }
    }

    /** The app came on screen (a check, debounced) or left it (a waiting update may install). */
    fun foreground(shown: Boolean) = if (shown) Updater.opened() else Updater.left()

    /** The person touched the screen: a waiting update doesn't install under them. */
    fun touched() = Updater.touched()

    /** Whether [intent] is the "ready · Install" notification's tap. */
    fun isInstallIntent(intent: Intent?) = intent?.action == Updater.ACTION_INSTALL

    /** That tap: run Android's confirm flow for the waiting update. */
    fun installFromNotification(activity: Activity) = Updater.installFromNotification(activity)
}

/** In the app's scaffold: open Android's confirm prompt for an install the person started, continue after the permission screen. */
@Composable fun SelfUpdateEffects() {
    val update by Updater.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LaunchedEffect(update) {
        when (val u = update) {
            is UpdateState.Confirm -> { runCatching { activity?.startActivity(u.intent) }; Updater.confirming(u.release) }
            is UpdateState.Available -> if (Updater.takeStartWhenFound()) activity?.let(Updater::start)
            else -> {}
        }
    }
    LifecycleResumeEffect(Unit) { activity?.let(Updater::resume); onPauseOrDispose { } }
}

/** The top bar's progress line while an update the person started downloads, from 0 to 1; null otherwise. */
@Composable fun selfUpdateProgress(): Float? {
    val update by Updater.state.collectAsStateWithLifecycle()
    return (update as? UpdateState.Downloading)?.takeIf { it.manual }?.progress
}

/** Settings → About: the update controls. */
@Composable fun SelfUpdateRow() { Spacer(Modifier.height(16.dp)); UpdateRow() }

/** Settings → About: check for a newer build, install it, and whether updates install on their own. */
@Composable fun UpdateRow() {
    val update by Updater.state.collectAsStateWithLifecycle()
    val auto by Updater.auto.collectAsStateWithLifecycle()
    val updated by Updater.updated.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        updated?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        when (val u = update) {
            is UpdateState.Available, is UpdateState.NeedsPermission, is UpdateState.Waiting -> {
                val release = when (u) { is UpdateState.Available -> u.release; is UpdateState.NeedsPermission -> u.release; else -> (u as UpdateState.Waiting).release }
                Button(onClick = { activity?.let(Updater::start) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(if (u is UpdateState.Waiting) "Install ${release.label} now" else "Update to ${release.label}") }
                if (u is UpdateState.Waiting) Text("Downloaded. It installs on its own when you leave Automagik.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                if (release.notes.isNotBlank()) Text(release.notes, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (u is UpdateState.NeedsPermission) Text("Allow Automagik to install apps, then come back here. Android asks this once.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
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
        AlertRow("Install updates automatically", if (Build.VERSION.SDK_INT >= UpdatePolicy.SILENT_SDK)
            "New versions download on Wi-Fi and install while you’re away from the app; a notification says so after. Off, a notification asks first."
            else "Android ${Build.VERSION.RELEASE} asks before each update: a notification says when one is ready.", auto) { Updater.setAuto(it) }
    }
}
