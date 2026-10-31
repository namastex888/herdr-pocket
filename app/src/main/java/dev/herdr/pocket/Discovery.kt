package dev.herdr.pocket

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address

/** An SSH server found on the local network. herdr-core itself is only reachable through SSH, so this is a candidate. */
data class NearbyHost(val name: String, val host: String, val port: Int)

/**
 * Browses the local network for SSH servers advertised over mDNS (`_ssh._tcp`, which macOS Remote Login publishes).
 * NsdManager resolves one service at a time, so found services are queued and resolved in order.
 */
class Discovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val _hosts = MutableStateFlow(emptyList<NearbyHost>())
    val hosts: StateFlow<List<NearbyHost>> = _hosts.asStateFlow()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { if (listener === this) listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(service: NsdServiceInfo) { main.post { pending.addLast(service); next() } }
            override fun onServiceLost(service: NsdServiceInfo) {
                main.post { _hosts.update { list -> list.filterNot { it.name == service.serviceName } } }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        pending.clear()
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    // resolveService is deprecated from API 34 but still works, and its replacement needs API 34.
    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val service = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { main.post { resolving = false; next() } }
            override fun onServiceResolved(info: NsdServiceInfo) {
                // An IPv4 literal: SSH can't resolve .local names on Android.
                val address = info.host?.takeIf { it is Inet4Address }?.hostAddress
                main.post {
                    if (address != null && listener != null) {
                        val found = NearbyHost(info.serviceName, address, info.port)
                        _hosts.update { list -> list.filterNot { it.name == found.name || it.host == found.host } + found }
                    }
                    resolving = false; next()
                }
            }
        })
    }

    companion object {
        private const val SERVICE = "_ssh._tcp."
        /** The emulator's alias for the computer running it; its NAT drops mDNS, so this is all it can find. */
        val emulatorHost: NearbyHost? get() = if (BuildConfig.DEBUG && isEmulator) NearbyHost("This computer (emulator host)", "10.0.2.2", 22) else null
        private val isEmulator get() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic")
    }
}
