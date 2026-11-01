package ai.namastex.automagik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings Watch in background back after an update, from GitHub or Play, or a phone restart ended the process under it.
 * The machines being watched are kept in the preferences as they change, so a cold start needs no screen.
 */
object WatchResume {
    /** Ids of the machines connected, connecting or coming back while watching. */
    const val PREF = "watched_hosts"
    const val PREF_WATCH = "watch_background"

    /** The broadcasts that bring the watch back: an update replaced the app, or the phone restarted and was unlocked
     *  once. Not LOCKED_BOOT_COMPLETED: the keys and preferences are in storage that opens only after that unlock. */
    val ACTIONS = setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)

    /** What to keep for after a restart. Nothing with watching off or in the demo; a machine connected with a typed
     *  password isn't kept, because the password isn't. */
    fun watched(watch: Boolean, demo: Boolean, active: Set<String>, passwordHosts: Set<String>): Set<String> =
        if (!watch || demo) emptySet() else active - passwordHosts

    /** Whether the app was watching before it was replaced or the phone restarted, and still should. */
    fun wanted(watch: Boolean, watched: Set<String>?): Boolean = watch && !watched.isNullOrEmpty()

    fun wanted(context: Context): Boolean = context.getSharedPreferences("pocket", 0)
        .let { wanted(it.getBoolean(PREF_WATCH, true), it.getStringSet(PREF, null)) }

    /** Whether this broadcast brings the watch back. */
    fun resumes(action: String?, watch: Boolean, watched: Set<String>?): Boolean = action in ACTIONS && wanted(watch, watched)

    fun resumes(action: String?, context: Context): Boolean = action in ACTIONS && wanted(context)

    /** The saved machines to reconnect, in their saved order. A machine removed since isn't. */
    fun hosts(watch: Boolean, watched: Set<String>?, saved: List<String>): List<String> =
        if (!wanted(watch, watched)) emptyList() else saved.filter { it in watched.orEmpty() }
}

/**
 * Android starts the app here after replacing it, and after the phone restarts and is first unlocked. Both broadcasts
 * may start a foreground service from the background, so the watch comes back without opening the app. Kept apart from
 * the updater, so it runs whatever installed the update, in every distribution.
 */
class WatchResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!WatchResume.resumes(intent.action, context)) return
        (context.applicationContext as PocketApplication).vm.resumeWatching()
    }
}
