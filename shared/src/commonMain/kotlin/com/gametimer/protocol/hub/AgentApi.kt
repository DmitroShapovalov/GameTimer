package com.gametimer.protocol.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A typed result: the value, or why there is none. */
public sealed interface ApiResult<out T> {
    public data class Ok<T>(val value: T) : ApiResult<T>

    /** §5 conflict: someone else changed it first; [current] is the agent's value now. */
    public data class Conflict<T>(val current: T) : ApiResult<T>

    /** Refused with a §7.2 error code, or not delivered (gave up / revoked / stopped). */
    public data class Failed(val outcome: RequestOutcome) : ApiResult<Nothing>
}

/**
 * The §7.2 requests as typed calls over an [AgentLink]. Writes carry `ifRev` from the value the user
 * edited, so a concurrent change on the PC or by another parent is reported, never silently overwritten.
 */
public class AgentApi(private val link: AgentLink) {
    public suspend fun startInterval(hours: Int, minutes: Int): RequestOutcome =
        link.send("timer.start", buildJsonObject { put("mode", "interval"); put("hours", hours); put("minutes", minutes) })

    /** Ends at that local time of day on the PC. */
    public suspend fun startUntil(hour: Int, minute: Int): RequestOutcome =
        link.send("timer.start", buildJsonObject { put("mode", "absolute"); put("hours", hour); put("minutes", minute) })

    public suspend fun stop(): RequestOutcome = link.send("timer.stop", JsonObject(emptyMap()))

    public suspend fun extend(minutes: Int): RequestOutcome = link.send("timer.extend", buildJsonObject { put("minutes", minutes) })

    public suspend fun acknowledgeInterruption(): RequestOutcome = link.send("interruption.ack", JsonObject(emptyMap()))

    public suspend fun settings(): ApiResult<AgentSettings> = read("settings.get", null) { it["settings"]!!.decodeAs() }

    public suspend fun saveSettings(edited: AgentSettings): ApiResult<AgentSettings> {
        val body = edited.toJsonObject().toMutableMap().apply { remove("rev"); put("ifRev", kotlinx.serialization.json.JsonPrimitive(edited.rev)) }
        return write("settings.put", JsonObject(body), "settings") { it.decodeAs() }
    }

    public suspend fun schedule(): ApiResult<AgentSchedule> = read("schedule.get", null) { padWeek(it["schedule"]!!.decodeAs()) }

    public suspend fun saveSchedule(edited: AgentSchedule): ApiResult<AgentSchedule> {
        require(edited.week.size == 7) { "a schedule has 7 days" }
        val body = edited.toJsonObject().toMutableMap().apply { remove("rev"); put("ifRev", kotlinx.serialization.json.JsonPrimitive(edited.rev)) }
        return write("schedule.put", JsonObject(body), "schedule") { padWeek(it.decodeAs()) }
    }

    /** A PC that never saved a schedule may send fewer than 7 days: fill in "allowed, no limit, all day". */
    private fun padWeek(s: AgentSchedule): AgentSchedule =
        if (s.week.size >= 7) s.copy(week = s.week.take(7))
        else s.copy(week = s.week + List(7 - s.week.size) { DaySchedule(true, 0, 0, 1440) })

    public suspend fun history(limit: Int = 50, before: String? = null): ApiResult<HistoryPage> =
        read("history.get", buildJsonObject { put("limit", limit); if (before != null) put("before", before) }) { r ->
            HistoryPage(
                events = (r["events"] as JsonArray).map { it.decodeAs<HistoryEvent>() },
                more = r["more"].toString() == "true",
                next = r.string("next"),
            )
        }

    public suspend fun peers(): ApiResult<List<PeerInfo>> = read("peers.list", null) { peersOf(it) }

    /** Revoke a parent app (admin), or this phone itself (any role). */
    public suspend fun revoke(peerId: String): ApiResult<List<PeerInfo>> =
        write("peers.revoke", buildJsonObject { put("peer", peerId) }, null) { peersOf(it) }

    public suspend fun setRole(peerId: String, admin: Boolean): ApiResult<List<PeerInfo>> =
        write("peers.setRole", buildJsonObject { put("peer", peerId); put("role", if (admin) "admin" else "parent") }, null) { peersOf(it) }

    private fun peersOf(reply: JsonObject): List<PeerInfo> = (reply["peers"] as JsonArray).map { it.decodeAs() }

    private suspend fun <T> read(type: String, fields: JsonObject?, parse: (JsonObject) -> T): ApiResult<T> =
        when (val r = link.send(type, fields, changesState = false)) {
            is RequestOutcome.Ok -> parsed { ApiResult.Ok(parse(r.reply)) }
            else -> ApiResult.Failed(r)
        }

    /**
     * A write; the value is taken from [field] of the result (or the whole result when null), and a
     * `conflict` carries the current value in `value` (§5).
     */
    private suspend fun <T> write(type: String, fields: JsonObject, field: String?, parseValue: (JsonObject) -> T): ApiResult<T> =
        when (val r = link.send(type, fields)) {
            is RequestOutcome.Ok -> parsed { ApiResult.Ok(parseValue(if (field == null) r.reply else r.reply[field] as JsonObject)) }
            is RequestOutcome.Refused -> if (r.code == "conflict" && r.reply?.get("value") is JsonObject) {
                parsed { ApiResult.Conflict(parseValue(r.reply["value"] as JsonObject)) }
            } else {
                ApiResult.Failed(r)
            }
            else -> ApiResult.Failed(r)
        }

    /** A reply that doesn't have the expected shape (a newer or broken agent) is a failure, never a crash. */
    private inline fun <T> parsed(block: () -> ApiResult<T>): ApiResult<T> =
        try { block() } catch (e: Exception) { ApiResult.Failed(RequestOutcome.Refused(BAD_REPLY)) }

    public companion object {
        /** The code used when a reply couldn't be understood. */
        public const val BAD_REPLY: String = "bad_reply"
    }
}
