using System.Diagnostics;
using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Relay;

// A scriptable agent for the Kotlin hub tests: the production AgentHost behind a real WebSocket, wired
// like the Windows app's RemoteServer.V2. Output lines: "PORT <n>", "LINK <url>", "ASK <name>",
// "ABORTED <reason>", "PAIRED <hub id>", "OK". Input lines: "pair", "code <digits>", "cancel",
// "revoke <hub id>", "alert <minutes>", "relay <ws://host:port>" (Control from anywhere on, RelayClient
// connected → "RELAY Connected"), "relayoff" (bye remote_off to relayed sessions, client stopped), "quit".
// With --emulator: listens on all interfaces and adds test-only HTTP control endpoints for the Android
// instrumented test in CI (the emulator reaches the host as 10.0.2.2): GET /control/pair → link,
// GET /control/code?c=<digits> → the "owner" types that code.
var emulator = args.Contains("--emulator");

var store = new MemoryAgentStore();
var ui = new ConsolePairingUi();
var backend = new SimBackend();
var host = new AgentHost(store, backend, ui);
backend.Host = host;
host.Paired += p => Out("PAIRED " + p.Id);

var builder = WebApplication.CreateSlimBuilder();
builder.Logging.ClearProviders();
builder.WebHost.ConfigureKestrel(o => o.Listen(emulator ? IPAddress.Any : IPAddress.Loopback, emulator ? 5123 : 0));
var app = builder.Build();
app.UseWebSockets();
app.Map("/v2", c => Accept(c, false));
app.Map("/v2/pair", c => Accept(c, true));
var port = 0;
if (emulator)
{
    app.MapGet("/control/health", () => "OK");
    app.MapGet("/control/pair", () => $"http://10.0.2.2:{port}/pair?code=X#{host.StartPairing("Test PC").QrFragment}");
    app.MapGet("/control/code", (string c) => { ui.Answer(c); return "OK"; });
}
await app.StartAsync();
port = new Uri(app.Urls.First().Replace("0.0.0.0", "127.0.0.1").Replace("[::]", "127.0.0.1")).Port;
Out("PORT " + port);
if (emulator) { await Task.Delay(Timeout.Infinite); return 0; } // no stdin in CI

RelayClient? relay = null;
string? line;
while ((line = Console.ReadLine()) != null)
{
    var parts = line.Split(' ', 2);
    switch (parts[0])
    {
        case "pair":
            var offer = host.StartPairing("Test PC");
            Out($"LINK http://127.0.0.1:{port}/pair?code=X#{offer.QrFragment}");
            break;
        case "code": ui.Answer(parts[1]); break;
        case "cancel": ui.Answer(null); break;
        case "revoke": Out(host.RevokeByOwner(parts[1]) ? "OK" : "NOTFOUND"); break;
        case "alert": host.Publish("ev.alert", new JsonObject { ["minutes"] = int.Parse(parts[1]) }); Out("OK"); break;
        case "relay":
            host.RemoteAccess = true;
            relay = new RelayClient(host, new Uri(parts[1].TrimEnd('/') + "/v2/agent"));
            relay.StatusChanged += s => Out("RELAY " + s);
            relay.Start();
            break;
        case "relayoff":
            host.RemoteAccess = false;
            await host.EndRelaySessionsAsync();
            if (relay != null) await relay.StopAsync();
            relay = null;
            Out("OK");
            break;
        case "quit": await host.ShutdownAsync(); if (relay != null) await relay.StopAsync(); await app.StopAsync(); return 0;
    }
}
return 0;

async Task Accept(HttpContext context, bool pairing)
{
    if (context.Request.Headers.Origin.Count > 0) { context.Response.StatusCode = 403; return; }
    if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
    using var ticket = host.TryAdmit(context.Connection.RemoteIpAddress!.ToString());
    if (ticket == null) { context.Response.StatusCode = 503; return; }
    using var socket = await context.WebSockets.AcceptWebSocketAsync();
    await host.RunWebSocketAsync(socket, ticket, pairing, context.RequestAborted);
}

static void Out(string s)
{
    lock (Console.Out) { Console.Out.WriteLine(s); Console.Out.Flush(); }
}

sealed class ConsolePairingUi : IPairingUi
{
    private readonly object _gate = new();
    private TaskCompletionSource<string?>? _pending;
    private string? _early; // a code that arrived before the agent asked (the phone shows it a moment earlier)

    public Task<string?> AskForCodeAsync(string hubName, CancellationToken cancel)
    {
        var tcs = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        cancel.Register(() => tcs.TrySetResult(null));
        lock (_gate)
        {
            if (_early is { } code) { _early = null; tcs.TrySetResult(code); }
            else _pending = tcs;
        }
        lock (Console.Out) { Console.Out.WriteLine("ASK " + hubName); Console.Out.Flush(); }
        return tcs.Task;
    }

    public void Answer(string? code)
    {
        lock (_gate)
        {
            if (_pending is { } p) { _pending = null; p.TrySetResult(code); }
            else _early = code;
        }
    }

    public void PairingAborted(PairingAbortReason reason)
    {
        lock (Console.Out) { Console.Out.WriteLine("ABORTED " + reason); Console.Out.Flush(); }
    }
}

/// <summary>
/// A stand-in for the Windows backend with the same message shapes (RemoteServer.V2.cs): a countdown,
/// settings and schedule with rev / ifRev / conflict, and a history.
/// </summary>
sealed class SimBackend : IAgentBackend
{
    private readonly object _gate = new();
    private readonly Stopwatch _clock = new();
    private int _durationSeconds;
    private long _settingsRev = 1, _scheduleRev = 1;
    private JsonObject _settings = new()
    {
        ["action"] = "lock", ["yellowThreshold"] = 10, ["redThreshold"] = 5,
        ["alertPoints"] = new JsonArray(new JsonObject { ["minutes"] = 5, ["enabled"] = true }),
        ["colorGreen"] = "#4CAF50", ["colorYellow"] = "#FFC107", ["colorRed"] = "#F44336", ["language"] = "en",
    };
    private JsonObject _schedule = new()
    {
        ["enabled"] = false,
        ["week"] = new JsonArray(Enumerable.Range(0, 7).Select(_ => (JsonNode)new JsonObject
        {
            ["allowed"] = true, ["limitMinutes"] = 120, ["windowStartMinutes"] = 0, ["windowEndMinutes"] = 1440,
        }).ToArray()),
    };
    private readonly List<JsonObject> _history = new();
    public AgentHost? Host { get; set; }

    public AgentInfo Info { get; } = new("Test PC", "windows", "0.0.0-test");
    public IReadOnlyCollection<string> Capabilities { get; } = new[] { "timer", "settings", "schedule", "history" };

    public Task<JsonObject> GetStateAsync() { lock (_gate) return Task.FromResult(State()); }

    public Task<Reply> HandleAsync(string type, JsonElement m, RequestContext context)
    {
        lock (_gate)
        {
            switch (type)
            {
                case "state.get": return Ok();
                case "timer.start":
                    if (_clock.IsRunning) return Err("already_running");
                    _durationSeconds = (m.GetProperty("hours").GetInt32() * 60 + m.GetProperty("minutes").GetInt32()) * 60;
                    if (_durationSeconds <= 0) return Err("invalid_time");
                    _clock.Restart();
                    _expired = false; // like the real agent: a timer started during a lift keeps the lift until it runs out
                    Log("started", context, _durationSeconds / 60);
                    return Changed(context);
                case "timer.stop":
                {
                    bool skip = m.TryGetProperty("skipScheduleToday", out var sk) && sk.ValueKind == JsonValueKind.True;
                    bool scheduleOn = _schedule["enabled"]!.GetValue<bool>();
                    if (!_clock.IsRunning && !(skip && scheduleOn)) return Err("not_running"); // a repeated lift is fine
                    if (_clock.IsRunning) { _clock.Reset(); Log("stopped", context, null); }
                    if (skip && scheduleOn && !_skipped) { _skipped = true; Log("scheduleSkipped", context, null); }
                    return Changed(context);
                }
                case "timer.end":
                    if (!_clock.IsRunning) return Err("not_running");
                    Log("extended", context, -Math.Max(1, (Remaining() + 59) / 60));
                    _clock.Reset();
                    _expired = true;
                    if (_skipped) { _skipped = false; Log("scheduleRestored", context, null); } // the lift ends with the timer
                    return Changed(context);
                case "timer.setEnd":
                {
                    if (!_clock.IsRunning) return Err("not_running");
                    if (!m.TryGetProperty("hours", out var hp) || !hp.TryGetInt32(out var h)
                        || !m.TryGetProperty("minutes", out var mp) || !mp.TryGetInt32(out var mi)) return Err("bad_request");
                    if (h is < 0 or > 23 || mi is < 0 or > 59) return Err("invalid_time");
                    var now = DateTime.Now;
                    var target = now.Date.AddHours(h).AddMinutes(mi);
                    if (target <= now) target = target.AddDays(1);
                    if (target - now > TimeSpan.FromHours(12) && (target - now).TotalSeconds > Remaining()) return Err("invalid_time");
                    // (the simulator has no schedule cap: availableSeconds is a fixed 7200)
                    int before = Remaining();
                    _durationSeconds = (int)_clock.Elapsed.TotalSeconds + (int)(target - now).TotalSeconds;
                    Log("extended", context, (int)Math.Round((Remaining() - before) / 60.0));
                    return Changed(context);
                }
                case "timer.extend":
                    if (!_clock.IsRunning) return Err("not_running");
                    if (!m.TryGetProperty("minutes", out var min) || min.GetInt32() == 0) return Err("invalid_minutes");
                    _durationSeconds += min.GetInt32() * 60;
                    Log("extended", context, min.GetInt32());
                    return Changed(context);
                case "interruption.ack": return Ok();
                case "settings.get": return Task.FromResult(Reply.Ok(new JsonObject { ["settings"] = WithRev(_settings, _settingsRev) }));
                case "settings.put":
                {
                    if (m.TryGetProperty("ifRev", out var r) && r.GetInt64() != _settingsRev)
                        return Task.FromResult(Reply.Error("conflict", new JsonObject { ["value"] = WithRev(_settings, _settingsRev) }));
                    foreach (var key in new[] { "colorGreen", "colorYellow", "colorRed" })
                        if (m.TryGetProperty(key, out var c) && !System.Text.RegularExpressions.Regex.IsMatch(c.GetString() ?? "", "^#[0-9A-Fa-f]{6}$"))
                            return Err("invalid_color"); // like RemoteServer.Validate
                    foreach (var key in new[] { "action", "yellowThreshold", "redThreshold", "alertPoints", "colorGreen", "colorYellow", "colorRed", "language" })
                        if (m.TryGetProperty(key, out var v)) _settings[key] = JsonNode.Parse(v.GetRawText());
                    _settingsRev++;
                    Host!.Publish("ev.settings", new JsonObject { ["settings"] = WithRev(_settings, _settingsRev) }, context.Origin);
                    return Task.FromResult(Reply.Ok(new JsonObject { ["settings"] = WithRev(_settings, _settingsRev) }));
                }
                case "schedule.get": return Task.FromResult(Reply.Ok(new JsonObject { ["schedule"] = WithRev(_schedule, _scheduleRev) }));
                case "schedule.put":
                {
                    if (m.TryGetProperty("ifRev", out var r) && r.GetInt64() != _scheduleRev)
                        return Task.FromResult(Reply.Error("conflict", new JsonObject { ["value"] = WithRev(_schedule, _scheduleRev) }));
                    if (!m.TryGetProperty("week", out var week) || week.GetArrayLength() != 7) return Err("invalid_schedule");
                    _schedule = new JsonObject { ["enabled"] = m.GetProperty("enabled").GetBoolean(), ["week"] = JsonNode.Parse(week.GetRawText()) };
                    _scheduleRev++;
                    Host!.Publish("ev.schedule", new JsonObject { ["schedule"] = WithRev(_schedule, _scheduleRev) }, context.Origin);
                    return Task.FromResult(Reply.Ok(new JsonObject { ["schedule"] = WithRev(_schedule, _scheduleRev) }));
                }
                case "history.get":
                {
                    int limit = m.TryGetProperty("limit", out var l) ? l.GetInt32() : 50;
                    int start = m.TryGetProperty("before", out var b) ? int.Parse(b.GetString()!) : 0;
                    var newestFirst = Enumerable.Reverse(_history).ToList();
                    var page = newestFirst.Skip(start).Take(limit).ToList();
                    bool more = start + page.Count < newestFirst.Count;
                    var reply = new JsonObject { ["events"] = new JsonArray(page.Select(e => (JsonNode)e.DeepClone()).ToArray()), ["more"] = more };
                    if (more) reply["next"] = (start + page.Count).ToString();
                    return Task.FromResult(Reply.Ok(reply));
                }
            }
            return Err("unknown_command");
        }
    }

    private static JsonObject WithRev(JsonObject value, long rev)
    {
        var o = (JsonObject)value.DeepClone();
        o["rev"] = rev;
        return o;
    }

    private void Log(string type, RequestContext context, int? minutes)
    {
        var e = new JsonObject
        {
            ["time"] = DateTimeOffset.Now.ToString("o"), ["type"] = type, ["source"] = "phone", ["device"] = context.HubName, ["hub"] = context.HubId,
            ["mode"] = type == "started" ? "interval" : null, ["minutes"] = minutes, ["action"] = null,
        };
        _history.Add(e);
        Host!.Publish("ev.history", new JsonObject { ["event"] = e.DeepClone() });
    }

    private Task<Reply> Changed(RequestContext context)
    {
        Host!.Publish("ev.state", new JsonObject { ["state"] = State() }, context.Origin);
        return Ok();
    }

    private Task<Reply> Ok() => Task.FromResult(Reply.Ok(new JsonObject { ["state"] = State() }));
    private static Task<Reply> Err(string code) => Task.FromResult(Reply.Error(code));

    private bool _skipped, _expired;

    private int Remaining() => Math.Max(0, _durationSeconds - (int)_clock.Elapsed.TotalSeconds);

    private JsonObject State()
    {
        if (_clock.IsRunning && Remaining() == 0)
        {
            // Ran out (or extended to zero): like the agent's OnExpired, which also ends a lift
            _clock.Reset();
            _expired = true;
            _skipped = false;
        }
        bool running = _clock.IsRunning;
        int remaining = running ? Remaining() : 0;
        bool scheduleOn = _schedule["enabled"]!.GetValue<bool>();
        return new JsonObject
        {
            ["now"] = DateTimeOffset.Now.ToString("o"),
            ["timer"] = new JsonObject
            {
                ["state"] = running ? "running" : _expired ? "expired" : "stopped",
                ["remainingSeconds"] = remaining,
            },
            ["schedule"] = new JsonObject
            {
                ["status"] = !scheduleOn ? "off" : _skipped ? "skippedToday" : "allowed",
                ["availableSeconds"] = scheduleOn && !_skipped ? 7200 : 0, ["usedSecondsToday"] = 0,
            },
            ["interruption"] = null,
        };
    }
}
