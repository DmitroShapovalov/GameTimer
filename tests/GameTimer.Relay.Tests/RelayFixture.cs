using System.Buffers.Binary;
using System.Net.WebSockets;
using GameTimer.Protocol.Identity;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace GameTimer.Relay.Tests;

/// <summary>An in-memory relay (TestServer) with short timeouts, and helpers for agent and hub sockets.</summary>
internal sealed class RelayFixture : IAsyncDisposable
{
    public const string IpHeader = "X-Test-Client-IP";

    private readonly WebApplication _app;
    public TestServer Server { get; }
    internal RelayState State => _app.Services.GetRequiredService<RelayState>();

    private RelayFixture(WebApplication app)
    {
        _app = app;
        Server = app.GetTestServer();
    }

    /// <summary>Wake-ups the relay passed on (shared by the fixtures of one test class run; read per test).</summary>
    public static FakePush Push { get; } = new();

    public static async Task<RelayFixture> StartAsync(Action<RelayOptions>? tweak = null)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        RelayApp.ConfigureServices(builder);
        builder.Services.AddSingleton<IPushSender>(Push);
        builder.Services.PostConfigure<RelayOptions>(o =>
        {
            o.ClientIpHeader = IpHeader;
            o.Sweep = TimeSpan.FromMilliseconds(50);
            o.CloseEchoWait = TimeSpan.FromSeconds(2);
            o.WakeAfterConnected = TimeSpan.Zero;
            tweak?.Invoke(o);
        });
        var app = builder.Build();
        RelayApp.Map(app);
        await app.StartAsync();
        return new RelayFixture(app);
    }

    public async ValueTask DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
    }

    public async Task<WebSocket> ConnectAsync(string path, string ip, bool origin = false)
    {
        var client = Server.CreateWebSocketClient();
        client.ConfigureRequest = r =>
        {
            r.Headers[IpHeader] = ip;
            if (origin) r.Headers["Origin"] = "https://example.com";
        };
        return await client.ConnectAsync(new Uri(Server.BaseAddress, path), CancellationToken.None);
    }

    /// <summary>The HTTP status an upgrade gets (101 when it succeeds; the socket is then closed).</summary>
    public async Task<int> StatusAsync(string path, string ip, bool origin = false)
    {
        try
        {
            var ws = await ConnectAsync(path, ip, origin);
            ws.Abort();
            return 101;
        }
        catch (InvalidOperationException e) when (e.Message.Contains("status code"))
        {
            return int.Parse(new string(e.Message.Where(char.IsDigit).ToArray()));
        }
    }

    public Task<WebSocket> AgentAsync(string ip = "10.0.0.1") => ConnectAsync("v2/agent", ip);

    public Task<WebSocket> HubAsync(string route, string ip = "10.0.0.2") => ConnectAsync("v2/hub/" + route, ip);

    // ---------------- frames and claims ----------------

    public static readonly DateOnly Day = new(2026, 10, 31);

    public static byte[] Ak(int n) => Enumerable.Repeat((byte)n, 32).ToArray();

    public static byte[] Claim(int hub) => RouteToken.Claim(Ak(hub), Day);

    public static string Route(int hub) => RouteToken.Derive(Ak(hub), Day);

    public static byte[] Register(params int[] hubs)
    {
        var f = new List<byte> { 0x01 };
        foreach (var h in hubs) f.AddRange(Claim(h));
        return f.ToArray();
    }

    public static byte[] Data(uint stream, params byte[] noise) => Frame(0x03, stream, noise);

    public static byte[] Close(uint stream, ushort code)
    {
        var b = new byte[2];
        BinaryPrimitives.WriteUInt16BigEndian(b, code);
        return Frame(0x04, stream, b);
    }

    public static byte[] Frame(byte type, uint stream, byte[] body)
    {
        var f = new byte[5 + body.Length];
        f[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(f.AsSpan(1), stream);
        body.CopyTo(f, 5);
        return f;
    }

    public static Task SendAsync(WebSocket ws, byte[] data) =>
        ws.SendAsync(data, WebSocketMessageType.Binary, true, CancellationToken.None);

    /// <summary>The next message (or close) within <paramref name="seconds"/>; fails the test on timeout.</summary>
    public static async Task<(WebSocketMessageType Type, byte[] Data)> ReceiveAsync(WebSocket ws, double seconds = 5)
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(seconds));
        var buf = new byte[70_000];
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await ws.ReceiveAsync(new ArraySegment<byte>(buf), cts.Token);
            if (r.MessageType == WebSocketMessageType.Close) return (r.MessageType, Array.Empty<byte>());
            ms.Write(buf, 0, r.Count);
            if (r.EndOfMessage) return (r.MessageType, ms.ToArray());
        }
    }

    /// <summary>Waits for the peer's close and returns its code (answering it).</summary>
    public static async Task<int> ClosedWithAsync(WebSocket ws, double seconds = 5)
    {
        while (true)
        {
            var (type, _) = await ReceiveAsync(ws, seconds);
            if (type != WebSocketMessageType.Close) continue;
            var code = (int)ws.CloseStatus!.Value;
            try { await ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None); } catch { }
            return code;
        }
    }

    /// <summary>The agent's next frame of <paramref name="type"/>, skipping keepalive pings.</summary>
    public static async Task<byte[]> NextFrameAsync(WebSocket agent, byte type, double seconds = 5)
    {
        while (true)
        {
            var (t, d) = await ReceiveAsync(agent, seconds);
            Assert.Equal(WebSocketMessageType.Binary, t);
            if (d[0] == 0x05 && type != 0x05) continue;
            Assert.Equal(type, d[0]);
            return d;
        }
    }

    /// <summary>Registers hubs and waits until the relay has applied it (no acknowledgement exists).</summary>
    public async Task RegisterAsync(WebSocket agent, params int[] hubs)
    {
        await SendAsync(agent, Register(hubs));
        for (int i = 0; i < 200 && hubs.Any(h => State.Registry.Find(Route(h)) == null); i++) await Task.Delay(10);
    }
}

/// <summary>Records wake-ups instead of calling FCM.</summary>
public sealed class FakePush : IPushSender
{
    private readonly System.Collections.Concurrent.ConcurrentQueue<string> _tokens = new();

    public bool TryWake(string pushToken)
    {
        _tokens.Enqueue(pushToken);
        return true;
    }

    public IReadOnlyList<string> Tokens => _tokens.ToList();
}
