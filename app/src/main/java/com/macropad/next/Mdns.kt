package com.macropad.next

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** Tìm cổng ADB của chính máy này bằng mDNS (type: _adb-tls-pairing._tcp hoặc _adb-tls-connect._tcp) */
class Mdns(ctx: Context, private val type: String) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val h = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null

    @Volatile
    var port: Int = -1

    fun start() {
        port = -1
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { UiLog.add("mDNS lỗi bắt đầu: $e") }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(i: NsdServiceInfo?) { port = -1 }
            override fun onServiceFound(i: NsdServiceInfo?) { if (i != null) resolve(i) }
        }
        listener = l
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {
                if (e == NsdManager.FAILURE_ALREADY_ACTIVE) h.postDelayed({ resolve(info) }, 300)
            }
            override fun onServiceResolved(i: NsdServiceInfo?) {
                val host = i?.host
                if (i != null && host != null && isLocal(host)) port = i.port
            }
        })
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
    }

    fun waitPort(timeoutMs: Long): Int {
        val end = System.currentTimeMillis() + timeoutMs
        while (port < 0 && System.currentTimeMillis() < end) Thread.sleep(200)
        return port
    }

    private fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress) return true
        return try {
            val ha = a.hostAddress?.substringBefore('%')
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { ni ->
                Collections.list(ni.inetAddresses).any { it.hostAddress?.substringBefore('%') == ha }
            }
        } catch (e: Exception) { true }
    }
}
