package ai.namastex.automagik

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The newest published build, from the `latest.json` CI attaches to each GitHub release. */
data class Release(val versionCode: Long, val versionName: String, val url: String, val sha256: String, val notes: String) {
    val label get() = "$versionName ($versionCode)"
    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        fun parse(text: String): Release? = runCatching {
            val json = JSONObject(text)
            val code = json.getLong("versionCode")
            val url = json.getString("url")
            val sha = json.getString("sha256").lowercase()
            require(code > 0 && url.startsWith("https://") && SHA256.matches(sha))
            Release(code, json.optString("versionName").filterNot { it.isISOControl() }.take(40).ifBlank { "build" }, url, sha,
                json.optString("notes").take(600))
        }.getOrNull()
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: Release) : UpdateState
    /** Android asks once whether Herdr Pocket may install apps; the update continues when the person comes back. */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Android's "Update this app?" prompt, for the screen to open. */
    data class Confirm(val release: Release, val intent: Intent) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/** Updates the installed app from the GitHub releases: download, check the hash, hand the APK to Android's installer. */
object Updater {
    private const val MAX_APK = 200L shl 20
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    /** Whether [release] is newer than the running build. */
    fun isNewer(release: Release, installed: Long = BuildConfig.VERSION_CODE.toLong()) = release.versionCode > installed

    /** Looks for a newer build. [quiet] keeps a failed check from showing as an error (the launch check). */
    fun check(quiet: Boolean = false) {
        if (job?.isActive == true || _state.value.let { it is UpdateState.Downloading || it is UpdateState.Installing || it is UpdateState.Confirm }) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = runCatching { fetch(BuildConfig.UPDATE_URL) }.fold(
                { release -> if (release != null && isNewer(release)) UpdateState.Available(release) else UpdateState.Current },
                { e -> if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn’t check for updates: ${e.message ?: "no connection"}", null) })
        }
    }

    /** Starts installing the release on offer; first sends the person to allow installs if Android hasn't yet. */
    fun start(activity: Activity) {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())) }
            return
        }
        val context = activity.applicationContext
        job = scope.launch {
            runCatching { install(context, release, download(context, release)) }
                .onFailure { e -> _state.value = UpdateState.Failed("Update failed: ${e.message ?: e.javaClass.simpleName}", release) }
        }
    }

    /** Back from Android's install permission screen: continue if it was granted. */
    fun resume(activity: Activity) {
        if (_state.value is UpdateState.NeedsPermission && activity.packageManager.canRequestPackageInstalls()) start(activity)
    }

    /** The screen opened Android's prompt; the receiver reports what the person chose. */
    fun confirming(release: Release) { _state.value = UpdateState.Installing(release) }

    private fun fetch(url: String): Release? {
        val text = open(url).run { try { inputStream.bufferedReader().use { it.readText().take(8192) } } finally { disconnect() } }
        return Release.parse(text)
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "HerdrPocket/${BuildConfig.VERSION_NAME}")
        if (responseCode !in 200..299) { disconnect(); error("the server answered $responseCode") }
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "automagik-${release.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        _state.value = UpdateState.Downloading(release, 0f)
        val connection = open(release.url)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            check((total ?: 0) <= MAX_APK) { "the download is too large" }
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 shl 10); var read = 0L; var shown = 0f
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    read += n; check(read <= MAX_APK) { "the download is too large" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    val progress = total?.let { read.toFloat() / it } ?: 0f
                    if (progress - shown >= .01f) { shown = progress; _state.value = UpdateState.Downloading(release, progress) }
                }
            } }
        } finally { connection.disconnect() }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != release.sha256) { file.delete(); error("the download doesn’t match the published build") }
        return file
    }

    private fun install(context: Context, release: Release, apk: File) {
        _state.value = UpdateState.Installing(release)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName); setSize(apk.length())
            // After the first update, Herdr Pocket installed itself, and Android 12+ can update it without asking again.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("automagik.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val callback = Intent(context, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
        }
        apk.delete()
    }

    internal fun reported(status: Int, message: String?, confirm: Intent?) {
        val release = when (val s = _state.value) {
            is UpdateState.Installing -> s.release; is UpdateState.Confirm -> s.release; else -> null
        } ?: return
        _state.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { UpdateState.Confirm(release, it) } ?: UpdateState.Failed("Android didn’t ask to install the update.", release)
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Current
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(release)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateState.Failed(
                "This copy of Automagik was signed with a different key. Uninstall it once, then install the release from GitHub.", null)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Not enough storage to install the update.", release)
            else -> UpdateState.Failed("Android didn’t install the update${message?.let { ": $it" }.orEmpty()}.", release)
        }
    }
}

/** Android's installer reports here: asks the person to confirm, or says how it went. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.reported(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
    }
}
