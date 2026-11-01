package dev.herdr.pocket

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android notifications for the desktop's alerts, while the person isn't in the app: one per agent, with Reply
 * (typed into the agent like the composer) and a tap that opens it. The app's own toast covers the foreground.
 */
object Notify {
    const val NEEDS_YOU = "needs_you"
    const val DONE = "done"
    const val EXTRA_HOST = "dev.herdr.pocket.host"
    const val EXTRA_ADDRESS = "dev.herdr.pocket.address"
    private const val EXTRA_TITLE = "dev.herdr.pocket.title"
    private const val KEY_REPLY = "reply"

    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply { description = "An agent asks something or waits for approval." },
            NotificationChannel(DONE, "Done", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "An agent finished a turn." }))
    }

    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun id(ref: TerminalRef) = "${ref.hostId}|${ref.address}".hashCode()

    /** [detail]: what it asks or said, and where it runs. [lockScreen]: Reply works without unlocking, and the content shows there. */
    /** [menu]: the options on the agent's screen, which become buttons (see [Prompt.choices]). */
    fun post(context: Context, alert: Alerts.Alert, detail: String, silent: Boolean, lockScreen: Boolean, menu: Prompt.Menu? = null) =
        post(context, alert.ref, alert.kind, alert.text, detail, silent, lockScreen, null,
            Prompt.choices(alert.terminal.kind, menu), menu?.options.orEmpty(), alert.terminal.message)

    /** [sent]: a reply's outcome, shown in place of [detail] without alerting again. */
    fun post(context: Context, ref: TerminalRef, kind: String, title: String, detail: String, silent: Boolean, lockScreen: Boolean, sent: String?,
             choices: List<Prompt.Choice> = emptyList(), options: List<String> = emptyList(), msg: String = "") {
        if (!allowed(context)) return
        // One button per option; each remembers the question and the whole menu, so a stale tap presses nothing.
        val picks = choices.map { c ->
            NotificationCompat.Action.Builder(R.drawable.ic_notify, c.label.take(40), PendingIntent.getBroadcast(context, id(ref) * 31 + c.index + 1,
                Intent(context, ChoiceReceiver::class.java).putExtra(EXTRA_HOST, ref.hostId).putExtra(EXTRA_ADDRESS, ref.address).putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_DETAIL, detail).putExtra(EXTRA_MSG, msg)
                    .putStringArrayListExtra(EXTRA_OPTIONS, ArrayList(options)).putExtra(EXTRA_INDEX, c.index).putExtra(EXTRA_LABEL, c.label),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setAuthenticationRequired(!lockScreen).build()
        }
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notify, "Reply", PendingIntent.getBroadcast(context, id(ref),
            Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_HOST, ref.hostId).putExtra(EXTRA_ADDRESS, ref.address).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_DETAIL, detail),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Message to the agent").build())
            .setAllowGeneratedReplies(false).setAuthenticationRequired(!lockScreen)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
        val text = sent ?: detail
        val n = NotificationCompat.Builder(context, if (kind == Alerts.BLOCKED) NEEDS_YOU else DONE)
            .setSmallIcon(R.drawable.ic_notify).setColor(0xff96b2d7.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (kind == Alerts.BLOCKED) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (kind == Alerts.BLOCKED) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(if (lockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open(context, ref)).setAutoCancel(true)
            .setSilent(silent || sent != null).setOnlyAlertOnce(sent != null)
            .apply { picks.forEach(::addAction); if (picks.size < 3) addAction(reply) }.build()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { NotificationManagerCompat.from(context).notify(id(ref), n) }
    }

    fun cancel(context: Context, ref: TerminalRef) = NotificationManagerCompat.from(context).cancel(id(ref))

    private fun open(context: Context, ref: TerminalRef) = PendingIntent.getActivity(context, id(ref),
        Intent(context, MainActivity::class.java).putExtra(EXTRA_HOST, ref.hostId).putExtra(EXTRA_ADDRESS, ref.address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The agent a notification's tap opens, if [intent] came from one. */
    fun opened(intent: Intent?): TerminalRef? {
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return null
        return TerminalRef(host, intent.getStringExtra(EXTRA_ADDRESS) ?: return null)
    }

    private const val EXTRA_KIND = "dev.herdr.pocket.kind"
    private const val EXTRA_MSG = "dev.herdr.pocket.msg"
    private const val EXTRA_OPTIONS = "dev.herdr.pocket.options"
    private const val EXTRA_INDEX = "dev.herdr.pocket.index"
    private const val EXTRA_LABEL = "dev.herdr.pocket.label"

    /** An option's button: picks it in the agent, after checking the same question and menu are still there. */
    class ChoiceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ref = opened(intent) ?: return
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            val kind = intent.getStringExtra(EXTRA_KIND) ?: Alerts.BLOCKED
            val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
            fun show(outcome: String) = post(context, ref, kind, title, detail, silent = true,
                lockScreen = PocketViewModel.current?.state?.value?.lockAnswers ?: true, sent = outcome)
            val vm = PocketViewModel.current ?: run { show("Not sent: open Herdr Pocket and connect first."); return }
            val pending = goAsync()
            scope.launch {
                try {
                    show(vm.choose(ref, intent.getStringExtra(EXTRA_MSG).orEmpty(), intent.getStringArrayListExtra(EXTRA_OPTIONS).orEmpty(),
                        intent.getIntExtra(EXTRA_INDEX, -1), intent.getStringExtra(EXTRA_LABEL).orEmpty()))
                } finally { pending.finish() }
            }
        }
    }
    private const val EXTRA_DETAIL = "dev.herdr.pocket.detail"

    /** Reply: sends the typed text through the open connection and shows the outcome in the same notification. */
    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ref = opened(intent) ?: return
            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            val kind = intent.getStringExtra(EXTRA_KIND) ?: Alerts.BLOCKED
            val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
            fun show(outcome: String) = post(context, ref, kind, title, detail, silent = true,
                lockScreen = PocketViewModel.current?.state?.value?.lockAnswers ?: true, sent = outcome)
            if (text.isBlank()) return
            val pending = goAsync()
            val vm = PocketViewModel.current
            if (vm == null) { show("Not sent: open Herdr Pocket and connect first."); pending.finish(); return }
            scope.launch {
                try { show(vm.reply(ref, text)) } finally { pending.finish() }
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
