package com.gametimer.protocol.agent

import com.gametimer.protocol.noise.TestSupport
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assume

/**
 * C# ↔ Kotlin: the C# hub (tools/GameTimer.TestHub = gt-testhub, on the C# protocol core) pairs with and
 * controls the Kotlin AgentHost over real WebSockets, and its replay test must pass against it.
 * Gradle passes -PcsharpHub=<gt-testhub.dll> (CI always does); without it the test is skipped locally.
 */
class CsharpHubInteropTests {
    private lateinit var agent: TestAgentServer
    private lateinit var dir: File
    private lateinit var dll: String

    @BeforeTest
    fun start() {
        val hub = System.getProperty("csharpHub")
        if (hub == null) check(System.getenv("CI") != "true") { "CI run without -PcsharpHub" }
        Assume.assumeTrue("csharpHub not set: C# hub interop skipped", hub != null)
        check(File(hub!!).isFile) { "csharpHub not found: $hub" }
        dll = hub
        TestSupport.initSodium()
        agent = TestAgentServer()
        dir = Files.createTempDirectory("gt-testhub").toFile() // gt-testhub keeps its state in the current folder
    }

    @AfterTest
    fun stop() {
        if (::agent.isInitialized) agent.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private class Run(val exit: Int, val out: String)

    /** Runs one gt-testhub command; [onLine] sees stdout lines as they come. */
    private fun hub(vararg args: String, onLine: (String) -> Unit = {}): Run {
        val p = ProcessBuilder(listOf("dotnet", dll) + args).directory(dir).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            p.inputStream.bufferedReader().forEachLine { line -> synchronized(out) { out.appendLine(line) }; onLine(line) }
        }.also { it.start() }
        check(p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); "gt-testhub ${args.toList()} hung: $out" }
        reader.join(5_000)
        return Run(p.exitValue(), synchronized(out) { out.toString() })
    }

    /** The JSON gt-testhub printed (indented, possibly after other lines). */
    private fun printed(run: Run): JsonObject = Json.parseToJsonElement(run.out.substring(run.out.indexOf('{'), run.out.lastIndexOf('}') + 1)).jsonObject

    @Test
    fun theCsharpHubPairsControlsAndCannotReplay() {
        // Pairing: the hub prints the code it shows, the "owner" types it on the device
        val pairing = hub("pair", agent.link(), "C# hub") { line ->
            if (line.startsWith("Enter this code on the PC: ")) agent.ui.answer(line.substringAfterLast(' ').trim())
        }
        assertEquals(0, pairing.exit, pairing.out)
        assertTrue("Paired as admin" in pairing.out || "Paired as \"admin\"" in pairing.out, pairing.out)
        assertEquals("C# hub", agent.host.peerList.single().name)

        // A session: gt-testhub checks hello.agent.id against the pinned key (ids derived alike in both languages)
        val status = hub("status")
        assertEquals(0, status.exit, status.out)
        val hello = printed(status)
        assertEquals("hello", hello.s("t"))
        assertEquals(agent.host.agentId, hello.obj("agent").s("id"))
        assertEquals("admin", hello.obj("you").s("role"))

        // Requests through the C# hub, executed by the Kotlin agent
        val started = hub("start", "30")
        assertEquals("result", printed(started).s("t"), started.out)
        assertEquals("running", printed(started).obj("state").obj("timer").s("state"))
        assertEquals("result", printed(hub("extend", "5")).s("t"))
        assertEquals("result", printed(hub("stop")).s("t"))
        assertEquals("not_running", printed(hub("stop")).s("code"))
        val peers = printed(hub("peers"))
        assertEquals("true", (peers["peers"] as kotlinx.serialization.json.JsonArray)[0].jsonObject.s("current"))
        val history = printed(hub("history"))
        assertEquals("stopped", (history["events"] as kotlinx.serialization.json.JsonArray)[0].jsonObject.s("type"))

        // A recorded msg 1 + ready, replayed, never becomes a session (§4.3)
        val replay = hub("replay-test")
        assertEquals(0, replay.exit, replay.out)
        assertTrue("PASS" in replay.out, replay.out)
    }

    @Test
    fun aWrongCodeOnTheDeviceDeniesTheCsharpHub() {
        val pairing = hub("pair", agent.link(), "C# hub") { line ->
            if (line.startsWith("Enter this code on the PC: ")) {
                val code = line.substringAfterLast(' ').trim()
                agent.ui.answer(if (code == "000000") "000001" else "000000")
            }
        }
        assertTrue("\"denied\"" in pairing.out, pairing.out)
        assertTrue(agent.host.peerList.isEmpty())
    }
}
