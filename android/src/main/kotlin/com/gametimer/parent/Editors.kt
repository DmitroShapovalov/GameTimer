package com.gametimer.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gametimer.protocol.hub.AgentSchedule
import com.gametimer.protocol.hub.AgentSettings

/**
 * Unsaved edits of one PC's settings and schedule. Kept in the view model (per PC), so switching tabs
 * or going back doesn't throw them away; incoming updates from the PC only replace a form that isn't dirty.
 */
class PcEditors {
    // Settings: the numbers are kept as typed text, so a cleared or half-typed field stays what the user sees
    var settingsLoaded by mutableStateOf<AgentSettings?>(null)
    var settingsEdit by mutableStateOf<AgentSettings?>(null)
    var yellowText by mutableStateOf("")
    var redText by mutableStateOf("")
    var settingsDirty by mutableStateOf(false)
    var settingsSaving by mutableStateOf(false)

    fun fillSettings(s: AgentSettings) {
        settingsLoaded = s
        settingsEdit = s
        yellowText = s.yellowThreshold.toString()
        redText = s.redThreshold.toString()
        settingsDirty = false
    }

    // Schedule
    data class DayEdit(val allowed: Boolean, val limit: String, val from: String, val to: String)

    var scheduleLoaded by mutableStateOf<AgentSchedule?>(null)
    var scheduleEnabled by mutableStateOf(false)
    val days = mutableStateListOf<DayEdit>()
    var scheduleDirty by mutableStateOf(false)
    var scheduleSaving by mutableStateOf(false)

    fun fillSchedule(s: AgentSchedule) {
        scheduleLoaded = s
        scheduleEnabled = s.enabled
        days.clear()
        s.week.forEach { d -> days.add(DayEdit(d.allowed, d.limitMinutes.toString(), clock(d.windowStartMinutes), clock(d.windowEndMinutes))) }
        scheduleDirty = false
    }

    companion object {
        fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
