package com.gametimer.child

import com.gametimer.protocol.agent.AgentHost
import com.gametimer.protocol.agent.FrameTransport
import com.gametimer.protocol.agent.ProtocolViolation
import com.gametimer.protocol.agent.run
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The agent on the home network (protocol §3), like the Windows app's RemoteServer: `/v2` and `/v2/pair` on
 * port [PORT] of the Wi-Fi address, only for LAN addresses, never for a browser (an Origin header), one handshake ticket per
 * connection. Runs only while the phone is on Wi-Fi or Ethernet ([AgentRuntime] starts and stops it).
 */
class LanServer(private val host: AgentHost) {
    private var server: EmbeddedServer<*, *>? = null
    private var boundTo: String? = null

    /**
     * Listens on [address] (the Wi-Fi IPv4: not on the mobile-data interface too); rebinds when it changed.
     * False if the port is taken.
     */
    @Synchronized
    fun start(address: String): Boolean {
        if (server != null && boundTo == address) return true
        stop()
        return try {
            boundTo = address
            server = embeddedServer(CIO, configure = {
                connector { port = PORT; host = address }
                reuseAddress = true // the same address again right after a Wi-Fi drop (closed sockets in TIME_WAIT)
            }) {
                install(WebSockets) { maxFrameSize = 65535 }
                routing {
                    webSocket("/v2") { serve(pairing = false) }
                    webSocket("/v2/pair") { serve(pairing = true) }
                }
            }.start(wait = false)
            true
        } catch (e: Exception) {
            server = null
            boundTo = null
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(200, 1000)
        server = null
        boundTo = null
    }

    private suspend fun DefaultWebSocketServerSession.serve(pairing: Boolean) {
        val remote = call.request.local.remoteAddress
        if (!isLanAddress(remote) || call.request.headers["Origin"] != null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
            return
        }
        val ticket = host.tryAdmit(remote) ?: run { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "")); return }
        host.run(SocketTransport(this), ticket, pairing)
    }

    private class SocketTransport(private val session: DefaultWebSocketServerSession) : FrameTransport {
        override suspend fun receive(): ByteArray? {
            while (true) {
                val frame = try { session.incoming.receive() } catch (e: ClosedReceiveChannelException) { return null }
                when (frame) {
                    is Frame.Binary -> if (frame.data.size > 65535) throw ProtocolViolation("message too large") else return frame.data
                    is Frame.Text -> throw ProtocolViolation("text message")
                    is Frame.Close -> return null
                    else -> continue
                }
            }
        }

        override suspend fun send(frame: ByteArray) = session.send(Frame.Binary(true, frame))

        override suspend fun close() {
            try { withTimeoutOrNull(1.seconds) { session.close(CloseReason(CloseReason.Codes.NORMAL, "")) } } catch (e: Exception) { }
        }
    }

    companion object {
        /** The same port as the Windows agent. */
        const val PORT = 5123

        /** Loopback and the private / link-local ranges only (the same list as the PC's NetworkFilter). */
        fun isLanAddress(ip: String): Boolean {
            val a = try { InetAddress.getByName(ip.removePrefix("[").removeSuffix("]")) } catch (e: Exception) { return false }
            val b = a.address
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            // IPv6 unique local fc00::/7 (isSiteLocalAddress covers only the old fec0::/10)
            return b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
        }
    }
}
