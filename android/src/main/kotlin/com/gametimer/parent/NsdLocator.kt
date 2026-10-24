package com.gametimer.parent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.gametimer.protocol.hub.AgentAddress
import com.gametimer.protocol.hub.DiscoveryTxt
import com.gametimer.protocol.hub.Locator
import java.net.Inet4Address
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a PC by its daily discovery tag (protocol §9) with Android's own mDNS (NsdManager): browses
 * `_gametimer._tcp`, resolves the services one at a time (older Android allows a single resolve per
 * app, so one locator serves all PCs) and returns those whose TXT carries one of the tags. Instance
 * names are random, so only the TXT counts. After the first match it keeps listening for [GRACE], so a
 * fake announcement that answers first doesn't hide the real PC.
 */
class NsdLocator(context: Context) : Locator {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolving = Mutex()
    private val releaser = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun locate(tags: Set<String>, timeout: Duration): List<AgentAddress> {
        val found = Channel<NsdServiceInfo>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_LATEST)
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
            return emptyList()
        }
        val start = TimeSource.Monotonic.markNow()
        var deadline = timeout
        val result = LinkedHashSet<AgentAddress>()
        val resolved = HashSet<String>()
        try {
            while (true) {
                val left = deadline - start.elapsedNow()
                if (left <= Duration.ZERO) break
                val info = withTimeoutOrNull(left) { found.receiveCatching().getOrNull() } ?: break
                if (info.serviceName in resolved) continue
                // Waiting for the app-wide slot counts too: an old resolve may still hold it (API < 34)
                val full = withTimeoutOrNull(RESOLVE_TIMEOUT * 2) { resolve(info) } ?: continue
                resolved += info.serviceName
                if (DiscoveryTxt.tags(full.attributes).none { it in tags }) continue
                val host = hostOf(full) ?: continue
                if (result.isEmpty()) deadline = minOf(deadline, start.elapsedNow() + GRACE)
                result += AgentAddress(host, full.port)
            }
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (e: RuntimeException) { /* already stopped */ }
        }
        return result.toList()
    }

    /**
     * One resolve at a time for the whole app. The caller waits at most [RESOLVE_TIMEOUT] (a fake or stale
     * instance must not eat the whole search), but the app-wide slot is held until Android reports the
     * resolve finished or was stopped (API 34+ can stop it; older versions can't), capped at [RESOLVE_CAP].
     */
    @Suppress("DEPRECATION") // resolveService: the replacement (registerServiceInfoCallback) needs API 34
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        resolving.lock()
        val done = CompletableDeferred<NsdServiceInfo?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) { done.complete(resolved) }
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { done.complete(null) }
            override fun onResolutionStopped(serviceInfo: NsdServiceInfo) { done.complete(null) }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (e: RuntimeException) {
            done.complete(null)
        }
        releaser.launch {
            withTimeoutOrNull(RESOLVE_CAP) { done.await() }
            done.complete(null)
            resolving.unlock()
        }
        try {
            return withTimeoutOrNull(RESOLVE_TIMEOUT) { done.await() }
        } finally {
            if (!done.isCompleted && Build.VERSION.SDK_INT >= 34) {
                try { nsd.stopServiceResolution(listener) } catch (e: RuntimeException) { /* finished meanwhile */ }
            }
        }
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host: hostAddresses needs API 34
    private fun hostOf(info: NsdServiceInfo): String? {
        val address = info.host ?: return null
        return if (address is Inet4Address) address.hostAddress else null
    }

    private companion object {
        val GRACE = 1.seconds
        val RESOLVE_TIMEOUT = 2.seconds
        val RESOLVE_CAP = 10.seconds
    }
}
