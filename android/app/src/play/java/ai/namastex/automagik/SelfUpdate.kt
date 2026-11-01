package ai.namastex.automagik

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable

/*
 * The self-update hooks main code calls, play flavor: Google Play updates the app, so each one does nothing. There is
 * no Updater in this flavor, no update check and no update UI; src/play/AndroidManifest.xml removes the installer
 * permissions (docs/rfcs/0006-distribution-flavors.md). Keep the declarations in step with src/github.
 */

@Suppress("UNUSED_PARAMETER")
object SelfUpdate {
    fun init(app: Application) {}
    fun onLaunch(foreground: () -> Boolean, terminalOpen: () -> Boolean, watching: () -> Boolean) {}
    fun foreground(shown: Boolean) {}
    fun touched() {}
    fun isInstallIntent(intent: Intent?) = false
    fun installFromNotification(activity: Activity) {}
}

@Composable fun SelfUpdateEffects() {}

@Composable fun selfUpdateProgress(): Float? = null

/** Settings → About shows no update controls: Google Play installs updates. */
@Composable fun SelfUpdateRow() {}
