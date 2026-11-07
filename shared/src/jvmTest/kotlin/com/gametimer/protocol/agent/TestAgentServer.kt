package com.gametimer.protocol.agent

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The Kotlin [AgentHost] behind a real WebSocket server (Ktor CIO on 127.0.0.1), wired like the Windows
 * app's RemoteServer.V2 and the C# TestAgent: `/v2` and `/v2/pair`, no Origin header allowed, one ticket per
 * connection. The backend is [SimBackend]; the "owner" answers pairing prompts through [answer].
 */
internal class TestAgentServer : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ui = QueueUi()
    val backend = SimBackend()
    val host = AgentHost(MemoryAgentStore(), backend, ui, scope = scope)
    private val server: EmbeddedServer<*, *>
    val port: Int

    init {
        backend.host = host
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets) { maxFrameSize = 65536 }
            routing {
                webSocket("/v2") { serve(pairing = false) }
                webSocket("/v2/pair") { serve(pairing = true) }
            }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private suspend fun DefaultWebSocketServerSession.serve(pairing: Boolean) {
        if (call.request.headers["Origin"] != null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "")); return }
        val ticket = host.tryAdmit(call.request.local.remoteAddress)
            ?: run { close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "")); return }
        host.run(KtorServerTransport(this), ticket, pairing)
    }

    /** A pairing link as the QR would carry it. */
    fun link(): String = "http://127.0.0.1:$port/pair#" + host.startPairing("Kids phone").qrFragment

    override fun close() {
        runBlocking { withTimeoutOrNull(3.seconds) { host.shutdown() } }
        host.close()
        server.stop(100, 1000)
        scope.cancel()
    }
}

/** [FrameTransport] over a Ktor server WebSocket session. */
internal class KtorServerTransport(private val session: DefaultWebSocketServerSession) : FrameTransport {
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

/** The owner's side: each prompt takes the next answer offered with [answer] (a code, or null = cancel). */
internal class QueueUi : PairingUi {
    val asked = LinkedBlockingQueue<String>()
    val aborted = LinkedBlockingQueue<PairingAbortReason>()
    private val answers = LinkedBlockingQueue<CompletableDeferred<String?>>()
    @Volatile private var pending: CompletableDeferred<String?>? = null

    /** The answer to the current (or next) prompt. */
    fun answer(code: String?) {
        synchronized(this) {
            val p = pending
            if (p != null && !p.isCompleted) { pending = null; p.complete(code) } else answers.add(CompletableDeferred(code))
        }
    }

    fun expectAsked(seconds: Long = 20): String = asked.poll(seconds, TimeUnit.SECONDS) ?: error("the owner was never asked")

    override suspend fun askForCode(hubName: String): String? {
        val d = synchronized(this) { answers.poll() ?: CompletableDeferred<String?>().also { pending = it } }
        asked.add(hubName)
        return d.await()
    }

    override fun pairingAborted(reason: PairingAbortReason) {
        aborted.add(reason)
    }
}

/**
 * A stand-in backend with the Windows agent's message shapes (a Kotlin port of the C# TestAgent's SimBackend):
 * a countdown, settings and schedule with rev / ifRev / conflict, and a history.
 */
internal class SimBackend : AgentBackend {
    lateinit var host: AgentHost
    override val info = AgentInfo("Kids phone", "android", "0.0.0-test")
    override val capabilities: Collection<String> = listOf("timer", "settings", "schedule", "history")

    private var startedNanos: Long? = null
    private var durationSeconds = 0
    private var expired = false
    private var settingsRev = 1L
    private var scheduleRev = 1L
    private var settings: JsonObject = buildJsonObject {
        put("action", "lock"); put("yellowThreshold", 10); put("redThreshold", 5)
        put("alertPoints", buildJsonArray { add(buildJsonObject { put("minutes", 5); put("enabled", true) }) })
        put("colorGreen", "#4CAF50"); put("colorYellow", "#FFC107"); put("colorRed", "#F44336"); put("language", "en")
    }
    private var schedule: JsonObject = buildJsonObject {
        put("enabled", false)
        put("week", buildJsonArray {
            repeat(7) { add(buildJsonObject { put("allowed", true); put("limitMinutes", 120); put("windowStartMinutes", 0); put("windowEndMinutes", 1440) }) }
        })
    }
    private val history = mutableListOf<JsonObject>()

    override suspend fun state(): JsonObject = synchronized(this) { stateLocked() }

    override suspend fun handle(type: String, message: JsonObject, context: RequestContext): Reply = synchronized(this) {
        val m = message
        when (type) {
            "state.get" -> ok()
            "timer.start" -> {
                if (running()) return@synchronized Reply.error("already_running")
                durationSeconds = ((m["hours"]?.jsonPrimitive?.int ?: 0) * 60 + (m["minutes"]?.jsonPrimitive?.int ?: 0)) * 60
                if (durationSeconds <= 0) return@synchronized Reply.error("invalid_time")
                startedNanos = System.nanoTime()
                expired = false
                log("started", context, durationSeconds / 60)
                changed(context)
            }
            "timer.stop" -> {
                if (!running()) return@synchronized Reply.error("not_running")
                startedNanos = null
                log("stopped", context, null)
                changed(context)
            }
            "timer.extend" -> {
                if (!running()) return@synchronized Reply.error("not_running")
                val min = m["minutes"]?.jsonPrimitive?.int ?: 0
                if (min == 0) return@synchronized Reply.error("invalid_minutes")
                durationSeconds += min * 60
                log("extended", context, min)
                changed(context)
            }
            "interruption.ack" -> ok()
            "settings.get" -> Reply.ok(buildJsonObject { put("settings", withRev(settings, settingsRev)) })
            "settings.put" -> {
                val ifRev = (m["ifRev"] as? JsonPrimitive)?.long
                if (ifRev != null && ifRev != settingsRev) {
                    return@synchronized Reply.error("conflict", buildJsonObject { put("value", withRev(settings, settingsRev)) })
                }
                for (key in listOf("colorGreen", "colorYellow", "colorRed")) {
                    val c = m[key] ?: continue
                    if (!Regex("^#[0-9A-Fa-f]{6}$").matches((c as? JsonPrimitive)?.content ?: "")) return@synchronized Reply.error("invalid_color")
                }
                val keys = listOf("action", "yellowThreshold", "redThreshold", "alertPoints", "colorGreen", "colorYellow", "colorRed", "language")
                settings = JsonObject(settings + m.filterKeys { it in keys })
                settingsRev++
                host.publish("ev.settings", buildJsonObject { put("settings", withRev(settings, settingsRev)) }, context.origin)
                Reply.ok(buildJsonObject { put("settings", withRev(settings, settingsRev)) })
            }
            "schedule.get" -> Reply.ok(buildJsonObject { put("schedule", withRev(schedule, scheduleRev)) })
            "schedule.put" -> {
                val ifRev = (m["ifRev"] as? JsonPrimitive)?.long
                if (ifRev != null && ifRev != scheduleRev) {
                    return@synchronized Reply.error("conflict", buildJsonObject { put("value", withRev(schedule, scheduleRev)) })
                }
                val week = m["week"] as? JsonArray
                if (week == null || week.size != 7) return@synchronized Reply.error("invalid_schedule")
                schedule = buildJsonObject { put("enabled", m["enabled"]!!.jsonPrimitive.boolean); put("week", week) }
                scheduleRev++
                host.publish("ev.schedule", buildJsonObject { put("schedule", withRev(schedule, scheduleRev)) }, context.origin)
                Reply.ok(buildJsonObject { put("schedule", withRev(schedule, scheduleRev)) })
            }
            "history.get" -> {
                val limit = (m["limit"] as? JsonPrimitive)?.int ?: 50
                val start = (m["before"] as? JsonPrimitive)?.content?.toInt() ?: 0
                val newestFirst = history.reversed()
                val page = newestFirst.drop(start).take(limit)
                val more = start + page.size < newestFirst.size
                Reply.ok(buildJsonObject {
                    put("events", JsonArray(page))
                    put("more", more)
                    if (more) put("next", (start + page.size).toString())
                })
            }
            else -> Reply.error("unknown_command")
        }
    }

    private fun running(): Boolean = startedNanos != null && remaining() > 0

    private fun remaining(): Int {
        val s = startedNanos ?: return 0
        return maxOf(0, durationSeconds - ((System.nanoTime() - s) / 1_000_000_000L).toInt())
    }

    private fun withRev(value: JsonObject, rev: Long) = JsonObject(value + ("rev" to JsonPrimitive(rev)))

    private fun log(type: String, context: RequestContext, minutes: Int?) {
        val e = buildJsonObject {
            put("time", isoUtc(System.currentTimeMillis())); put("type", type); put("source", "phone")
            put("device", context.hubName); put("hub", context.hubId)
            put("mode", if (type == "started") JsonPrimitive("interval") else JsonNull)
            put("minutes", minutes?.let { JsonPrimitive(it) } ?: JsonNull); put("action", JsonNull)
        }
        history.add(e)
        host.publish("ev.history", buildJsonObject { put("event", e) })
    }

    private fun changed(context: RequestContext): Reply {
        host.publish("ev.state", buildJsonObject { put("state", stateLocked()) }, context.origin)
        return ok()
    }

    private fun ok(): Reply = Reply.ok(buildJsonObject { put("state", stateLocked()) })

    private fun stateLocked(): JsonObject {
        if (startedNanos != null && remaining() == 0) { startedNanos = null; expired = true }
        val running = startedNanos != null
        val scheduleOn = (schedule["enabled"] as JsonPrimitive).boolean
        return buildJsonObject {
            put("now", isoUtc(System.currentTimeMillis()))
            put("timer", buildJsonObject {
                put("state", if (running) "running" else if (expired) "expired" else "stopped")
                put("remainingSeconds", if (running) remaining() else 0)
            })
            put("schedule", buildJsonObject {
                put("status", if (scheduleOn) "allowed" else "off")
                put("availableSeconds", if (scheduleOn) 7200 else 0); put("usedSecondsToday", 0)
            })
            put("interruption", JsonNull)
            put("request", JsonNull)
        }
    }

    @Suppress("unused") private fun JsonElement?.unused() = this
}
