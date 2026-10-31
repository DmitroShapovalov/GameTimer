using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Noise;
using GameTimer.Protocol.Relay;
using GameTimer.Protocol.Tests.Agent;
using GameTimer.Relay;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>
/// The agent behind the real relay (in memory): RelayClient registers, hubs dial their routes, and the
/// sessions are the usual Noise sessions (§10). Also the relay pool and route ↔ key binding of §10.4.
/// </summary>
public class RelayClientTests
{
    private const string IpHeader = "X-Test-Client-IP";

    private sealed class Rig : IAsyncDisposable
    {
        public required WebApplication App { get; init; }
        public required TestServer Server { get; init; }
        public required AgentFixture Agent { get; init; }
        public required RelayClient Client { get; init; }
        public required RecordingPush Push { get; init; }

        public async ValueTask DisposeAsync()
        {
            await Client.StopAsync();
            Agent.Dispose();
            await App.StopAsync();
            await App.DisposeAsync();
        }

        public Task<WebSocket> DialAsync(string path, string ip = "10.0.0.2")
        {
            var c = Server.CreateWebSocketClient();
            c.ConfigureRequest = r => r.Headers[IpHeader] = ip;
            return c.ConnectAsync(new Uri(Server.BaseAddress, path), CancellationToken.None);
        }

        /// <summary>Dials the hub's route until the relay knows it (the agent registers in the background).</summary>
        public async Task<WebSocket> DialHubAsync(byte[] ak)
        {
            var route = RouteToken.Derive(ak, Agent.Host.UtcToday);
            for (int i = 0; ; i++)
            {
                try { return await DialAsync("v2/hub/" + route); }
                catch (InvalidOperationException) when (i < 100) { await Task.Delay(50); }
            }
        }
    }

    private static async Task<Rig> StartAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        RelayApp.ConfigureServices(builder);
        var push = new RecordingPush();
        builder.Services.AddSingleton<IPushSender>(push);
        builder.Services.PostConfigure<RelayOptions>(o => { o.ClientIpHeader = IpHeader; o.WakeAfterConnected = TimeSpan.Zero; o.WakeSameTokenEvery = TimeSpan.FromMilliseconds(100); });
        var app = builder.Build();
        RelayApp.Map(app);
        await app.StartAsync();
        var server = app.GetTestServer();
        var agent = new AgentFixture();
        agent.Host.RemoteAccess = true;
        var client = new RelayClient(agent.Host, new Uri(server.BaseAddress, "v2/agent"), connect: async (uri, ct) =>
        {
            var c = server.CreateWebSocketClient();
            c.ConfigureRequest = r => r.Headers[IpHeader] = "10.0.0.1";
            return await c.ConnectAsync(uri, ct);
        });
        return new Rig { App = app, Server = server, Agent = agent, Client = client, Push = push };
    }

    private static byte[] Ak(JsonElement pairResult) => Encodings.Base64Url.TryDecode(pairResult.GetProperty("ak").GetString())!;

    // ---------------- a minimal hub over a WebSocket ----------------

    private sealed class Hub
    {
        public required WebSocket Ws { get; init; }
        public CipherState? Send, Recv;

        public Task SendAsync(byte[] f) => Ws.SendAsync(f, WebSocketMessageType.Binary, true, CancellationToken.None);

        /// <summary>The next binary message, or null when the socket closes (the close is answered).</summary>
        public async Task<byte[]?> ReceiveAsync(double seconds = 10)
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(seconds));
            var buf = new byte[70_000];
            int len = 0;
            while (true)
            {
                var r = await Ws.ReceiveAsync(new ArraySegment<byte>(buf, len, buf.Length - len), cts.Token);
                if (r.MessageType == WebSocketMessageType.Close)
                {
                    try { await Ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None); } catch { }
                    return null;
                }
                len += r.Count;
                if (r.EndOfMessage) return buf[..len];
            }
        }

        public Task SendJsonAsync(JsonObject o) => SendAsync(Send!.Encrypt(Encoding.UTF8.GetBytes(o.ToJsonString())));

        public async Task<JsonElement?> ReceiveJsonAsync()
        {
            var f = await ReceiveAsync();
            return f == null ? null : JsonDocument.Parse(Recv!.Decrypt(f)).RootElement.Clone();
        }

        /// <summary>msg 1 → msg 2; false if the agent closed without msg 2.</summary>
        public async Task<bool> HandshakeAsync(KeyPair key, byte[] agentKey)
        {
            var hs = new HandshakeState(NoisePattern.IK, true, AgentHost.Prologue, key, agentKey);
            await SendAsync(hs.WriteMessage("{\"t\":\"connect\"}"u8));
            var msg2 = await ReceiveAsync();
            if (msg2 == null) return false;
            hs.ReadMessage(msg2);
            (Send, Recv) = hs.Split();
            return true;
        }

        public async Task<JsonElement> ReadyAsync()
        {
            await SendJsonAsync(new JsonObject { ["t"] = "ready", ["protocol"] = 2, ["minor"] = 1, ["app"] = "test" });
            var hello = await ReceiveJsonAsync();
            Assert.NotNull(hello);
            Assert.Equal("hello", hello!.Value.GetProperty("t").GetString());
            return hello.Value;
        }
    }

    [Fact]
    public async Task A_hub_controls_the_agent_through_the_relay()
    {
        await using var rig = await StartAsync();
        var (key, pair) = await rig.Agent.PairAsync("Away");
        var started = new List<string>();
        rig.Agent.Host.SessionStarted += ip => { lock (started) started.Add(ip); };
        rig.Client.Start();

        var hub = new Hub { Ws = await rig.DialHubAsync(Ak(pair)) };
        Assert.True(await hub.HandshakeAsync(key, rig.Agent.Host.AgentPublicKey));
        var hello = await hub.ReadyAsync();
        Assert.True(hello.GetProperty("remoteAccess").GetBoolean());
        Assert.Equal(RelayStatus.Connected, rig.Client.Status);

        await hub.SendJsonAsync(new JsonObject { ["t"] = "state.get", ["id"] = 1 });
        var reply = await hub.ReceiveJsonAsync();
        Assert.Equal("result", reply!.Value.GetProperty("t").GetString());

        // The same parent at home on the LAN at the same time
        var (lan, _) = await TestHub.ConnectAsync(rig.Agent.Host, key);
        for (int i = 0; i < 100 && started.Count == 0; i++) await Task.Delay(10); // raised just after hello
        lock (started) Assert.Equal(new[] { "192.168.1.20" }, started); // only the LAN session marks the network (§9)

        // "Control from anywhere" turned off: the relayed session ends with bye remote_off, the LAN one stays
        rig.Agent.Host.RemoteAccess = false;
        var ending = rig.Agent.Host.EndRelaySessionsAsync();
        var bye = await hub.ReceiveJsonAsync();
        Assert.Equal("bye", bye!.Value.GetProperty("t").GetString());
        Assert.Equal("remote_off", bye.Value.GetProperty("code").GetString());
        await ending;
        Assert.Null(await hub.ReceiveAsync());
        var state = await lan.RequestAsync("state.get");
        Assert.Equal("result", state.GetProperty("t").GetString());
        // …and no new relayed stream is taken
        Assert.Null(rig.Agent.Host.TryAdmitRelay(Ids.HubId(key.PublicKey)));
    }

    [Fact]
    public async Task A_route_only_works_for_its_own_hub_key()
    {
        await using var rig = await StartAsync();
        var (keyA, pairA) = await rig.Agent.PairAsync("A");
        var (keyB, _) = await rig.Agent.PairAsync("B");
        rig.Client.Start();
        // B (paired, but not the owner of A's route) dials A's route: refused before msg 2
        var hub = new Hub { Ws = await rig.DialHubAsync(Ak(pairA)) };
        Assert.False(await hub.HandshakeAsync(keyB, rig.Agent.Host.AgentPublicKey));
        // A itself gets through
        var own = new Hub { Ws = await rig.DialHubAsync(Ak(pairA)) };
        Assert.True(await own.HandshakeAsync(keyA, rig.Agent.Host.AgentPublicKey));
        await own.ReadyAsync();
    }

    [Fact]
    public async Task A_silent_relay_stream_gives_its_slot_back_after_2_seconds()
    {
        await using var rig = await StartAsync();
        var (_, pair) = await rig.Agent.PairAsync("A");
        rig.Client.Start();
        var hub = new Hub { Ws = await rig.DialHubAsync(Ak(pair)) };
        var started = DateTime.UtcNow;
        Assert.Null(await hub.ReceiveAsync(seconds: 10)); // no msg 1: the agent closes the stream
        var took = DateTime.UtcNow - started;
        Assert.InRange(took.TotalSeconds, 1.5, 4.5);
        for (int i = 0; i < 50 && rig.Agent.Host.RelayPendingCount > 0; i++) await Task.Delay(20);
        Assert.Equal(0, rig.Agent.Host.RelayPendingCount);
    }

    [Fact]
    public async Task Revoking_a_hub_withdraws_its_route_and_stopping_withdraws_all()
    {
        await using var rig = await StartAsync();
        var (_, pairA) = await rig.Agent.PairAsync("A");
        var (_, pairB) = await rig.Agent.PairAsync("B");
        rig.Client.Start();
        (await rig.DialHubAsync(Ak(pairA))).Abort();
        var idA = rig.Agent.Host.Peers.First(p => p.Name == "A").Id;
        Assert.True(rig.Agent.Host.RevokeByOwner(idA));
        var routeA = RouteToken.Derive(Ak(pairA), rig.Agent.Host.UtcToday);
        Assert.True(await GoneAsync(rig, routeA));
        (await rig.DialHubAsync(Ak(pairB))).Abort(); // B is still there

        await rig.Client.StopAsync();
        Assert.Equal(RelayStatus.Off, rig.Client.Status);
        Assert.True(await GoneAsync(rig, RouteToken.Derive(Ak(pairB), rig.Agent.Host.UtcToday)));
    }

    private static async Task<bool> GoneAsync(Rig rig, string route)
    {
        for (int i = 0; i < 100; i++)
        {
            try { (await rig.DialAsync("v2/hub/" + route, "10.0.0." + (10 + i % 200))).Abort(); }
            catch (InvalidOperationException e) when (e.Message.Contains("404")) { return true; }
            await Task.Delay(50);
        }
        return false;
    }

    /// <summary>
    /// Against the deployed relay (set GT_RELAY_E2E=wss://host to run; skipped otherwise): a real
    /// ClientWebSocket on both sides, TLS and the hosting proxy included.
    /// </summary>
    [Fact]
    public async Task Through_the_deployed_relay_when_asked()
    {
        var relay = Environment.GetEnvironmentVariable("GT_RELAY_E2E");
        if (string.IsNullOrEmpty(relay)) return;
        using var agent = new AgentFixture();
        agent.Host.RemoteAccess = true;
        var (key, pair) = await agent.PairAsync("E2E");
        await using var client = new RelayClient(agent.Host, new Uri(relay.TrimEnd('/') + "/v2/agent"));
        client.Start();
        var route = RouteToken.Derive(Ak(pair), agent.Host.UtcToday);
        ClientWebSocket? ws = null;
        for (int i = 0; i < 60 && ws == null; i++)
        {
            var c = new ClientWebSocket();
            try { await c.ConnectAsync(new Uri(relay.TrimEnd('/') + "/v2/hub/" + route), CancellationToken.None); ws = c; }
            catch (WebSocketException) { c.Dispose(); await Task.Delay(500); }
        }
        Assert.NotNull(ws);
        var hub = new Hub { Ws = ws! };
        Assert.True(await hub.HandshakeAsync(key, agent.Host.AgentPublicKey));
        var hello = await hub.ReadyAsync();
        Assert.True(hello.GetProperty("remoteAccess").GetBoolean());
        await hub.SendJsonAsync(new JsonObject { ["t"] = "state.get", ["id"] = 1 });
        Assert.Equal("result", (await hub.ReceiveJsonAsync())!.Value.GetProperty("t").GetString());
        ws!.Abort();
    }

    public sealed class RecordingPush : IPushSender
    {
        private readonly System.Collections.Concurrent.ConcurrentQueue<string> _q = new();
        public bool TryWake(string pushToken) { _q.Enqueue(pushToken); return true; }
        public IReadOnlyList<string> Tokens => _q.ToList();
    }

    [Fact]
    public async Task Push_set_stores_the_token_and_events_wake_hubs_without_a_session()
    {
        await using var rig = await StartAsync();
        var (keyA, _) = await rig.Agent.PairAsync("A");
        var (keyB, _) = await rig.Agent.PairAsync("B");
        var (keyC, _) = await rig.Agent.PairAsync("C");
        rig.Client.Start();
        for (int i = 0; i < 100 && rig.Client.Status != RelayStatus.Connected; i++) await Task.Delay(20);
        static JsonObject Hist(string type, string source = "phone", string? hub = null) =>
            new() { ["event"] = new JsonObject { ["type"] = type, ["source"] = source, ["hub"] = hub } };

        var (a, helloA) = await TestHub.ConnectAsync(rig.Agent.Host, keyA);
        Assert.Contains("push", helloA.GetProperty("capabilities").EnumerateArray().Select(c => c.GetString()));
        Assert.Equal("result", (await a.RequestAsync("push.set", new JsonObject { ["token"] = "token-A" }, withRid: true)).GetProperty("t").GetString());
        Assert.Equal("bad_request", (await a.RequestAsync("push.set", new JsonObject { ["token"] = "has space" }, withRid: true)).GetProperty("code").GetString());
        Assert.Equal("bad_request", (await a.RequestAsync("push.set", new JsonObject { ["token"] = 5 }, withRid: true)).GetProperty("code").GetString());
        Assert.Equal("bad_request", (await a.RequestAsync("push.set", new JsonObject { ["token"] = "t", ["events"] = new JsonArray("a-b") }, withRid: true)).GetProperty("code").GetString());
        // B wants only "time's up"; C everything
        var (b, _) = await TestHub.ConnectAsync(rig.Agent.Host, keyB);
        await b.RequestAsync("push.set", new JsonObject { ["token"] = "token-B", ["events"] = new JsonArray("expired") }, withRid: true);
        var (c, _) = await TestHub.ConnectAsync(rig.Agent.Host, keyC);
        await c.RequestAsync("push.set", new JsonObject { ["token"] = "token-C" }, withRid: true);
        Assert.Equal("token-A", rig.Agent.Store.State!.Peers.Single(p => p.Name == "A").PushToken); // persisted
        var peers = await a.RequestAsync("peers.list");
        Assert.DoesNotContain("token-", peers.GetRawText()); // never shown to other hubs

        // A stays connected (it sees the events itself); B and C go away
        await b.End.CloseAsync();
        await c.End.CloseAsync();
        for (int i = 0; i < 100 && rig.Agent.Host.LiveSessionCount > 1; i++) await Task.Delay(20);

        async Task<List<string>> WokenBy(Action publish)
        {
            int before = rig.Push.Tokens.Count;
            publish();
            await Task.Delay(400);
            return rig.Push.Tokens.Skip(before).ToList();
        }
        // Someone stopped the timer: C (all kinds) — not B (only expired), not A (online)
        Assert.Equal(new[] { "token-C" }, await WokenBy(() => rig.Agent.Host.Publish("ev.history", Hist("stopped"))));
        // Automatic events (source system) wake nobody, alerts neither (§10.6)
        Assert.Empty(await WokenBy(() => rig.Agent.Host.Publish("ev.history", Hist("started", "system"))));
        Assert.Empty(await WokenBy(() => rig.Agent.Host.Publish("ev.alert", new JsonObject { ["minutes"] = 5 })));
        // Time's up: B now; C not yet (30 s since its last wake-up; a trailing one would follow)
        Assert.Equal(new[] { "token-B" }, await WokenBy(() => rig.Agent.Host.Publish("ev.history", Hist("expired", "system"))));
        // Nothing at all while remote access is off
        rig.Agent.Host.RemoteAccess = false;
        Assert.Empty(await WokenBy(() => rig.Agent.Host.Publish("ev.history", Hist("closed", "system"))));
        rig.Agent.Host.RemoteAccess = true;
        // The hub that caused an event isn't woken for it
        var hubC = rig.Agent.Host.Peers.Single(p => p.Name == "C").Id;
        await Task.Delay(100);
        Assert.Empty((await WokenBy(() => rig.Agent.Host.Publish("ev.history", Hist("extended", hub: hubC)))).Where(t => t == "token-C"));

        await a.RequestAsync("push.set", new JsonObject { ["token"] = null }, withRid: true);
        Assert.Null(rig.Agent.Store.State!.Peers.Single(p => p.Name == "A").PushToken);
    }

    [Fact]
    public async Task A_held_back_wake_up_is_sent_only_if_the_hub_did_not_connect_since()
    {
        var saved = RelayClient.WakeEvery;
        RelayClient.WakeEvery = TimeSpan.FromSeconds(1.5);
        try
        {
            await using var rig = await StartAsync();
            var (keyB, _) = await rig.Agent.PairAsync("B");
            rig.Client.Start();
            for (int i = 0; i < 100 && rig.Client.Status != RelayStatus.Connected; i++) await Task.Delay(20);
            var (b, _) = await TestHub.ConnectAsync(rig.Agent.Host, keyB);
            await b.RequestAsync("push.set", new JsonObject { ["token"] = "token-B" }, withRid: true);
            await b.End.CloseAsync();
            for (int i = 0; i < 100 && rig.Agent.Host.LiveSessionCount > 0; i++) await Task.Delay(20);
            JsonObject Stopped() => new() { ["event"] = new JsonObject { ["type"] = "stopped", ["source"] = "pc" } };

            rig.Agent.Host.Publish("ev.history", Stopped()); // wakes at once
            rig.Agent.Host.Publish("ev.history", Stopped()); // held back
            await Task.Delay(200);
            Assert.Single(rig.Push.Tokens);
            // The phone connects (caught up both) and goes again before the window ends: no trailing wake-up
            var (b2, _) = await TestHub.ConnectAsync(rig.Agent.Host, keyB);
            await b2.End.CloseAsync();
            await Task.Delay(2_500);
            Assert.Single(rig.Push.Tokens);
            // Another event held back with no session since: the trailing wake-up goes out
            rig.Agent.Host.Publish("ev.history", Stopped()); // window over: at once
            rig.Agent.Host.Publish("ev.history", Stopped()); // held back
            for (int i = 0; i < 200 && rig.Push.Tokens.Count < 3; i++) await Task.Delay(20);
            Assert.Equal(3, rig.Push.Tokens.Count);
        }
        finally { RelayClient.WakeEvery = saved; }
    }

    [Fact]
    public async Task A_new_parent_app_wakes_the_others()
    {
        await using var rig = await StartAsync();
        var (keyB, _) = await rig.Agent.PairAsync("B");
        rig.Client.Start();
        for (int i = 0; i < 100 && rig.Client.Status != RelayStatus.Connected; i++) await Task.Delay(20);
        var (b, _) = await TestHub.ConnectAsync(rig.Agent.Host, keyB);
        await b.RequestAsync("push.set", new JsonObject { ["token"] = "token-B", ["events"] = new JsonArray("peerAdded") }, withRid: true);
        await b.End.CloseAsync();
        for (int i = 0; i < 100 && rig.Agent.Host.LiveSessionCount > 0; i++) await Task.Delay(20);
        await rig.Agent.PairAsync("Child's phone?");
        for (int i = 0; i < 100 && rig.Push.Tokens.Count == 0; i++) await Task.Delay(20);
        Assert.Equal(new[] { "token-B" }, rig.Push.Tokens);
    }

    [Fact]
    public async Task Replayed_msg_1_holds_at_most_two_streams_per_hub()
    {
        using var f = new AgentFixture();
        f.Host.RemoteAccess = true;
        var (key, _) = await f.PairAsync("A");
        var hubId = Ids.HubId(key.PublicKey);
        // The relay saw one real msg 1 and replays it on new streams that never send ready
        var msg1 = new HandshakeState(NoisePattern.IK, true, AgentHost.Prologue, key, f.Host.AgentPublicKey)
            .WriteMessage("{\"t\":\"connect\"}"u8);
        async Task<bool> GetsMsg2()
        {
            var (agentEnd, hubEnd) = MemoryTransport.Pair();
            _ = f.Host.RunAsync(agentEnd, f.Host.TryAdmitRelay(hubId)!, pairing: false, CancellationToken.None);
            await hubEnd.SendAsync(msg1, default);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            return await hubEnd.ReceiveAsync(cts.Token) != null;
        }
        Assert.True(await GetsMsg2());
        Assert.True(await GetsMsg2());
        Assert.False(await GetsMsg2()); // the third waits for nothing: refused
        Assert.Equal(0, f.Host.RelayPendingCount); // and none of them sits in the evictable pool
    }

    [Fact]
    public async Task The_relay_pool_is_apart_and_evicts_the_oldest()
    {
        using var f = new AgentFixture();
        f.Host.RemoteAccess = true;
        var lan = Enumerable.Range(0, AgentHost.MaxPendingHandshakes / AgentHost.MaxPendingPerIp)
            .SelectMany(i => Enumerable.Range(0, AgentHost.MaxPendingPerIp).Select(_ => f.Host.TryAdmit("192.168.1." + i)))
            .ToList();
        Assert.All(lan, t => Assert.NotNull(t));
        Assert.Null(f.Host.TryAdmit("192.168.1.99")); // the LAN pool is full…
        var a1 = f.Host.TryAdmitRelay("A")!;          // …the relay pool is not
        var a2 = f.Host.TryAdmitRelay("A")!;
        var a3 = f.Host.TryAdmitRelay("A")!;           // 2 per hub: A's oldest is evicted
        Assert.True(a1.Evicted.IsCancellationRequested);
        Assert.False(a2.Evicted.IsCancellationRequested);
        var b1 = f.Host.TryAdmitRelay("B")!;
        var c1 = f.Host.TryAdmitRelay("C")!;           // a2, a3, b1, c1: the pool is full
        Assert.Equal(AgentHost.MaxRelayPending, f.Host.RelayPendingCount);
        Assert.False(a2.Evicted.IsCancellationRequested);
        var d1 = f.Host.TryAdmitRelay("D")!;           // D has none: the hub with the most waiting (A) gives its oldest
        Assert.Equal(AgentHost.MaxRelayPending, f.Host.RelayPendingCount);
        Assert.True(a2.Evicted.IsCancellationRequested);
        Assert.False(a3.Evicted.IsCancellationRequested || b1.Evicted.IsCancellationRequested
                     || c1.Evicted.IsCancellationRequested || d1.Evicted.IsCancellationRequested);
        // A flood on one hub's route (leaked) only ever evicts that hub's own streams
        var flood = Enumerable.Range(0, 5).Select(_ => f.Host.TryAdmitRelay("D")!).ToList();
        Assert.False(a3.Evicted.IsCancellationRequested || b1.Evicted.IsCancellationRequested || c1.Evicted.IsCancellationRequested);
        Assert.True(d1.Evicted.IsCancellationRequested);
        foreach (var t in new[] { a1, a2, a3, b1, c1, d1 }.Concat(flood)) t.Dispose();
        Assert.Equal(0, f.Host.RelayPendingCount);
        Assert.Null(f.Host.TryAdmit("192.168.1.99")); // relay tickets never touched the LAN pool
        foreach (var t in lan) t!.Dispose();
        await Task.CompletedTask;
    }
}
