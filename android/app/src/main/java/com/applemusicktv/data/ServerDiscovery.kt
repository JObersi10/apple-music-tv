package com.applemusicktv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Finds the PC proxy on the LAN over mDNS/Bonjour (the server advertises `_amproxy._tcp`), so the
 * app recovers on its own when the server's IP changes — no retyping, no getting stuck in
 * standalone. Fire-and-forget: call [discover] when the server is unreachable; [onFound] fires with
 * a ready base URL ("http://host:port/") the first time a proxy answers, then discovery stops.
 */
class ServerDiscovery(context: Context) {
    private val appCtx = context.applicationContext
    private val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile private var running = false

    fun discover(onFound: (String) -> Unit) {
        if (running) return
        running = true

        // mDNS is multicast — many Android/Fire TV builds drop multicast without this lock held.
        val lock = (appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("amproxy-discovery")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }

        var listener: NsdManager.DiscoveryListener? = null
        fun cleanup() {
            if (!running) return
            running = false
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            runCatching { lock?.release() }
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { cleanup() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("amproxy")) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val url = "http://$host:${info.port}/"
                            Log.i("AMnsd", "found proxy at $url")
                            onFound(url)
                            cleanup()
                        }
                    })
                }
            }
        }

        runCatching {
            nsd.discoverServices("_amproxy._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { cleanup() }

        // Give up after ~10s so we don't hold the multicast lock forever on a LAN with no server.
        android.os.Handler(appCtx.mainLooper).postDelayed({ cleanup() }, 10_000)
    }
}
