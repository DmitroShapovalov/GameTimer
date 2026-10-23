using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Models;
using GameTimer.Protocol.Agent;
using GameTimer.Services.V2;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace GameTimer.Services;

/// <summary>
/// Protocol v2 (docs/protocol.md) next to the v1 phone API: the <c>/v2</c> and <c>/v2/pair</c>
/// WebSocket endpoints feed <see cref="AgentHost"/>, and <see cref="V2Backend"/> maps its requests onto
/// the same <see cref="TimerController"/> / settings as v1. Events go out from the v1 event handlers
/// (RemoteServer.Events.cs), so both protocols see every change.
/// </summary>
public sealed partial class RemoteServer
{
    private static readonly TimeSpan V2TickInterval = TimeSpan.FromSeconds(30); // §7.3 ev.tick
    private long _lastV2TickMs;
    private V2Backend? _v2Backend;

    /// <summary>The v2 agent: identity, paired parent apps, pairing. Lives as long as the app.</summary>
    public AgentHost Agent { get; private set; } = null!;

    /// <summary>Called once from the constructor.</summary>
    private void InitV2(IAgentStore store, IPairingUi pairingUi)
    {
        _v2Backend = new V2Backend(this);
        Agent = new AgentHost(store, _v2Backend, pairingUi);
        Agent.StoreError += ex => DiagnosticLog.Write("v2 store save failed: " + ex.Message);
    }

    private void MapV2(WebApplication app)
    {
        app.Map("/v2", context => AcceptV2Async(context, pairing: false));
        app.Map("/v2/pair", context => AcceptV2Async(context, pairing: true));
    }

    private async Task AcceptV2Async(HttpContext context, bool pairing)
    {
        // §3: browsers are refused (a web page could otherwise open sockets and burn the limits)
        if (context.Request.Headers.Origin.Count > 0)
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            return;
        }
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }
        using var ticket = Agent.TryAdmit(ClientKey(context));
        if (ticket == null)
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        await Agent.RunWebSocketAsync(socket, ticket, pairing, context.RequestAborted); // returns once the socket is closed
    }

    // ---------------- events (UI thread), called from RemoteServer.Events.cs ----------------

    private void V2State() => V2Publish("ev.state", () => new JsonObject { ["state"] = _v2Backend!.State() });

    private void V2Tick()
    {
        var now = Environment.TickCount64;
        if (now - _lastV2TickMs < V2TickInterval.TotalMilliseconds - 250) return;
        _lastV2TickMs = now;
        V2Publish("ev.tick", () => new JsonObject { ["state"] = _v2Backend!.State() });
    }

    private void V2Alert(int minutes) => V2Publish("ev.alert", () => new JsonObject { ["minutes"] = minutes });

    private void V2SettingsChanged(bool settings, bool schedule)
    {
        if (settings)
        {
            _v2Backend!.SettingsRev++;
            V2Publish("ev.settings", () => new JsonObject { ["settings"] = _v2Backend!.Settings() });
        }
        if (schedule)
        {
            _v2Backend!.ScheduleRev++;
            V2Publish("ev.schedule", () => new JsonObject { ["schedule"] = _v2Backend!.Schedule() });
        }
    }

    private void V2History()
    {
        var latest = _sessionLog.Recent(1);
        if (latest.Count == 0) return;
        V2Publish("ev.history", () => new JsonObject { ["event"] = JsonSerializer.SerializeToNode(latest[0], Json) });
    }

    private void V2Publish(string type, Func<JsonObject> body)
    {
        if (_app == null) return;
        Agent.Publish(type, body(), _v2Backend!.CurrentOrigin);
    }

    /// <summary>
    /// The platform side of the v2 agent. Every request runs on the UI thread; while a request calls
    /// into the controller, <see cref="CurrentOrigin"/> is set, so the events it causes are attributed
    /// to it (§5: the requester gets the result, the other parents the event).
    /// </summary>
    private sealed class V2Backend : IAgentBackend
    {
        private readonly RemoteServer _s;

        public V2Backend(RemoteServer server)
        {
            _s = server;
            // §5 rev: +1 on every change. Starts at the launch time so it keeps increasing across restarts.
            var start = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            SettingsRev = start;
            ScheduleRev = start;
            Info = new AgentInfo(Environment.MachineName, "windows", AppInfo.Version);
        }

        public AgentInfo Info { get; }

        public IReadOnlyCollection<string> Capabilities { get; } = new[] { "timer", "schedule", "settings", "history" };

        /// <summary>UI thread only.</summary>
        public RequestOrigin? CurrentOrigin { get; private set; }

        public long SettingsRev { get; set; }
        public long ScheduleRev { get; set; }

        public Task<JsonObject> GetStateAsync() => _s._ui.InvokeAsync(State);

        public Task<Reply> HandleAsync(string type, JsonElement m, RequestContext context) =>
            _s._ui.InvokeAsync(() =>
            {
                CurrentOrigin = context.Origin;
                try { return Handle(type, m, context); }
                finally { CurrentOrigin = null; }
            });

        // UI thread
        private Reply Handle(string type, JsonElement m, RequestContext context)
        {
            var t = _s._timer;
            switch (type)
            {
                case "state.get":
                    return Ok("state", State());

                case "timer.start":
                {
                    var mode = Str(m, "mode") switch { "interval" => TimerMode.Interval, "absolute" => TimerMode.Absolute, _ => (TimerMode?)null };
                    if (mode == null || !Int(m, "hours", out var h) || !Int(m, "minutes", out var min)) return Reply.Error("bad_request");
                    if (t.IsRunning) return Reply.Error("already_running");
                    bool started = mode == TimerMode.Interval
                        ? t.StartInterval(h, min, ActionSource.Phone, context.HubName)
                        : t.StartAbsolute(h, min, ActionSource.Phone, context.HubName);
                    return started ? Ok("state", State()) : Reply.Error("invalid_time");
                }

                case "timer.stop":
                    return t.Stop(ActionSource.Phone, context.HubName) ? Ok("state", State()) : Reply.Error("not_running");

                case "timer.extend":
                    if (!Int(m, "minutes", out var minutes) || minutes == 0 || Math.Abs(minutes) > MaxMinutes) return Reply.Error("invalid_minutes");
                    return t.Extend(minutes, ActionSource.Phone, context.HubName) ? Ok("state", State()) : Reply.Error("not_running");

                case "interruption.ack":
                    t.AcknowledgeInterruption();
                    return Ok("state", State());

                case "settings.get":
                    return Ok("settings", Settings());

                case "settings.put":
                {
                    if (IfRevMismatch(m, SettingsRev)) return Conflict(Settings());
                    SettingsUpdate? update;
                    try { update = JsonSerializer.Deserialize<SettingsUpdate>(m.GetRawText(), Json); }
                    catch (JsonException) { return Reply.Error("bad_request"); }
                    if (update == null) return Reply.Error("bad_request");
                    var error = Validate(update);
                    if (error != null) return Reply.Error(error);
                    _s.Apply(update);
                    _s._settingsService.Save(_s._settings);
                    _s._settingsService.NotifyChanged(_s); // → OnSettingsChanged bumps the rev and publishes
                    return Ok("settings", Settings());
                }

                case "schedule.get":
                    return Ok("schedule", Schedule());

                case "schedule.put":
                {
                    if (IfRevMismatch(m, ScheduleRev)) return Conflict(Schedule());
                    ScheduleDto? update;
                    try { update = JsonSerializer.Deserialize<ScheduleDto>(m.GetRawText(), Json); }
                    catch (JsonException) { return Reply.Error("bad_request"); }
                    var error = _s.ValidateSchedule(update);
                    if (error != null) return Reply.Error(error == "invalid_request" ? "invalid_schedule" : error);
                    _s.ApplySchedule(update!);
                    return Ok("schedule", Schedule());
                }

                case "history.get":
                    return History(m);
            }
            return Reply.Error("unknown_command");
        }

        // ---------------- shapes (§7) ----------------

        public JsonObject State()
        {
            var st = _s.BuildStatus();
            var running = _s._timer.IsRunning;
            return new JsonObject
            {
                ["now"] = DateTimeOffset.Now.ToString("o", CultureInfo.InvariantCulture),
                ["timer"] = new JsonObject
                {
                    ["state"] = Enum(st.State),
                    ["mode"] = st.Mode is { } mode ? Enum(mode) : null,
                    ["remainingSeconds"] = st.RemainingSeconds,
                    ["targetTime"] = st.TargetTime?.ToString("o", CultureInfo.InvariantCulture),
                    ["action"] = Enum(st.Action),
                    ["zone"] = running && st.Zone is { } zone ? Enum(zone) : null,
                },
                ["schedule"] = new JsonObject
                {
                    ["status"] = Enum(st.ScheduleStatus),
                    ["availableSeconds"] = st.ScheduleAvailableSeconds,
                    ["usedSecondsToday"] = st.UsedSecondsToday,
                },
                ["interruption"] = st.LastInterruption?.ToString("o", CultureInfo.InvariantCulture),
            };
        }

        public JsonObject Settings()
        {
            var o = JsonSerializer.SerializeToNode(_s.BuildSettings(), Json)!.AsObject();
            o["rev"] = SettingsRev;
            return o;
        }

        public JsonObject Schedule()
        {
            var o = JsonSerializer.SerializeToNode(_s.BuildSchedule(), Json)!.AsObject();
            o["rev"] = ScheduleRev;
            return o;
        }

        /// <summary>§7.2 history.get: newest first, <c>limit</c> 1–100 (default 50), cursor <c>before</c>.</summary>
        private Reply History(JsonElement m)
        {
            int limit = 50;
            if (m.TryGetProperty("limit", out var l) && (!l.TryGetInt32(out limit) || limit is < 1 or > 100)) return Reply.Error("bad_request");
            DateTimeOffset? before = null;
            if (Str(m, "before") is { } b)
            {
                if (!DateTimeOffset.TryParse(b, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var parsed)) return Reply.Error("bad_request");
                before = parsed;
            }

            var all = _s._sessionLog.Recent(int.MaxValue);
            var page = all.Where(e => before == null || e.Time < before).Take(limit + 1).ToList();
            bool more = page.Count > limit;
            if (more) page.RemoveAt(page.Count - 1);
            var events = new JsonArray();
            foreach (var e in page) events.Add(JsonSerializer.SerializeToNode(e, Json));
            var reply = new JsonObject { ["events"] = events, ["more"] = more };
            if (more) reply["next"] = page[^1].Time.ToString("o", CultureInfo.InvariantCulture);
            return Reply.Ok(reply);
        }

        private static string Enum<T>(T value) where T : struct, System.Enum => JsonNamingPolicy.CamelCase.ConvertName(value.ToString());

        private static Reply Ok(string name, JsonNode value) => Reply.Ok(new JsonObject { [name] = value });

        private static Reply Conflict(JsonObject current) => Reply.Error("conflict", new JsonObject { ["value"] = current });

        private static bool IfRevMismatch(JsonElement m, long rev) =>
            m.TryGetProperty("ifRev", out var r) && (!r.TryGetInt64(out var v) || v != rev);

        private static string? Str(JsonElement e, string name) =>
            e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

        private static bool Int(JsonElement e, string name, out int value)
        {
            value = 0;
            return e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out value);
        }
    }
}
