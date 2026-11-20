package app.localbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-network discovery for LoBBY.
 *
 * This class only advertises/discovers an already-running TCP service. It does
 * not create a network, enable a hotspot, or depend on internet connectivity.
 * The service record is intentionally small and platform-neutral.
 */
class LobbyDiscovery(context: Context) {
    data class Device(
        val name: String,
        val host: String,
        val port: Int,
        val sharePath: String = "/files"
    ) {
        val baseUrl: String get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"
    }

    companion object {
        const val SERVICE_TYPE = "_lobby._tcp."
    }

    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = ConcurrentHashMap<String, Device>()
    @Volatile private var onDevicesChanged: ((List<Device>) -> Unit)? = null

    @Synchronized
    fun advertise(port: Int, deviceName: String = "LoBBY Android", sharePath: String = "/files") {
        stopAdvertising()
        val service = NsdServiceInfo().apply {
            serviceName = deviceName.take(50)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("path", sharePath))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        try {
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            registrationListener = null
        }
    }

    @Synchronized
    fun startDiscovery(onChanged: (List<Device>) -> Unit) {
        stopDiscovery()
        found.clear()
        onDevicesChanged = onChanged
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopDiscovery() }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                publish()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                try {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = if (Build.VERSION.SDK_INT >= 34) {
                                (info.hostAddresses.firstOrNull { it is java.net.Inet4Address }
                                    ?: info.hostAddresses.firstOrNull())?.hostAddress
                            } else {
                                @Suppress("DEPRECATION")
                                info.host?.hostAddress
                            } ?: return
                            if (info.port !in 1..65535) return
                            val advertisedPath = info.attributes["path"]?.toString(Charsets.UTF_8)
                                ?.takeIf { it.matches(Regex("/[A-Za-z0-9_/-]+")) && !it.contains("//") }
                                ?: "/files"
                            found[info.serviceName] = Device(info.serviceName, host, info.port, advertisedPath)
                            publish()
                        }
                    })
                } catch (_: Exception) { }
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discoveryListener = null
        }
    }

    private fun publish() {
        onDevicesChanged?.invoke(found.values.sortedBy { it.name.lowercase() })
    }

    @Synchronized
    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try { manager.stopServiceDiscovery(listener) } catch (_: Exception) { }
        found.clear()
        onDevicesChanged?.invoke(emptyList())
        onDevicesChanged = null
    }

    @Synchronized
    fun stopAdvertising() {
        val listener = registrationListener ?: return
        registrationListener = null
        try { manager.unregisterService(listener) } catch (_: Exception) { }
    }

    fun stop() {
        stopDiscovery()
        stopAdvertising()
    }
}
