using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Noise;
using Xunit;

namespace GameTimer.Protocol.Tests.Agent;

/// <summary>An in-memory WebSocket: two ends, binary frames, close from either side.</summary>
internal sealed class MemoryTransport : IFrameTransport
{
    private readonly Channel<byte[]?> _in;
    private readonly Channel<byte[]?> _out;
    private MemoryTransport? _peer;
    private int _closed;

    private MemoryTransport(Channel<byte[]?> @in, Channel<byte[]?> @out)
    {
        _in = @in;
        _out = @out;
    }

    public static (MemoryTransport Agent, MemoryTransport Hub) Pair()
    {
        var a = Channel.CreateUnbounded<byte[]?>();
        var b = Channel.CreateUnbounded<byte[]?>();
        var agent = new MemoryTransport(a, b);
        var hub = new MemoryTransport(b, a);
        agent._peer = hub;
        hub._peer = agent;
        return (agent, hub);
    }

    /// <summary>Next frame from the other end; a text frame is simulated by <see cref="SendTextAsync"/>.</summary>
    public async ValueTask<byte[]?> ReceiveAsync(CancellationToken cancel)
    {
        if (Volatile.Read(ref _closed) != 0) return null; // this end closed itself; frames already sent by the peer stay readable otherwise
        try
        {
            var frame = await _in.Reader.ReadAsync(cancel);
            if (frame == null) return null;
            if (ReferenceEquals(frame, TextFrame)) throw new GameTimer.Protocol.Messages.ProtocolViolation("text message");
            return frame;
        }
        catch (ChannelClosedException)
        {
            return null;
        }
    }

    private static readonly byte[] TextFrame = new byte[1];

    public ValueTask SendAsync(byte[] frame, CancellationToken cancel)
    {
        if (Volatile.Read(ref _closed) != 0) throw new InvalidOperationException("closed");
        _out.Writer.TryWrite(frame); // dropped if the peer has closed, like a socket
        return ValueTask.CompletedTask;
    }

    /// <summary>Simulates a text WebSocket message arriving at the other end.</summary>
    public ValueTask SendTextAsync() => SendAsync(TextFrame, default);

    public ValueTask CloseAsync()
    {
        if (Interlocked.Exchange(ref _closed, 1) == 0)
        {
            // like a WebSocket close: the peer reads what was sent, then null; its own sends are dropped
            _out.Writer.TryWrite(null);
            _out.Writer.TryComplete();
            _in.Writer.TryComplete();
        }
        return ValueTask.CompletedTask;
    }

    public bool IsClosed => Volatile.Read(ref _closed) != 0 || _in.Reader.Completion.IsCompleted;
}

internal sealed class FakePairingUi : IPairingUi
{
    /// <summary>Answers the code prompt; default: cancel.</summary>
    public Func<string, CancellationToken, Task<string?>> Answer { get; set; } = (_, _) => Task.FromResult<string?>(null);
    public List<string> Asked { get; } = new();
    public List<PairingAbortReason> Aborts { get; } = new();

    public Task<string?> AskForCodeAsync(string hubName, CancellationToken cancel)
    {
        lock (Asked) Asked.Add(hubName);
        return Answer(hubName, cancel);
    }

    public void PairingAborted(PairingAbortReason reason)
    {
        lock (Aborts) Aborts.Add(reason);
    }
}

internal sealed class FakeBackend : IAgentBackend
{
    private int _extends;
    public AgentHost? Host { get; set; }
    public AgentInfo Info { get; } = new("Kids PC", "windows", "1.7.0-test");
    public IReadOnlyCollection<string> Capabilities { get; set; } = new[] { "timer", "settings" };
    public int Calls;
    public int Extends => Volatile.Read(ref _extends);

    public Task<JsonObject> GetStateAsync() =>
        Task.FromResult(new JsonObject { ["timer"] = new JsonObject { ["state"] = "notStarted", ["extends"] = Extends } });

    public Task<Reply> HandleAsync(string type, JsonElement message, RequestContext context)
    {
        Interlocked.Increment(ref Calls);
        switch (type)
        {
            case "state.get":
                return GetStateAsync().ContinueWith(t => Reply.Ok(new JsonObject { ["state"] = t.Result }));
            case "timer.extend":
                if (!message.TryGetProperty("minutes", out var m) || m.GetInt32() == 0)
                    return Task.FromResult(Reply.Error("invalid_minutes"));
                Interlocked.Increment(ref _extends);
                // like the Windows backend: the controller's StateChanged fires during the request
                Host!.Publish("ev.state", new JsonObject { ["state"] = new JsonObject { ["extends"] = Extends } });
                return Task.FromResult(Reply.Ok(new JsonObject { ["state"] = new JsonObject { ["extends"] = Extends } }));
            case "timer.stop":
                return Task.FromResult(Reply.Error("not_running"));
            case "settings.get":
                return Task.FromResult(Reply.Ok(new JsonObject { ["settings"] = new JsonObject { ["rev"] = 1 } }));
            default:
                return Task.FromResult(Reply.Error("unknown_command"));
        }
    }
}

/// <summary>A scripted hub (the phone app's side) over a <see cref="MemoryTransport"/>.</summary>
internal sealed class TestHub
{
    public static readonly byte[] Prologue = "GameTimer/2"u8.ToArray();
    private long _nextId;

    public required KeyPair Key { get; init; }
    public required MemoryTransport End { get; init; }
    public required Task Run { get; init; }
    public CipherState? Send { get; set; }
    public CipherState? Recv { get; set; }
    public byte[]? HandshakeHash { get; set; }
    public byte[]? Msg1 { get; set; }
    public byte[]? FirstTransport { get; set; }

    public string Id => Ids.HubId(Key.PublicKey);

    /// <summary>Opens /v2 or /v2/pair; runs msg 1 / msg 2. Returns null for msg 2 if the agent closed silently.</summary>
    public static async Task<(TestHub Hub, bool GotMsg2)> HandshakeAsync(AgentHost host, KeyPair key, bool pairing,
        byte[]? psk = null, string ip = "192.168.1.20", byte[]? replayMsg1 = null, string firstType = "connect")
    {
        var (agentEnd, hubEnd) = MemoryTransport.Pair();
        var ticket = host.TryAdmit(ip) ?? throw new InvalidOperationException("not admitted");
        var run = host.RunAsync(agentEnd, ticket, pairing, CancellationToken.None);
        var hs = replayMsg1 == null
            ? new HandshakeState(pairing ? NoisePattern.IKpsk1 : NoisePattern.IK, true, Prologue, key, host.AgentPublicKey, psk)
            : null;
        var msg1 = replayMsg1 ?? hs!.WriteMessage(Encoding.UTF8.GetBytes($"{{\"t\":\"{firstType}\"}}"));
        await hubEnd.SendAsync(msg1, default);
        var hub = new TestHub { Key = key, End = hubEnd, Run = run, Msg1 = msg1 };
        var msg2 = await hubEnd.ReceiveAsync(Timeout());
        if (msg2 == null) return (hub, false);
        if (replayMsg1 != null) return (hub, true); // the replayer can't read msg 2
        hs!.ReadMessage(msg2);
        hub.HandshakeHash = hs.GetHandshakeHash();
        (hub.Send, hub.Recv) = hs.Split();
        return (hub, true);
    }

    /// <summary>A full /v2 session: handshake, ready, hello.</summary>
    public static async Task<(TestHub Hub, JsonElement Hello)> ConnectAsync(AgentHost host, KeyPair key, string ip = "192.168.1.20")
    {
        var (hub, ok) = await HandshakeAsync(host, key, pairing: false, ip: ip);
        Assert.True(ok, "agent refused the handshake");
        await hub.SendJsonAsync(new JsonObject { ["t"] = "ready", ["protocol"] = 2, ["minor"] = 0, ["app"] = "test" });
        var hello = await hub.ReceiveJsonAsync();
        Assert.Equal("hello", hello.GetProperty("t").GetString());
        return (hub, hello);
    }

    public async Task SendJsonAsync(JsonObject message)
    {
        var frame = Send!.Encrypt(JsonSerializer.SerializeToUtf8Bytes(message));
        FirstTransport ??= frame;
        await End.SendAsync(frame, default);
    }

    public Task SendRawAsync(byte[] plaintext) => End.SendAsync(Send!.Encrypt(plaintext), default).AsTask();

    /// <summary>The next message, or null if the agent closed.</summary>
    public async Task<JsonElement?> TryReceiveJsonAsync(TimeSpan? timeout = null)
    {
        var frame = await End.ReceiveAsync(Timeout(timeout));
        if (frame == null) return null;
        using var doc = JsonDocument.Parse(Recv!.Decrypt(frame));
        return doc.RootElement.Clone();
    }

    public async Task<JsonElement> ReceiveJsonAsync() => await TryReceiveJsonAsync() ?? throw new InvalidOperationException("agent closed");

    /// <summary>Sends a request with the next id (and a fresh rid if asked) and waits for its result / error, skipping events.</summary>
    public async Task<JsonElement> RequestAsync(string type, JsonObject? fields = null, string? rid = null, bool withRid = false)
    {
        var id = ++_nextId;
        var m = new JsonObject { ["t"] = type, ["id"] = id };
        if (rid != null || withRid) m["rid"] = rid ?? NewRid();
        if (fields != null) foreach (var (k, v) in fields) m[k] = v?.DeepClone();
        await SendJsonAsync(m);
        while (true)
        {
            var r = await ReceiveJsonAsync();
            var t = r.GetProperty("t").GetString();
            if ((t is "result" or "error") && r.GetProperty("re").GetInt64() == id) return r;
            if (t == "bye") throw new InvalidOperationException("bye " + r.GetProperty("code").GetString());
        }
    }

    public void SkipIds(long n) => _nextId += n;

    public static string NewRid() => GameTimer.Protocol.Encodings.Base64Url.Encode(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16));

    public static CancellationToken Timeout(TimeSpan? t = null) => new CancellationTokenSource(t ?? TimeSpan.FromSeconds(10)).Token;
}

/// <summary>An agent with fakes, plus helpers to pair hubs.</summary>
internal sealed class AgentFixture : IDisposable
{
    public MemoryAgentStore Store { get; } = new();
    public FakeBackend Backend { get; } = new();
    public FakePairingUi Ui { get; } = new();
    public AgentHost Host { get; }

    public AgentFixture(TimeProvider? time = null, MemoryAgentStore? store = null)
    {
        if (store != null) Store = store;
        Host = new AgentHost(Store, Backend, Ui, time);
        Backend.Host = Host;
    }

    /// <summary>Pairs a new hub: the owner types the right code. Returns its key and the pair.result.</summary>
    public async Task<(KeyPair Key, JsonElement Result)> PairAsync(string name = "Phone")
    {
        var offer = Host.StartPairing("Kids PC");
        var key = KeyPair.Generate();
        var (hub, ok) = await TestHub.HandshakeAsync(Host, key, pairing: true, psk: offer.Secret, firstType: "pair");
        Assert.True(ok);
        var code = PairingCode.Derive(hub.HandshakeHash!);
        Ui.Answer = (_, _) => Task.FromResult<string?>(code);
        await hub.SendJsonAsync(new JsonObject { ["t"] = "pair.hello", ["protocol"] = 2, ["minor"] = 0, ["name"] = name, ["app"] = "test" });
        var result = await hub.ReceiveJsonAsync();
        Assert.Equal("pair.result", result.GetProperty("t").GetString());
        Host.CancelPairing();
        return (key, result);
    }

    public void Dispose() => Host.Dispose();
}
