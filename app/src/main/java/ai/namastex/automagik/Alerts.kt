package ai.namastex.automagik

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Herdr desktop's alerts (Settings > Alerts, settings.json `sounds`): when an agent needs the person, or finishes while
 * they aren't looking at it, it plays a sound and, unless they're looking, shows a toast that opens it.
 */
object Alerts {
    const val BLOCKED = "blocked"
    const val DONE = "done"

    data class Alert(val ref: TerminalRef, val terminal: CoreTerminal, val kind: String) {
        val text get() = terminal.title.ifBlank { terminal.agent }.let { if (kind == BLOCKED) "$it needs you" else "$it finished" }
    }

    /**
     * The agents that turned blocked or finished a turn between [old] and [new], one machine's terminal lists. A
     * sub-agent with no pane of its own ([paned]) never alerts Done: its parent's done is the news. No [old] list (the
     * first load or a reconnect) means nothing changed.
     */
    fun changes(old: List<CoreTerminal>?, new: List<CoreTerminal>, hostId: String, paned: (TerminalRef) -> Boolean): List<Alert> {
        if (old == null) return emptyList()
        val before = old.associateBy { it.address }
        val live = new.filter { it.live }.associateBy { it.address }
        return new.filter { it.live && it.status.isNotBlank() }.mapNotNull { t ->
            val ref = TerminalRef(hostId, t.address)
            val kid = t.parent.isNotBlank() && t.parent in live && !paned(ref)
            val word = if (kid) Cockpit.kidStatus(t, false) else Cockpit.status(t, false)
            val was = before[t.address]?.let { if (kid) Cockpit.kidStatus(it, false) else Cockpit.status(it, false) }
            when {
                word == BLOCKED && was != BLOCKED -> Alert(ref, t, BLOCKED)
                // A done that ended a new turn: catches two finishes between reads.
                word == DONE && (was != DONE || before[t.address]?.turn != t.turn) -> Alert(ref, t, DONE)
                else -> null
            }
        }
    }

    /**
     * The agents whose alert is over between [old] and [new]: they no longer need the person and aren't sitting on a
     * finished turn (they answered elsewhere, started working again, or ended), so their notification goes.
     */
    fun resolved(old: List<CoreTerminal>?, new: List<CoreTerminal>, hostId: String, paned: (TerminalRef) -> Boolean): List<TerminalRef> {
        if (old == null) return emptyList()
        val words = words(new, hostId, paned)
        return words(old, hostId, paned).filter { (address, word) -> word in ALERTING && words[address] !in ALERTING }.keys
            .map { TerminalRef(hostId, it) }
    }
    private val ALERTING = setOf(BLOCKED, DONE)
    /** Each live agent's word, with the sub-agent rule. */
    private fun words(list: List<CoreTerminal>, hostId: String, paned: (TerminalRef) -> Boolean): Map<String, String> {
        val live = list.filter { it.live }.associateBy { it.address }
        return live.values.filter { it.status.isNotBlank() }.associate { t ->
            val kid = t.parent.isNotBlank() && t.parent in live && !paned(TerminalRef(hostId, t.address))
            t.address to if (kid) Cockpit.kidStatus(t, false) else Cockpit.status(t, false)
        }
    }

    /** Needs you sounds even when the person is looking; Done only when they aren't. The toast shows only when they aren't. */
    fun sounds(kind: String, looking: Boolean) = kind == BLOCKED || !looking

    fun play(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        }
    }
}
