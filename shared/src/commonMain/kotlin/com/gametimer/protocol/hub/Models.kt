package com.gametimer.protocol.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Payload shapes of protocol §7 (they reuse the v1 API's shapes, docs/remote-api.http). */

@Serializable
public data class AlertPoint(val minutes: Int, val enabled: Boolean = true)

/** `settings` (§7.2 settings.get / settings.put). [action]: shutdown / lock / logoff. */
@Serializable
public data class AgentSettings(
    val action: String,
    val yellowThreshold: Int,
    val redThreshold: Int,
    val alertPoints: List<AlertPoint>,
    val colorGreen: String,
    val colorYellow: String,
    val colorRed: String,
    val language: String,
    val rev: Long = 0,
)

/** One weekday of the schedule. Minutes since midnight; end 1440 = midnight. */
@Serializable
public data class DaySchedule(
    val allowed: Boolean,
    val limitMinutes: Int,
    val windowStartMinutes: Int,
    val windowEndMinutes: Int,
)

/** `schedule` (§7.2): [week] has 7 days indexed by weekday, 0 = Sunday. */
@Serializable
public data class AgentSchedule(val enabled: Boolean, val week: List<DaySchedule>, val rev: Long = 0)

/** One history event (§7.2 history.get, §7.3 ev.history). */
@Serializable
public data class HistoryEvent(
    val time: String,
    val type: String,
    val source: String,
    val device: String? = null,
    /** The hub id of the parent app that did it (agent v1.7.2+); compare with `hello.you.id`. */
    val hub: String? = null,
    val mode: String? = null,
    val minutes: Int? = null,
    val action: String? = null,
)

/** One page of history, newest first; [next] continues it when [more]. */
public data class HistoryPage(val events: List<HistoryEvent>, val more: Boolean, val next: String?)

/** A paired parent app (§7.2 peers[]). */
@Serializable
public data class PeerInfo(
    val id: String,
    val name: String,
    val role: String,
    val pairedAt: String? = null,
    val lastSeen: String? = null,
    val current: Boolean = false,
)

internal val ModelJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

internal inline fun <reified T> JsonElement.decodeAs(): T = ModelJson.decodeFromJsonElement<T>(this)

internal inline fun <reified T> T.toJsonObject(): JsonObject = ModelJson.encodeToJsonElement(this).jsonObject
