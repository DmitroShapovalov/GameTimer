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
        builder.Services.PostConfigure<RelayOptions>(o => o.ClientIpHeader = IpHeader);
        var app = builder.Build();
        RelayApp.Map(app);
        await app.StartAsync();
        var server = app.GetTestServer();
        var agent = new AgentFixture();
        var client = new RelayClient(agent.Host, new Uri(server.BaseAddress, "v2/agent"), connect: async (uri, ct) =>
        {
            var c = server.CreateWebSocketClient();
            c.ConfigureRequest = r => r.Headers[IpHeader] = "10.0.0.1";
            return await c.ConnectAsync(uri, ct);
        });
        return new Rig { App = app, Server = server, Agent = agent, Client = client };
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
        rig.Agent.Host.RemoteAccess = true;
        rig.Client.Start();

        var hub = new Hub { Ws = await rig.DialHubAsync(Ak(pair)) };
        Assert.True(await hub.HandshakeAsync(key, rig.Agent.Host.AgentPublicKey));
        var hello = await hub.ReadyAsync();
        Assert.True(hello.GetProperty("remoteAccess").GetBoolean());
        Assert.Equal(RelayStatus.Connected, rig.Client.Status);

        await hub.SendJsonAsync(new JsonObject { ["t"] = "state.get", ["id"] = 1 });
        var reply = await hub.ReceiveJsonAsync();
        Assert.Equal("result", reply!.Value.GetProperty("t").GetString());

        // "Control from anywhere" turned off: the relayed session ends with bye remote_off
        rig.Agent.Host.EndRelaySessions();
        var bye = await hub.ReceiveJsonAsync();
        Assert.Equal("bye", bye!.Value.GetProperty("t").GetString());
        Assert.Equal("remote_off", bye.Value.GetProperty("code").GetString());
        Assert.Null(await hub.ReceiveAsync());
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

    [Fact]
    public async Task The_relay_pool_is_apart_and_evicts_the_oldest()
    {
        using var f = new AgentFixture();
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
        var d1 = f.Host.TryAdmitRelay("D")!;           // 4 in total: the oldest (a2) goes
        Assert.Equal(AgentHost.MaxRelayPending, f.Host.RelayPendingCount);
        Assert.True(a2.Evicted.IsCancellationRequested);
        Assert.False(a3.Evicted.IsCancellationRequested || b1.Evicted.IsCancellationRequested
                     || c1.Evicted.IsCancellationRequested || d1.Evicted.IsCancellationRequested);
        foreach (var t in new[] { a1, a2, a3, b1, c1, d1 }) t.Dispose();
        Assert.Equal(0, f.Host.RelayPendingCount);
        Assert.Null(f.Host.TryAdmit("192.168.1.99")); // relay tickets never touched the LAN pool
        foreach (var t in lan) t!.Dispose();
        await Task.CompletedTask;
    }
}
