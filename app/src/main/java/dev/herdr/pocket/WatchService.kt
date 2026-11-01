package dev.herdr.pocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the connections open while the person is away from the app, so alerts still reach them as notifications.
 * Android requires it to show a notification for as long as it runs; Stop there disconnects and ends it.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as PocketApplication).vm.stopWatching()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(this, text), type) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "watching"
        private const val ID = 1
        private const val ACTION_STOP = "dev.herdr.pocket.STOP_WATCHING"
        @Volatile private var running = false
        @Volatile private var text = ""

        /** What the ongoing notification says. */
        fun summary(machines: Int, needYou: Int) =
            (if (machines == 1) "Watching 1 machine" else "Watching $machines machines") + if (needYou > 0) " · $needYou need you" else ""

        /** Starts it (only from the foreground, as Android requires) or updates its text. */
        fun show(context: Context, summary: String, foreground: Boolean) {
            text = summary
            if (running) {
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    runCatching { NotificationManagerCompat.from(context).notify(ID, notification(context, summary)) }
                return
            }
            if (!foreground) return
            running = runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }.isSuccess
        }

        // Lint reads stopService(new Intent) as removing a listener by a fresh instance; services stop by component.
        @android.annotation.SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, WatchService::class.java))
        }

        private fun notification(context: Context, summary: String): android.app.Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Watching machines", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Herdr Pocket keeps your machines connected in the background."; setShowBadge(false) })
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(context, 1, Intent(context, WatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify).setColor(0xff96b2d7.toInt())
                .setContentTitle("Herdr Pocket").setContentText(summary.ifBlank { "Watching your machines" })
                .setOngoing(true).setSilent(true).setShowWhen(false).setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_notify, "Stop", stop).build()
        }
    }
}
