using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Noise;
using static Wire;

// gt-testhub: a minimal protocol v2 parent app (docs/protocol.md) to test the Windows agent on a real PC.
// State (this hub's key, the PC's key, address, ak) is kept in gt-testhub.json in the current folder.
//
//   gt-testhub pair "<link from the Connect phone window>" [name]
//   gt-testhub status | stop | start <minutes> | extend <minutes> | peers | history | settings | watch
//   gt-testhub replay-test      a recorded msg 1 + ready, replayed, must never become a session

const string StateFile = "gt-testhub.json";
var prologue = "GameTimer/2"u8.ToArray();

try
{
    if (args.Length == 0) return Usage();
    switch (args[0])
    {
        case "pair": return args.Length >= 2 ? await Pair(args[1], args.Length >= 3 ? args[2] : "Test hub") : Usage();
        case "status": return await Session(s => { Print(s.Hello); return Task.CompletedTask; });
        case "stop": return await Request("timer.stop", new JsonObject());
        case "start" when args.Length >= 2 && int.TryParse(args[1], out var m):
            return await Request("timer.start", new JsonObject { ["mode"] = "interval", ["hours"] = m / 60, ["minutes"] = m % 60 });
        case "extend" when args.Length >= 2 && int.TryParse(args[1], out var m):
            return await Request("timer.extend", new JsonObject { ["minutes"] = m });
        case "peers": return await Request("peers.list", null);
        case "history": return await Request("history.get", new JsonObject { ["limit"] = 10 });
        case "settings": return await Request("settings.get", null);
        case "watch": return await Session(async s => { Print(s.Hello); while (true) { var ev = await s.Receive(); if (ev == null) break; Print(ev); } });
        case "replay-test": return await ReplayTest();
        default: return Usage();
    }
}
catch (Exception ex)
{
    Console.Error.WriteLine("error: " + ex.Message);
    return 1;
}

static int Usage()
{
    Console.WriteLine("""
        gt-testhub pair "<link from the Connect phone window>" [name]
        gt-testhub status | stop | start <minutes> | extend <minutes> | peers | history | settings | watch
        gt-testhub replay-test
        """);
    return 2;
}

static void Print(JsonNode? node) =>
    Console.WriteLine(node?.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

async Task<int> Pair(string link, string name)
{
    var uri = new Uri(link);
    var fragment = System.Web.HttpUtility.ParseQueryString(uri.Fragment.TrimStart('#'));
    var agentKey = Base64Url.TryDecode(fragment["k"]) ?? throw new ArgumentException("link has no k (agent key)");
    var secret = Base64Url.TryDecode(fragment["s"]) ?? throw new ArgumentException("link has no s (pairing secret)");

    var hubKey = KeyPair.Generate();
    using var ws = await Connect(uri.Host, uri.Port, "/v2/pair");
    var hs = new HandshakeState(NoisePattern.IKpsk1, true, prologue, hubKey, agentKey, secret);
    await SendRaw(ws, hs.WriteMessage("{\"t\":\"pair\"}"u8));
    var msg2 = await ReceiveRaw(ws) ?? throw new InvalidOperationException("the PC closed the connection (wrong or expired code?)");
    hs.ReadMessage(msg2);
    var (send, recv) = hs.Split();
    var code = PairingCode.Derive(hs.GetHandshakeHash());

    await SendJson(ws, send, new JsonObject { ["t"] = "pair.hello", ["protocol"] = 2, ["minor"] = 0, ["name"] = name, ["app"] = "gt-testhub" });
    Console.WriteLine($"Enter this code on the PC: {code}");
    var result = await ReceiveJson(ws, recv) ?? throw new InvalidOperationException("the PC closed the connection");
    Print(result);
    if (result["t"]?.GetValue<string>() != "pair.result") return 1;

    var agentId = result["agent"]!["id"]!.GetValue<string>();
    if (agentId != Ids.AgentId(agentKey)) throw new InvalidOperationException("agent id does not match the pinned key"); // §4.4
    var state = new JsonObject
    {
        ["hubKey"] = Base64Url.Encode(hubKey.PrivateKey),
        ["agentKey"] = Base64Url.Encode(agentKey),
        ["host"] = uri.Host,
        ["port"] = uri.Port,
        ["ak"] = result["ak"]!.GetValue<string>(),
        ["agentId"] = agentId,
    };
    File.WriteAllText(StateFile, state.ToJsonString());
    Console.WriteLine($"Paired as {result["role"]}. Saved to {StateFile}.");
    return 0;
}

async Task<int> Request(string type, JsonObject? fields) => await Session(async s =>
{
    var reply = await s.Request(type, fields);
    Print(reply);
});

async Task<int> Session(Func<HubSession, Task> body)
{
    var st = JsonNode.Parse(File.ReadAllText(StateFile))!;
    var hubKey = KeyPair.FromPrivateKey(Base64Url.TryDecode(st["hubKey"]!.GetValue<string>())!);
    var agentKey = Base64Url.TryDecode(st["agentKey"]!.GetValue<string>())!;
    using var ws = await Connect(st["host"]!.GetValue<string>(), st["port"]!.GetValue<int>(), "/v2");
    var session = await HubSession.Open(ws, hubKey, agentKey, prologue);
    if (session.Hello["agent"]?["id"]?.GetValue<string>() != Ids.AgentId(agentKey))
        throw new InvalidOperationException("hello.agent.id does not match the pinned key");
    await body(session);
    return 0;
}

async Task<int> ReplayTest()
{
    var st = JsonNode.Parse(File.ReadAllText(StateFile))!;
    var host = st["host"]!.GetValue<string>();
    var port = st["port"]!.GetValue<int>();
    var hubKey = KeyPair.FromPrivateKey(Base64Url.TryDecode(st["hubKey"]!.GetValue<string>())!);
    var agentKey = Base64Url.TryDecode(st["agentKey"]!.GetValue<string>())!;

    // A real session, as a sniffer would record it: msg 1 and the first transport message (ready)
    byte[] msg1, ready;
    using (var ws = await Connect(host, port, "/v2"))
    {
        var hs = new HandshakeState(NoisePattern.IK, true, prologue, hubKey, agentKey);
        msg1 = hs.WriteMessage("{\"t\":\"connect\"}"u8);
        await SendRaw(ws, msg1);
        hs.ReadMessage((await ReceiveRaw(ws))!);
        var (send, _) = hs.Split();
        ready = send.Encrypt(Encoding.UTF8.GetBytes("{\"t\":\"ready\",\"protocol\":2,\"minor\":0,\"app\":\"gt-testhub\"}"));
        await SendRaw(ws, ready);
        await ReceiveRaw(ws); // hello
    }

    // The replay: the PC answers msg 2 (Noise allows that), but the recorded ready can't decrypt any more
    using (var ws = await Connect(host, port, "/v2"))
    {
        await SendRaw(ws, msg1);
        var msg2 = await ReceiveRaw(ws);
        Console.WriteLine(msg2 == null ? "msg 2: none" : "msg 2: received (expected)");
        await SendRaw(ws, ready);
        var next = await ReceiveRaw(ws);
        if (next == null)
        {
            Console.WriteLine("PASS: the PC closed the replayed session without a hello");
            return 0;
        }
        Console.WriteLine("FAIL: the PC sent something after the replayed ready");
        return 1;
    }
}

/// <summary>An open /v2 session after ready → hello.</summary>
sealed class HubSession
{
    private readonly WebSocket _ws;
    private readonly CipherState _send, _recv;
    private long _id;

    private HubSession(WebSocket ws, CipherState send, CipherState recv, JsonNode hello)
    {
        _ws = ws; _send = send; _recv = recv; Hello = hello;
    }

    public JsonNode Hello { get; }

    public static async Task<HubSession> Open(WebSocket ws, KeyPair hubKey, byte[] agentKey, byte[] prologue)
    {
        var hs = new HandshakeState(NoisePattern.IK, true, prologue, hubKey, agentKey);
        await SendRaw(ws, hs.WriteMessage("{\"t\":\"connect\"}"u8));
        var msg2 = await ReceiveRaw(ws) ?? throw new InvalidOperationException("the PC closed the connection (not paired, or revoked?)");
        hs.ReadMessage(msg2);
        var (send, recv) = hs.Split();
        await SendJson(ws, send, new JsonObject { ["t"] = "ready", ["protocol"] = 2, ["minor"] = 0, ["app"] = "gt-testhub" });
        var hello = await ReceiveJson(ws, recv) ?? throw new InvalidOperationException("no hello");
        if (hello["t"]?.GetValue<string>() != "hello") throw new InvalidOperationException("expected hello, got " + hello.ToJsonString());
        return new HubSession(ws, send, recv, hello);
    }

    public Task<JsonNode?> Receive() => ReceiveJson(_ws, _recv);

    public async Task<JsonNode?> Request(string type, JsonObject? fields)
    {
        var id = ++_id;
        var m = new JsonObject { ["t"] = type, ["id"] = id };
        if (fields != null)
        {
            m["rid"] = Base64Url.Encode(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16));
            foreach (var (k, v) in fields) m[k] = v?.DeepClone();
        }
        await SendJson(_ws, _send, m);
        while (true)
        {
            var r = await Receive();
            if (r == null) return null;
            if (r["re"]?.GetValue<long>() == id || r["t"]?.GetValue<string>() == "bye") return r;
        }
    }
}

/// <summary>WebSocket + Noise framing helpers (one binary message = one Noise message).</summary>
static class Wire
{
    public static async Task<ClientWebSocket> Connect(string host, int port, string path)
    {
        var ws = new ClientWebSocket(); // sends no Origin header (the PC refuses browsers)
        await ws.ConnectAsync(new Uri($"ws://{host}:{port}{path}"), CancellationToken.None);
        return ws;
    }

    public static Task SendRaw(WebSocket ws, byte[] frame) =>
        ws.SendAsync(frame, WebSocketMessageType.Binary, true, CancellationToken.None);

    public static async Task<byte[]?> ReceiveRaw(WebSocket ws)
    {
        var buffer = new byte[65536];
        int length = 0;
        try
        {
            ValueWebSocketReceiveResult r;
            do
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(120));
                r = await ws.ReceiveAsync(buffer.AsMemory(length), timeout.Token);
                if (r.MessageType == WebSocketMessageType.Close) return null;
                length += r.Count;
            } while (!r.EndOfMessage);
            return buffer[..length];
        }
        catch (WebSocketException) { return null; }
    }

    public static Task SendJson(WebSocket ws, CipherState send, JsonObject message) =>
        SendRaw(ws, send.Encrypt(JsonSerializer.SerializeToUtf8Bytes(message)));

    public static async Task<JsonNode?> ReceiveJson(WebSocket ws, CipherState recv)
    {
        var frame = await ReceiveRaw(ws);
        return frame == null ? null : JsonNode.Parse(recv.Decrypt(frame));
    }
}
