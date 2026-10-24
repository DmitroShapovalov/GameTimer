package com.gametimer.parent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.gametimer.protocol.hub.AgentAddress
import com.gametimer.protocol.hub.DiscoveryTxt
import com.gametimer.protocol.hub.Locator
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a PC by its daily discovery tag (protocol §9) with Android's own mDNS (NsdManager): browses
 * `_gametimer._tcp`, resolves each service one at a time (older Android allows a single resolve) and
 * returns the first whose TXT carries one of the tags. Instance names are random, so only the TXT counts.
 */
class NsdLocator(context: Context) : Locator {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override suspend fun locate(tags: Set<String>, timeout: Duration): AgentAddress? {
        val found = Channel<NsdServiceInfo>(capacity = 32, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices(DiscoveryTxt.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            return null
        }
        try {
            return withTimeoutOrNull(timeout) {
                val seen = HashSet<String>()
                for (info in found) {
                    if (!seen.add(info.serviceName)) continue
                    val resolved = resolve(info) ?: continue
                    if (DiscoveryTxt.tags(resolved.attributes).none { it in tags }) continue
                    val host = hostOf(resolved) ?: continue
                    return@withTimeoutOrNull AgentAddress(host, resolved.port)
                }
                null
            }
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (e: RuntimeException) { /* already stopped */ }
        }
    }

    @Suppress("DEPRECATION") // resolveService: the replacement (registerServiceInfoCallback) needs API 34
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) { if (cont.isActive) cont.resume(resolved) }
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        } catch (e: RuntimeException) {
            cont.resume(null)
        }
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host: hostAddresses needs API 34
    private fun hostOf(info: NsdServiceInfo): String? {
        val address = info.host ?: return null
        return if (address is Inet4Address) address.hostAddress else null
    }
}
