using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Noise;
using Microsoft.Extensions.Time.Testing;
using Xunit;

namespace GameTimer.Protocol.Tests.Agent;

/// <summary>/v2 sessions: handshake, the ready rule, hello, limits and timeouts (protocol §3, §4, §6).</summary>
public class SessionTests
{
    [Fact]
    public async Task Hello_after_ready()
    {
        using var f = new AgentFixture();
        var (key, pairResult) = await f.PairAsync();
        var (hub, hello) = await TestHub.ConnectAsync(f.Host, key);

        Assert.Equal(2, hello.GetProperty("protocol").GetInt32());
        Assert.Equal(0, hello.GetProperty("minor").GetInt32());
        Assert.Equal(hub.Id, hello.GetProperty("you").GetProperty("id").GetString());
        Assert.Equal("admin", hello.GetProperty("you").GetProperty("role").GetString());
        Assert.Equal(pairResult.GetProperty("ak").GetString(), hello.GetProperty("ak").GetString());
        var caps = hello.GetProperty("capabilities").EnumerateArray().Select(c => c.GetString()).ToList();
        Assert.Equal(new[] { "timer", "settings", "peers", "push" }, caps);
        Assert.Equal("notStarted", hello.GetProperty("state").GetProperty("timer").GetProperty("state").GetString());
        Assert.NotNull(f.Host.Peers[0].LastSeen);
    }

    [Fact]
    public async Task Unknown_hub_key_gets_no_message_2()
    {
        using var f = new AgentFixture();
        await f.PairAsync();
        var (_, gotMsg2) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), pairing: false);
        Assert.False(gotMsg2);
    }

    [Fact]
    public async Task Message_1_must_be_connect()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (_, gotMsg2) = await TestHub.HandshakeAsync(f.Host, key, pairing: false, firstType: "timer.stop");
        Assert.False(gotMsg2);
    }

    [Fact]
    public async Task First_transport_message_must_be_ready()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, ok) = await TestHub.HandshakeAsync(f.Host, key, pairing: false);
        Assert.True(ok);
        await hub.SendJsonAsync(new JsonObject { ["t"] = "timer.extend", ["id"] = 1, ["rid"] = TestHub.NewRid(), ["minutes"] = 5 });
        Assert.Null(await hub.TryReceiveJsonAsync());
        Assert.Equal(0, f.Backend.Calls);
    }

    [Fact]
    public async Task Replayed_message_1_and_ready_never_reach_the_backend_or_evict_sessions()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (real, _) = await TestHub.ConnectAsync(f.Host, key);
        await real.RequestAsync("timer.extend", new JsonObject { ["minutes"] = 5 }, withRid: true);
        var lastSeen = f.Host.Peers[0].LastSeen;

        // An attacker recorded msg 1 + ready + the extend request and replays them, more times than the per-hub limit
        for (int i = 0; i < 3; i++)
        {
            var (replay, gotMsg2) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), pairing: false,
                ip: "192.168.1.66", replayMsg1: real.Msg1);
            Assert.True(gotMsg2); // Noise accepts a replayed IK msg 1...
            await replay.End.SendAsync(real.FirstTransport!, default); // ...but the old "ready" doesn't decrypt
            Assert.Null(await replay.End.ReceiveAsync(TestHub.Timeout()));
            await replay.Run;
        }

        Assert.Equal(1, f.Backend.Extends);
        Assert.Equal(lastSeen, f.Host.Peers[0].LastSeen);
        var pong = await real.RequestAsync("state.get"); // the real session was not evicted
        Assert.Equal("result", pong.GetProperty("t").GetString());
    }

    [Fact]
    public async Task Ping_pong()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        await hub.SendJsonAsync(new JsonObject { ["t"] = "ping" });
        Assert.Equal("pong", (await hub.ReceiveJsonAsync()).GetProperty("t").GetString());
    }

    [Fact]
    public async Task Third_session_of_a_hub_closes_its_oldest()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (s1, _) = await TestHub.ConnectAsync(f.Host, key);
        var (s2, _) = await TestHub.ConnectAsync(f.Host, key);
        var (s3, _) = await TestHub.ConnectAsync(f.Host, key);

        Assert.Equal("busy", (await s1.ReceiveJsonAsync()).GetProperty("code").GetString());
        Assert.Equal("result", (await s2.RequestAsync("state.get")).GetProperty("t").GetString());
        Assert.Equal("result", (await s3.RequestAsync("state.get")).GetProperty("t").GetString());
    }

    [Fact]
    public void Admission_limits_pending_handshakes()
    {
        using var f = new AgentFixture();
        var a1 = f.Host.TryAdmit("10.0.0.1");
        var a2 = f.Host.TryAdmit("10.0.0.1");
        Assert.NotNull(a1);
        Assert.NotNull(a2);
        Assert.Null(f.Host.TryAdmit("10.0.0.1")); // 2 per IP

        var others = Enumerable.Range(2, 6).Select(i => f.Host.TryAdmit("10.0.0." + i)).ToList();
        Assert.All(others, Assert.NotNull);
        Assert.Null(f.Host.TryAdmit("10.0.0.99")); // 8 in total

        a1!.Dispose();
        a1.Dispose(); // twice is harmless
        Assert.NotNull(f.Host.TryAdmit("10.0.0.99"));
    }

    [Fact]
    public async Task Handshake_times_out_after_5_seconds()
    {
        var time = new FakeTimeProvider();
        using var f = new AgentFixture(time);
        var (key, _) = await f.PairAsync();
        var (hub, ok) = await TestHub.HandshakeAsync(f.Host, key, pairing: false);
        Assert.True(ok);
        // no ready
        time.Advance(TimeSpan.FromSeconds(6));
        Assert.Null(await hub.End.ReceiveAsync(TestHub.Timeout()));
        await hub.Run.WaitAsync(TimeSpan.FromSeconds(10));
    }

    [Fact]
    public async Task Idle_session_is_closed_after_90_seconds()
    {
        var time = new FakeTimeProvider();
        using var f = new AgentFixture(time);
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        time.Advance(TimeSpan.FromSeconds(60));
        await hub.SendJsonAsync(new JsonObject { ["t"] = "ping" }); // activity resets the idle timer
        Assert.Equal("pong", (await hub.ReceiveJsonAsync()).GetProperty("t").GetString());
        await Task.Delay(50);
        time.Advance(TimeSpan.FromSeconds(60));
        await Task.Delay(50);
        Assert.False(hub.End.IsClosed);
        time.Advance(TimeSpan.FromSeconds(31));
        Assert.Null(await hub.TryReceiveJsonAsync());
    }

    [Fact]
    public async Task Session_expires_after_24_hours()
    {
        var time = new FakeTimeProvider();
        using var f = new AgentFixture(time);
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        for (int i = 0; i < 24 * 60 - 1; i++) // keep it alive with a ping every minute
        {
            time.Advance(TimeSpan.FromMinutes(1));
            await hub.SendJsonAsync(new JsonObject { ["t"] = "ping" });
            Assert.Equal("pong", (await hub.ReceiveJsonAsync()).GetProperty("t").GetString());
            await Task.Yield();
        }
        await Task.Delay(50);
        time.Advance(TimeSpan.FromMinutes(1));
        var bye = await hub.ReceiveJsonAsync();
        Assert.Equal("bye", bye.GetProperty("t").GetString());
        Assert.Equal("expired", bye.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Identity_and_peers_survive_a_restart()
    {
        var store = new MemoryAgentStore();
        byte[] agentKey;
        KeyPair hubKey;
        using (var f = new AgentFixture(store: store))
        {
            (hubKey, _) = await f.PairAsync();
            agentKey = f.Host.AgentPublicKey;
        }
        using var g = new AgentFixture(store: store);
        Assert.Equal(agentKey, g.Host.AgentPublicKey);
        var (_, hello) = await TestHub.ConnectAsync(g.Host, hubKey);
        Assert.Equal("admin", hello.GetProperty("you").GetProperty("role").GetString());
    }

    [Fact]
    public async Task Shutdown_says_bye()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        await f.Host.ShutdownAsync();
        Assert.Equal("shutdown", (await hub.ReceiveJsonAsync()).GetProperty("code").GetString());
    }
}
