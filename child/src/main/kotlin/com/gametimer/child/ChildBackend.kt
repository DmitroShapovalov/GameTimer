package com.gametimer.child

import com.gametimer.protocol.agent.AgentBackend
import com.gametimer.protocol.agent.AgentInfo
import com.gametimer.protocol.agent.Reply
import com.gametimer.protocol.agent.RequestContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's side of the protocol as of C2: it says who it is and reports a §7.1 state in the Windows agent's
 * shape (no timer running, no schedule), so the parent app shows the phone. It announces no capabilities of
 * its own yet: timer, schedule, history and the game list come with enforcement in C3, and until then the
 * host answers their requests `unsupported`.
 */
class ChildBackend(deviceName: String, version: String) : AgentBackend {
    override val info: AgentInfo = AgentInfo(deviceName, "android", version)

    override val capabilities: Collection<String> = emptyList()

    override suspend fun state(): JsonObject = buildJsonObject {
        put("now", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        put("timer", buildJsonObject { put("state", "stopped"); put("remainingSeconds", 0) })
        put("schedule", buildJsonObject { put("status", "off"); put("availableSeconds", 0); put("usedSecondsToday", 0) })
        put("interruption", JsonNull)
        put("request", JsonNull)
    }

    override suspend fun handle(type: String, message: JsonObject, context: RequestContext): Reply =
        when (type) {
            "state.get" -> Reply.ok(buildJsonObject { put("state", state()) })
            else -> Reply.error("unsupported")
        }
}
