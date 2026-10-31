package com.gametimer.protocol.hub

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/** One WebSocket to an agent (protocol §3): one binary message = one Noise message. */
public interface FrameSocket {
    public suspend fun send(frame: ByteArray)

    /** The next binary message; null once the connection is closed (a text message closes it too). */
    public suspend fun receive(): ByteArray?

    public suspend fun close()
}

/** Opens [FrameSocket]s; the app uses [KtorConnector], tests may plug in their own. */
public fun interface Connector {
    public suspend fun connect(host: String, port: Int, path: String): FrameSocket
}

/**
 * Opens a [FrameSocket] by URL: the relay's `wss://<relay>/v2/hub/<route>` (§10.3). Throws
 * [RelayRouteNotFound] when the relay answers 404 (unknown route = PC offline), anything else otherwise.
 */
public fun interface RelayConnector {
    public suspend fun connect(url: String): FrameSocket
}

/** The relay answered 404: no PC holds that route (offline, or the route is for another day). */
public class RelayRouteNotFound(cause: Throwable? = null) : Exception("route not found", cause)

/** The relay path failed; never a statement about the PC itself (the relay isn't trusted, §10.5). */
public class RelayUnreachableException(cause: Throwable? = null) : Exception("relay unreachable", cause)

/**
 * WebSocket connector on Ktor (OkHttp on Android / JVM, Darwin on iOS). Sends no Origin header:
 * the agent refuses browsers (§3).
 */
public class KtorConnector(private val client: HttpClient = defaultClient()) : Connector, RelayConnector {
    override suspend fun connect(host: String, port: Int, path: String): FrameSocket =
        KtorFrameSocket(client.webSocketSession(method = HttpMethod.Get, host = host, port = port, path = path))

    override suspend fun connect(url: String): FrameSocket =
        try {
            KtorFrameSocket(client.webSocketSession(url))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The engines report a refused upgrade as "… expected status code 101 but was 404"
            if (generateSequence<Throwable>(e) { it.cause }.any { NOT_FOUND.containsMatchIn(it.message ?: "") }) throw RelayRouteNotFound(e)
            throw e
        }

    public companion object {
        public fun defaultClient(): HttpClient = HttpClient {
            install(WebSockets) // message size is checked in receive() (OkHttp doesn't support maxFrameSize)
        }

        internal const val MAX_MESSAGE = 65535
        private val NOT_FOUND = Regex("""\b404\b""")
    }
}

private class KtorFrameSocket(private val session: DefaultClientWebSocketSession) : FrameSocket {
    override suspend fun send(frame: ByteArray) {
        session.send(Frame.Binary(fin = true, data = frame))
    }

    override suspend fun receive(): ByteArray? {
        while (true) {
            val frame = try { session.incoming.receive() } catch (e: ClosedReceiveChannelException) { return null }
            when (frame) {
                is Frame.Binary -> return if (frame.data.size > KtorConnector.MAX_MESSAGE) null else frame.data
                is Frame.Close -> return null
                is Frame.Text -> { close(); return null } // never sent by an agent
                else -> continue // ping / pong are handled by Ktor
            }
        }
    }

    override suspend fun close() {
        // Bounded: a stuck outgoing buffer must not hold up stopping the link (callers run this NonCancellable)
        try {
            kotlinx.coroutines.withTimeoutOrNull(1_000) { session.close(CloseReason(CloseReason.Codes.NORMAL, "")) }
                ?: session.cancel()
        } catch (e: Exception) { /* already gone */ }
    }
}
