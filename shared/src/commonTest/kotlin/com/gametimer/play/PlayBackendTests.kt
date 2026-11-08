@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gametimer.play

import com.gametimer.protocol.agent.AgentFixture
import com.gametimer.protocol.agent.AgentHost
import com.gametimer.protocol.agent.AgentInfo
import com.gametimer.protocol.agent.FakePairingUi
import com.gametimer.protocol.agent.MemoryAgentStore
import com.gametimer.protocol.agent.TestHub
import com.gametimer.protocol.agent.obj
import com.gametimer.protocol.agent.s
import com.gametimer.protocol.agent.t
import com.gametimer.protocol.identity.PairingCode
import com.gametimer.protocol.noise.KeyPair
import com.gametimer.protocol.noise.TestSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The phone's rules (C3) through the real AgentHost and a scripted parent app; the time is moved by hand. */
class PlayBackendTests {
    @BeforeTest
    fun init() = TestSupport.initSodium()

    /** A phone whose clock the test moves: wall time and the monotonic clock together. */
    private class FakePhone : PlayPlatform {
        var epoch = 1_792_843_200_000L // 2026-10-24 12:00 UTC, a Saturday; the zone is UTC
        var elapsed = 0L
        var saved: PlayData? = null
        var stored: PlayData? = null
        var boot = -1
        val apps = listOf(AppEntry("com.game.one", "Game One"), AppEntry("com.game.two", "Game Two"), AppEntry("com.school", "School"))

        fun advance(ms: Long) { epoch += ms; elapsed += ms }

        override fun now(): LocalNow {
            val days = epoch.floorDiv(86_400_000L)
            val sod = (epoch.mod(86_400_000L) / 1000).toInt()
            val weekday = ((days + 4) % 7).toInt() // 1970-01-01 was a Thursday
            val date = com.gametimer.protocol.identity.DiscoveryTag.utcDate(epoch)
            return LocalNow(epoch, date, sod, weekday, date + "T00:00:00Z")
        }

        override fun elapsedMillis(): Long = elapsed
        override fun installedApps(): List<AppEntry> = apps
        override fun load(): PlayData? = stored
        override fun save(data: PlayData) { saved = data }
        override fun bootCount(): Int = boot
    }

    private class Rig(val test: TestScope, val phone: FakePhone = FakePhone()) {
        val backend = PlayBackend(AgentInfo("Kid's phone", "android", "test"), phone)
        val ui = FakePairingUi()
        val host = AgentHost(MemoryAgentStore(), backend, ui, clock = { phone.epoch }, scope = test.backgroundScope,
            timeSource = test.testScheduler.timeSource)
        init { backend.host = host }

        suspend fun parent(name: String = "Parent"): TestHub {
            val offer = host.startPairing("Kid's phone")
            val key = KeyPair.generate()
            val (h, _) = TestHub.handshake(host, key, pairing = true, psk = offer.secret, firstType = "pair", scope = test.backgroundScope)
            val code = PairingCode.derive(h.handshakeHash!!)
            ui.answer = { code }
            h.sendJson(AgentFixture.pairHello(name))
            h.receiveJson()
            host.cancelPairing()
            return TestHub.connect(host, key, test.backgroundScope).first
        }

        /** [seconds] of the child's phone with [front] in front, ticking every second. */
        fun play(front: String?, seconds: Int) {
            repeat(seconds) { phone.advance(1000); backend.tick(front) }
        }
    }

    private fun minutes(m: Int) = buildJsonObject { put("mode", "interval"); put("hours", 0); put("minutes", m) }
    private fun JsonObject.timer() = obj("state").obj("timer")
    private suspend fun TestHub.state() = request("state.get").obj("state")

    private suspend fun TestHub.limit(vararg ids: String, rev: Long) =
        request("apps.put", buildJsonObject { put("limited", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }); put("ifRev", rev) }, withRid = true)

    @Test
    fun helloAnnouncesThePhoneCapabilitiesAndMinor4() = runTest {
        val rig = Rig(this)
        val offer = rig.host.startPairing("x")
        val key = KeyPair.generate()
        val (h, _) = TestHub.handshake(rig.host, key, pairing = true, psk = offer.secret, firstType = "pair", scope = backgroundScope)
        val code = PairingCode.derive(h.handshakeHash!!)
        rig.ui.answer = { code }
        h.sendJson(AgentFixture.pairHello())
        h.receiveJson()
        val hub = TestHub.handshake(rig.host, key, pairing = false, scope = backgroundScope).first
        hub.sendJson(buildJsonObject { put("t", "ready"); put("protocol", 2); put("minor", 4) })
        val hello = hub.receiveJson()
        assertEquals(4, hello["minor"]!!.jsonPrimitive.int)
        assertEquals(listOf("timer", "schedule", "history", "apps", "peers", "push"), (hello["capabilities"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("stopped", hello.obj("state").obj("timer").s("state"))
        rig.host.close()
    }

    @Test
    fun timeCountsOnlyWhileALimitedGameIsInFront() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        assertEquals("result", p.limit("com.game.one", rev = 1).t())
        assertEquals("paused", p.request("timer.start", minutes(10), withRid = true).timer().s("state"))

        rig.play("com.school", 120) // not a game: nothing counts
        assertEquals(600, p.state().obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        rig.play("com.game.one", 1)
        assertEquals("running", p.state().obj("timer").s("state"))
        rig.play("com.game.one", 60)
        val t = p.state().obj("timer")
        assertEquals("running", t.s("state"))
        assertTrue(t["remainingSeconds"]!!.jsonPrimitive.int in 538..541)
        rig.play(null, 30) // the screen went off
        assertEquals("paused", p.state().obj("timer").s("state"))
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun timesUpBlocksOnlyLimitedGamesUntilAParentActs() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", "com.game.two", rev = 1)
        p.request("timer.start", minutes(1), withRid = true)
        rig.play("com.game.one", 62)
        assertEquals("expired", p.state().obj("timer").s("state"))
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.two"))
        assertNull(rig.backend.blockReason("com.school"))
        rig.play("com.game.two", 30)
        assertEquals("expired", p.state().obj("timer").s("state")) // blocked: no time is spent

        // The child asks; the parent says yes: 15 more minutes, even with nothing running
        assertEquals(AskResult.Sent, rig.backend.askForMoreTime())
        assertEquals(AskResult.AlreadyAsked, rig.backend.askForMoreTime())
        val req = p.state()["request"]!!.jsonObject.s("id")!!
        assertEquals("result", p.request("timer.answerRequest", buildJsonObject { put("request", req); put("minutes", 15) }, withRid = true).t())
        assertNull(rig.backend.blockReason("com.game.two"))
        assertEquals(900, p.state().obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        assertEquals("no_request", p.request("timer.answerRequest", buildJsonObject { put("request", req); put("minutes", 15) }, withRid = true).s("code"))

        // Stop: no limit at all any more
        p.request("timer.stop", null, withRid = true)
        assertEquals("stopped", p.state().obj("timer").s("state"))
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun aNoPausesAskingForFiveMinutes() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.request("timer.start", minutes(1), withRid = true)
        rig.play("com.game.one", 61)
        rig.backend.askForMoreTime()
        val req = p.state()["request"]!!.jsonObject.s("id")!!
        p.request("timer.answerRequest", buildJsonObject { put("request", req); put("minutes", 0) }, withRid = true)
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        assertEquals(AskResult.TooSoon, rig.backend.askForMoreTime())
        rig.play(null, 301)
        assertEquals(AskResult.Sent, rig.backend.askForMoreTime())
        rig.host.close()
    }

    @Test
    fun theScheduleGivesTheDaysAllowanceAndTheAllowedHours() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        // Saturday (index 6): 30 minutes, 08:00–20:00; Sunday no games
        val week = buildJsonArray {
            repeat(7) { i ->
                add(buildJsonObject {
                    put("allowed", i != 0); put("limitMinutes", 30); put("windowStartMinutes", 480); put("windowEndMinutes", 1200)
                })
            }
        }
        val put = p.request("schedule.put", buildJsonObject { put("enabled", true); put("week", week); put("ifRev", 1) }, withRid = true)
        assertEquals("result", put.t())
        var st = p.state()
        assertEquals("paused", st.obj("timer").s("state")) // today's allowance started by itself
        assertEquals(1800, st.obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        assertEquals("allowed", st.obj("schedule").s("status"))

        rig.play("com.game.one", 30 * 60 + 1)
        st = p.state()
        assertEquals("expired", st.obj("timer").s("state"))
        assertEquals("limitReached", st.obj("schedule").s("status"))
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        // A stale edit is a conflict with the current value
        val stale = p.request("schedule.put", buildJsonObject { put("enabled", false); put("week", week); put("ifRev", 1) }, withRid = true)
        assertEquals("conflict", stale.s("code"))
        assertEquals(2, stale.obj("value")["rev"]!!.jsonPrimitive.int)

        // 20:00: still time's up (today's allowance is gone); at midnight, Sunday: no games at all ("not now")
        rig.play(null, 7 * 3600 + 1800)
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        rig.play(null, 4 * 3600 + 60)
        st = p.state()
        assertEquals("dayDisabled", st.obj("schedule").s("status"))
        assertEquals(0, st.obj("schedule")["usedSecondsToday"]!!.jsonPrimitive.int) // a new day
        assertEquals("stopped", st.obj("timer").s("state")) // yesterday's time's up doesn't carry over
        assertEquals(BlockReason.NotNow, rig.backend.blockReason("com.game.one"))
        // Lift for today: the schedule is set aside until midnight
        assertEquals("result", p.request("timer.stop", buildJsonObject { put("skipScheduleToday", true) }, withRid = true).t())
        assertEquals("skippedToday", p.state().obj("schedule").s("status"))
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun appsListTheInstalledOnesAndSaveTheLimitedWithARev() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        val apps = p.request("apps.get").obj("apps")
        assertEquals(3, (apps["installed"] as JsonArray).size)
        assertEquals(1, apps["rev"]!!.jsonPrimitive.int)
        val saved = p.limit("com.game.two", rev = 1)
        val two = (saved.obj("apps")["installed"] as JsonArray).map { it.jsonObject }.single { it.s("id") == "com.game.two" }
        assertEquals("true", two.s("limited"))
        assertEquals("conflict", p.limit("com.game.one", rev = 1).s("code")) // stale
        assertEquals(setOf("com.game.two"), rig.phone.saved!!.limited) // persisted
        assertEquals("bad_request", p.request("apps.put", buildJsonObject { put("limited", 5) }, withRid = true).s("code"))
        rig.host.close()
    }

    @Test
    fun otherParentsSeeChangesAndHistoryRecordsThem() = runTest {
        val rig = Rig(this)
        val a = rig.parent("A")
        val b = rig.parent("B")
        a.limit("com.game.one", rev = 1)
        a.request("timer.start", minutes(5), withRid = true)
        a.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true)
        // B sees the apps change, then history and state as they happen
        val seen = mutableListOf<String>()
        repeat(5) { seen += b.receiveJson().t()!! }
        assertTrue("ev.apps" in seen && "ev.history" in seen && "ev.state" in seen, seen.toString())
        val h = a.request("history.get", buildJsonObject { put("limit", 10) })
        val types = (h["events"] as JsonArray).map { it.jsonObject.s("type") }
        assertEquals(listOf("extended", "started"), types)
        assertEquals("A", (h["events"] as JsonArray)[0].jsonObject.s("device"))
        rig.host.close()
    }

    @Test
    fun aTimerStartedDuringALiftIsNotCappedAndEndsTheLift() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        val week = buildJsonArray { repeat(7) { add(buildJsonObject { put("allowed", true); put("limitMinutes", 10); put("windowStartMinutes", 0); put("windowEndMinutes", 1440) }) } }
        p.request("schedule.put", buildJsonObject { put("enabled", true); put("week", week) }, withRid = true)
        // Capped by the schedule: a 60-minute timer with 10 minutes left today
        p.request("timer.stop", null, withRid = true)
        assertEquals(600, p.request("timer.start", minutes(60), withRid = true).timer()["remainingSeconds"]!!.jsonPrimitive.int)
        p.request("timer.stop", buildJsonObject { put("skipScheduleToday", true) }, withRid = true)
        assertEquals(3600, p.request("timer.start", minutes(60), withRid = true).timer()["remainingSeconds"]!!.jsonPrimitive.int)
        rig.play("com.game.one", 3601)
        val st = p.state()
        assertEquals("expired", st.obj("timer").s("state"))
        assertEquals("limitReached", st.obj("schedule").s("status")) // the lift ended with its timer
        rig.host.close()
    }

    @Test
    fun theStateSurvivesARestart() = runTest {
        val phone = FakePhone()
        val first = Rig(this, phone)
        val p = first.parent()
        p.limit("com.game.one", rev = 1)
        p.request("timer.start", minutes(10), withRid = true)
        first.play("com.game.one", 60)
        first.backend.saveNow() // the app stops
        first.host.close()
        phone.stored = phone.saved
        val second = PlayBackend(AgentInfo("x", "android", "t"), phone)
        assertTrue(second.minutesLeft()!! in 8..9)
        assertFalse(second.asked)
    }


    private fun week(limit: Int, start: Int = 0, end: Int = 1440, allowed: (Int) -> Boolean = { true }) = buildJsonArray {
        repeat(7) { i ->
            add(buildJsonObject { put("allowed", allowed(i)); put("limitMinutes", limit); put("windowStartMinutes", start); put("windowEndMinutes", end) })
        }
    }

    private suspend fun TestHub.schedule(week: JsonArray) =
        request("schedule.put", buildJsonObject { put("enabled", true); put("week", week) }, withRid = true)

    @Test
    fun theAllowanceStartsByItselfWhenTheAllowedHoursBegin() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(30, start = 13 * 60, end = 20 * 60)) // it is 12:00
        assertEquals("stopped", p.state().obj("timer").s("state"))
        assertEquals(BlockReason.NotNow, rig.backend.blockReason("com.game.one"))
        rig.play(null, 3600)
        val st = p.state()
        assertEquals("allowed", st.obj("schedule").s("status"))
        assertEquals(1800, st.obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun withTheScheduleOnNoTimerCrossesMidnight() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(30))
        // A parent's two hours late in the evening: tomorrow still starts with the day's 30 minutes
        rig.play(null, 11 * 3600) // 23:00
        p.request("timer.extend", buildJsonObject { put("minutes", 120) }, withRid = true)
        rig.play(null, 3600 + 60)
        assertEquals(1800, p.state().obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        rig.host.close()
    }

    @Test
    fun movingTheDateOrTheClockGainsNothing() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(30))
        rig.play("com.game.one", 30 * 60 + 1)
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        // The child moves the date ahead, day after day: still today, still used up
        repeat(9) {
            rig.phone.epoch += 86_400_000L
            rig.play("com.game.one", 2)
            assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        }
        // And back to the real date: the same
        rig.phone.epoch -= 9 * 86_400_000L
        rig.play("com.game.one", 2)
        assertEquals("limitReached", p.state().obj("schedule").s("status"))
        // Late in the evening, a date never seen at 23:00: still today
        rig.play(null, 10 * 3600 + 1800) // real 22:30
        rig.phone.epoch += 3 * 86_400_000L + 1800_000L
        rig.play("com.game.one", 2)
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        rig.phone.epoch += 86_400_000L
        rig.play("com.game.one", 2)
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        // The real midnight (1.5 hours on) does start a new day
        rig.play(null, 5400)
        assertNull(rig.backend.blockReason("com.game.one"))
        assertEquals(1800, p.state().obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        rig.host.close()
    }

    @Test
    fun aGrantBeforeTheAllowedHoursDoesNotUseUpTheDay() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(30, start = 13 * 60, end = 20 * 60)) // it is 12:00
        p.request("timer.extend", buildJsonObject { put("minutes", 10) }, withRid = true)
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.play("com.game.one", 120) // 2 of the 10 minutes now
        rig.play(null, 3600) // 13:02: the hours began
        val st = p.state()
        assertEquals("allowed", st.obj("schedule").s("status"))
        assertEquals(38 * 60, st.obj("timer")["remainingSeconds"]!!.jsonPrimitive.int) // 30 + 10 − 2
        rig.play("com.game.one", 30 * 60)
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun aGrantAtNightIsOnlyTheGrantedMinutes() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(60, start = 0, end = 20 * 60))
        rig.play(null, 9 * 3600) // 21:00, 60 minutes unused
        assertEquals(BlockReason.NotNow, rig.backend.blockReason("com.game.one"))
        p.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true)
        assertEquals(300, p.state().obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        rig.play("com.game.one", 301)
        assertEquals(BlockReason.TimeUp, rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun aParentsPlusAndLaterEndAreGrantedPastTheSchedule() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(10))
        rig.play("com.game.one", 10 * 60 + 1)
        assertEquals("limitReached", p.state().obj("schedule").s("status"))
        // + when today's limit is used: 5 more minutes, the limit rises with them
        assertEquals("result", p.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true).t())
        var st = p.state()
        assertEquals(300, st.obj("timer")["remainingSeconds"]!!.jsonPrimitive.int)
        assertEquals("allowed", st.obj("schedule").s("status"))
        assertNull(rig.backend.blockReason("com.game.one"))
        // A later end counts the same way
        assertEquals("result", p.request("timer.setEnd", buildJsonObject { put("inSeconds", 1200) }, withRid = true).t())
        rig.play("com.game.one", 1100)
        st = p.state()
        assertEquals("running", st.obj("timer").s("state"))
        assertEquals("allowed", st.obj("schedule").s("status"))
        // Outside the allowed hours a + lets the child play now
        p.schedule(week(0, start = 0, end = 60))
        assertEquals(BlockReason.NotNow, rig.backend.blockReason("com.game.one"))
        p.request("timer.extend", buildJsonObject { put("minutes", 5) }, withRid = true)
        assertNull(rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun onlyInstalledAppsCanBeLimited() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", "com.android.settings", rev = 1)
        assertEquals(setOf("com.game.one"), rig.phone.saved!!.limited)
        rig.host.close()
    }

    @Test
    fun theChildCanAskAgainAfterFifteenMinutesWithoutAnAnswer() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        p.request("timer.start", minutes(1), withRid = true)
        rig.play("com.game.one", 61)
        assertEquals(AskResult.Sent, rig.backend.askForMoreTime())
        assertTrue(rig.backend.asked)
        rig.play(null, 15 * 60)
        assertFalse(rig.backend.asked)
        assertEquals(AskResult.Sent, rig.backend.askForMoreTime())
        // Unblocked another way (a new timer): the request goes away
        p.request("timer.stop", null, withRid = true)
        assertTrue(p.state()["request"] is kotlinx.serialization.json.JsonNull)
        rig.host.close()
    }

    @Test
    fun scheduleValidationIsStrict() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        val bad = buildJsonArray { repeat(7) { add(buildJsonObject { put("allowed", "yes"); put("limitMinutes", 0); put("windowStartMinutes", 0); put("windowEndMinutes", 1440) }) } }
        assertEquals("invalid_schedule", p.request("schedule.put", buildJsonObject { put("enabled", true); put("week", bad) }, withRid = true).s("code"))
        assertEquals("invalid_schedule", p.request("schedule.put", buildJsonObject { put("enabled", true); put("week", week(2000)) }, withRid = true).s("code"))
        assertEquals("invalid_time", p.request("timer.start", buildJsonObject { put("mode", "interval"); put("hours", 99); put("minutes", 0) }, withRid = true).s("code"))
        rig.host.close()
    }

    @Test
    fun theScheduleGoesByTheCountedDayNotTheClock() = runTest {
        val rig = Rig(this)
        val p = rig.parent()
        p.limit("com.game.one", rev = 1)
        // Saturday no games, Monday 120 minutes, 13:00–20:00 on other days; it is Saturday 12:00
        val w = buildJsonArray {
            repeat(7) { i ->
                add(buildJsonObject {
                    put("allowed", i != 6); put("limitMinutes", if (i == 1) 120 else 30)
                    put("windowStartMinutes", if (i == 1) 0 else 13 * 60); put("windowEndMinutes", 1200)
                })
            }
        }
        p.schedule(w)
        rig.play(null, 1)
        assertEquals("dayDisabled", p.state().obj("schedule").s("status"))
        rig.phone.epoch += 2 * 86_400_000L // the child picks Monday
        rig.play("com.game.one", 5)
        assertEquals("dayDisabled", p.state().obj("schedule").s("status"))
        assertEquals(BlockReason.NotNow, rig.backend.blockReason("com.game.one"))
        rig.host.close()
    }

    @Test
    fun aRestartInTheSameBootKeepsTheDay() = runTest {
        val phone = FakePhone().apply { boot = 7 }
        val first = Rig(this, phone)
        val p = first.parent()
        p.limit("com.game.one", rev = 1)
        p.schedule(week(30))
        first.play("com.game.one", 30 * 60 + 1)
        first.backend.saveNow()
        first.host.close()
        phone.epoch += 86_400_000L // the date moved, then the app restarted
        phone.stored = phone.saved
        val second = PlayBackend(AgentInfo("x", "android", "t"), phone)
        second.tick("com.game.one")
        assertEquals(BlockReason.TimeUp, second.blockReason("com.game.one"))
    }

    @Test
    fun weekdaysOfDates() {
        assertEquals(6, weekdayOf("2026-10-24"))
        assertEquals(4, weekdayOf("1970-01-01"))
        assertEquals(2, weekdayOf("2000-02-29"))
        assertNull(weekdayOf("nope"))
        assertEquals("2026-03-01", nextDate("2026-02-28"))
        assertEquals("2024-02-29", nextDate("2024-02-28"))
        assertEquals("2027-01-01", nextDate("2026-12-31"))
    }

    @Test
    fun scheduleRulesMatchThePcEvaluator() {
        val sat = LocalNow(0, "2026-10-24", 10 * 3600, 6, "")
        val week = List(7) { DayRule(true, 60, 9 * 60, 21 * 60) }
        val s = WeekSchedule(true, week)
        assertEquals(ScheduleStatus.Allowed, ScheduleRules.evaluate(sat, s, 0, false).status)
        assertEquals(3_600_000L, ScheduleRules.evaluate(sat, s, 0, false).budgetLeftMs)
        assertEquals(ScheduleStatus.LimitReached, ScheduleRules.evaluate(sat, s, 3_600_000L, false).status)
        assertEquals(ScheduleStatus.OutsideWindow, ScheduleRules.evaluate(sat.copy(secondOfDay = 8 * 3600), s, 0, false).status)
        assertEquals(ScheduleStatus.SkippedToday, ScheduleRules.evaluate(sat, s, 0, true).status)
        assertEquals(ScheduleStatus.Off, ScheduleRules.evaluate(sat, s.copy(enabled = false), 0, false).status)
        // a reversed window counts as all day; the budget near the window's end is the time to the end
        val late = sat.copy(secondOfDay = 20 * 3600 + 50 * 60)
        assertEquals(600_000L, ScheduleRules.evaluate(late, s, 0, false).availableMs)
        assertEquals(ScheduleStatus.Allowed, ScheduleRules.evaluate(sat.copy(secondOfDay = 3600), WeekSchedule(true, List(7) { DayRule(true, 0, 600, 300) }), 0, false).status)
    }
}
