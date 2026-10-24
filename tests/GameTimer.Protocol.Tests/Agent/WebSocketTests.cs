using System.Net;
using System.Net.WebSockets;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Noise;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Xunit;

namespace GameTimer.Protocol.Tests.Agent;

/// <summary>
/// The agent behind a real Kestrel WebSocket, wired like RemoteServer.V2.cs: checks that
/// <see cref="WebSocketFrameTransport"/> behaves (frames, close, text messages, the pairing flow that
/// keeps a receive pending while the owner types) where MemoryTransport can't prove it.
/// </summary>
public sealed class WebSocketTests : IAsyncLifetime
{
    private readonly AgentFixture _f = new();
    private WebApplication? _app;
    private int _port;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(o => o.Listen(IPAddress.Loopback, 0));
        _app = builder.Build();
        _app.UseWebSockets();
        _app.Map("/v2", c => Accept(c, false));
        _app.Map("/v2/pair", c => Accept(c, true));
        await _app.StartAsync();
        _port = new Uri(_app.Urls.First()).Port;
    }

    private async Task Accept(HttpContext context, bool pairing)
    {
        if (context.Request.Headers.Origin.Count > 0) { context.Response.StatusCode = 403; return; }
        if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
        using var ticket = _f.Host.TryAdmit(context.Connection.RemoteIpAddress!.ToString());
        if (ticket == null) { context.Response.StatusCode = 503; return; }
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        await _f.Host.RunWebSocketAsync(socket, ticket, pairing, context.RequestAborted);
        Interlocked.Increment(ref _handlersDone);
    }

    private int _handlersDone;

    public async Task DisposeAsync()
    {
        _f.Dispose();
        if (_app != null) await _app.DisposeAsync();
    }

    private async Task<ClientWebSocket> Connect(string path, string? origin = null)
    {
        var ws = new ClientWebSocket();
        if (origin != null) ws.Options.SetRequestHeader("Origin", origin);
        await ws.ConnectAsync(new Uri($"ws://127.0.0.1:{_port}{path}"), TestHub.Timeout());
        return ws;
    }

    private static Task Send(WebSocket ws, byte[] frame) => ws.SendAsync(frame, WebSocketMessageType.Binary, true, TestHub.Timeout());

    private static async Task<byte[]?> Receive(WebSocket ws)
    {
        var buffer = new byte[65536];
        int length = 0;
        try
        {
            ValueWebSocketReceiveResult r;
            do
            {
                r = await ws.ReceiveAsync(buffer.AsMemory(length), TestHub.Timeout());
                if (r.MessageType == WebSocketMessageType.Close) return null;
                length += r.Count;
            } while (!r.EndOfMessage);
            return buffer[..length];
        }
        catch (WebSocketException) { return null; }
    }

    private static Task SendJson(WebSocket ws, CipherState send, JsonObject m) => Send(ws, send.Encrypt(JsonSerializer.SerializeToUtf8Bytes(m)));

    private static async Task<JsonElement?> ReceiveJson(WebSocket ws, CipherState recv)
    {
        var f = await Receive(ws);
        if (f == null) return null;
        using var doc = JsonDocument.Parse(recv.Decrypt(f));
        return doc.RootElement.Clone();
    }

    private async Task<(KeyPair Key, string Code)> PairOverSocket(string name = "Phone", int ownerDelayMs = 300)
    {
        var offer = _f.Host.StartPairing("PC");
        var key = KeyPair.Generate();
        using var ws = await Connect("/v2/pair");
        var hs = new HandshakeState(NoisePattern.IKpsk1, true, TestHub.Prologue, key, _f.Host.AgentPublicKey, offer.Secret);
        await Send(ws, hs.WriteMessage("{\"t\":\"pair\"}"u8));
        hs.ReadMessage((await Receive(ws))!);
        var (send, recv) = hs.Split();
        var code = PairingCode.Derive(hs.GetHandshakeHash());
        // The owner takes a moment to type: the agent keeps a receive pending on the socket meanwhile
        _f.Ui.Answer = async (_, _) => { await Task.Delay(ownerDelayMs); return code; };
        await SendJson(ws, send, new JsonObject { ["t"] = "pair.hello", ["protocol"] = 2, ["minor"] = 0, ["name"] = name });
        var result = await ReceiveJson(ws, recv);
        Assert.Equal("pair.result", result?.GetProperty("t").GetString()); // the socket survived the cancelled receive
        Assert.Null(await Receive(ws)); // then the agent closes the pairing connection
        return (key, code);
    }

    private async Task<(ClientWebSocket Ws, CipherState Send, CipherState Recv, JsonElement Hello)> SessionOverSocket(KeyPair key)
    {
        var ws = await Connect("/v2");
        var hs = new HandshakeState(NoisePattern.IK, true, TestHub.Prologue, key, _f.Host.AgentPublicKey);
        await Send(ws, hs.WriteMessage("{\"t\":\"connect\"}"u8));
        hs.ReadMessage((await Receive(ws))!);
        var (send, recv) = hs.Split();
        await SendJson(ws, send, new JsonObject { ["t"] = "ready", ["protocol"] = 2, ["minor"] = 0 });
        var hello = (await ReceiveJson(ws, recv))!.Value;
        return (ws, send, recv, hello);
    }

    [Fact]
    public async Task Pair_then_session_and_a_request_over_a_real_socket()
    {
        var (key, _) = await PairOverSocket();
        var (ws, send, recv, hello) = await SessionOverSocket(key);
        Assert.Equal("hello", hello.GetProperty("t").GetString());
        Assert.Equal(Ids.AgentId(_f.Host.AgentPublicKey), hello.GetProperty("agent").GetProperty("id").GetString());

        await SendJson(ws, send, new JsonObject { ["t"] = "timer.extend", ["id"] = 1, ["rid"] = TestHub.NewRid(), ["minutes"] = 5 });
        var r = (await ReceiveJson(ws, recv))!.Value;
        Assert.Equal("result", r.GetProperty("t").GetString());
        Assert.Equal(1, _f.Backend.Extends);

        // Revoked from the PC: bye revoked, then the socket closes
        _f.Host.RevokeByOwner(Ids.HubId(key.PublicKey));
        Assert.Equal("revoked", (await ReceiveJson(ws, recv))!.Value.GetProperty("code").GetString());
        Assert.Null(await Receive(ws));
    }

    [Fact]
    public async Task Text_message_gets_bye_protocol_error()
    {
        var (key, _) = await PairOverSocket();
        var (ws, _, recv, _) = await SessionOverSocket(key);
        await ws.SendAsync("{\"t\":\"ping\"}"u8.ToArray(), WebSocketMessageType.Text, true, TestHub.Timeout());
        Assert.Equal("protocol_error", (await ReceiveJson(ws, recv))!.Value.GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_flood_of_junk_frames_does_not_pin_the_connection()
    {
        // Regression: the read loop blocked on a full queue once the agent stopped reading, so the
        // handler (and its socket) never finished
        using var ws = await Connect("/v2");
        for (int i = 0; i < 40; i++) await Send(ws, new byte[100]);
        Assert.Null(await Receive(ws)); // the agent closes after the bad msg 1
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < deadline && _handlersDone == 0) await Task.Delay(50);
        Assert.Equal(1, _handlersDone);
    }

    [Fact]
    public async Task Browser_origin_is_refused()
    {
        var ex = await Assert.ThrowsAsync<WebSocketException>(() => Connect("/v2", origin: "http://evil.example"));
        Assert.Contains("403", ex.Message);
    }

    [Fact]
    public async Task Replayed_msg_1_and_ready_over_a_real_socket()
    {
        var (key, _) = await PairOverSocket();
        byte[] msg1, ready;
        using (var ws = await Connect("/v2"))
        {
            var hs = new HandshakeState(NoisePattern.IK, true, TestHub.Prologue, key, _f.Host.AgentPublicKey);
            msg1 = hs.WriteMessage("{\"t\":\"connect\"}"u8);
            await Send(ws, msg1);
            hs.ReadMessage((await Receive(ws))!);
            var (send, _) = hs.Split();
            ready = send.Encrypt("{\"t\":\"ready\",\"protocol\":2,\"minor\":0}"u8.ToArray());
            await Send(ws, ready);
            Assert.NotNull(await Receive(ws)); // hello
        }
        using (var replay = await Connect("/v2"))
        {
            await Send(replay, msg1);
            Assert.NotNull(await Receive(replay)); // msg 2
            await Send(replay, ready);
            Assert.Null(await Receive(replay));   // closed, no hello
        }
    }
}
