using System.Globalization;
using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Models;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Discovery;
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

    // ---------------- discovery (§9) ----------------

    // Random names, new every UTC day (with the tags) and every app start: nothing on the network stays
    // stable longer than the tags do. A new name sends a goodbye for the old one.
    private (DateOnly Day, string Instance, string Host) _mdnsNames = (default, "", "");

    private (string Instance, string Host) MdnsNamesFor(DateOnly day)
    {
        if (_mdnsNames.Day != day) _mdnsNames = (day, MdnsService.RandomLabel(), MdnsService.RandomLabel());
        return (_mdnsNames.Instance, _mdnsNames.Host);
    }
    private MdnsAdvertiser? _mdns;
    private volatile Tuple<IPAddress, IPAddress>? _mdnsSubnetBox; // (address, mask), written by the service callback

    private (IPAddress Address, IPAddress Mask)? _mdnsSubnet
    {
        get => _mdnsSubnetBox is { } t ? (t.Item1, t.Item2) : null;
        set => _mdnsSubnetBox = value is { } v ? Tuple.Create(v.Address, v.Mask) : null;
    }

    /// <summary>
    /// Announces <c>_sysmon._tcp</c> with today's tags while the server runs, so a parent app finds
    /// the PC again after its address changed. Nothing is announced while no parent app is paired, the
    /// PC has no LAN address, or it is on a network where no parent app has connected yet.
    /// </summary>
    private void StartDiscovery(int port)
    {
        if (_mdns != null) return;
        var mdns = new MdnsAdvertiser(() =>
        {
            var lan = NetworkFilter.GetLanInterface();
            _mdnsSubnet = lan;
            var today = Agent.UtcToday;
            var tags = Agent.DiscoveryTags(today);
            if (lan == null || tags.Count == 0) return null;
            // Only where a parent app already connected: silent on a café's or school's Wi-Fi
            var network = NetworkFilter.GetNetworkId();
            if (network == null) return null;
            // Upgrade from a build without known networks: the PC already has parent apps, so the network it
            // is on is taken as known (otherwise it would stay silent until their next session)
            if (Agent.KnownNetworkCount == 0) Agent.RememberNetwork(network);
            if (!Agent.IsKnownNetwork(network)) return null;
            var (instance, host) = MdnsNamesFor(today);
            return new MdnsService(instance, host, port, new[] { lan.Value.Address }, MdnsService.TxtFor(tags));
        }, ip => NetworkFilter.IsInSubnet(ip, _mdnsSubnet), DiagnosticLog.Write); // only the LAN it announces on
        _mdns = mdns;
        Agent.PeersChanged += mdns.Refresh;
        Agent.SessionStarted += OnV2SessionStarted;
        Agent.Paired += OnV2Paired;
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
        mdns.Start();
    }

    private async Task StopDiscoveryAsync()
    {
        var mdns = _mdns;
        if (mdns == null) return;
        _mdns = null;
        Agent.PeersChanged -= mdns.Refresh;
        Agent.SessionStarted -= OnV2SessionStarted;
        Agent.Paired -= OnV2Paired;
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
        await mdns.StopAsync(); // sends the goodbye
    }

    // A parent app connected from the LAN: this network is a known one from now on (§9)
    private void OnV2SessionStarted(string remoteIp)
    {
        if (!IPAddress.TryParse(remoteIp, out var ip) || NetworkFilter.IsLoopback(ip)) return; // gt-testhub on this PC
        RememberCurrentNetwork(ip);
    }

    // Pairing happens with the phone next to the PC, on this network
    private void OnV2Paired(Peer peer) => RememberCurrentNetwork();

    private void RememberCurrentNetwork(IPAddress? from = null)
    {
        _ = Task.Run(() => // off the session: the gateway lookup (ARP) can take a moment
        {
            try
            {
                if (from != null && !NetworkFilter.IsInSubnet(from, NetworkFilter.GetLanInterface())) return; // not via the LAN interface
                var network = NetworkFilter.GetNetworkId();
                if (network != null && Agent.RememberNetwork(network)) _mdns?.Refresh();
            }
            catch (Exception ex) { DiagnosticLog.Write("mDNS: remembering the network failed: " + ex.Message); }
        });
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        NetworkFilter.ForgetNetworkId();
        _mdns?.Refresh();
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

    /// <summary>A schedule allowed ↔ blocked change: a side effect, never the mirror of a reply (§5).</summary>
    private void V2ScheduleState() =>
        V2Publish("ev.state", () => new JsonObject { ["state"] = _v2Backend!.State() }, sideEffect: true);

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

    private void V2History(SessionEvent e) =>
        V2Publish("ev.history", () => new JsonObject { ["event"] = JsonSerializer.SerializeToNode(e, Json) }, sideEffect: true);

    /// <summary>
    /// §5: the requester gets the <c>result</c> instead of the event that mirrors it (ev.state for timer.*,
    /// ev.settings for settings.put, ev.schedule for schedule.put). Side effects (history, schedule status)
    /// go to every hub, the requester included.
    /// </summary>
    private void V2Publish(string type, Func<JsonObject> body, bool sideEffect = false)
    {
        if (_app == null) return;
        var origin = sideEffect ? null : _v2Backend!.OriginFor(type);
        Agent.Publish(type, body(), origin);
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
            // §5 rev: +1 on every change. Starts at the launch time in ms, so it keeps increasing across restarts.
            var start = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            SettingsRev = start;
            ScheduleRev = start;
            Info = new AgentInfo(Environment.MachineName, "windows", AppInfo.Version);
        }

        public AgentInfo Info { get; }

        public IReadOnlyCollection<string> Capabilities { get; } = new[] { "timer", "schedule", "settings", "history" };

        /// <summary>The request being executed on the UI thread, and its type (UI thread only).</summary>
        private RequestOrigin? _origin;
        private string? _requestType;

        /// <summary>The origin to attribute <paramref name="eventType"/> to: only the event mirroring the current request's reply.</summary>
        public RequestOrigin? OriginFor(string eventType)
        {
            if (_origin == null || _requestType == null) return null;
            bool mirrors = eventType switch
            {
                "ev.state" => _requestType.StartsWith("timer.", StringComparison.Ordinal) || _requestType == "interruption.ack",
                "ev.settings" => _requestType == "settings.put",
                "ev.schedule" => _requestType == "schedule.put",
                _ => false,
            };
            return mirrors ? _origin : null;
        }

        public long SettingsRev { get; set; }
        public long ScheduleRev { get; set; }

        public Task<JsonObject> GetStateAsync() => _s._ui.InvokeAsync(State);

        public Task<Reply> HandleAsync(string type, JsonElement m, RequestContext context) =>
            _s._ui.InvokeAsync(() =>
            {
                var (prevOrigin, prevType) = (_origin, _requestType); // restored: safe even under a nested dispatcher frame
                (_origin, _requestType) = (context.Origin, type);
                try { return Handle(type, m, context); }
                finally { (_origin, _requestType) = (prevOrigin, prevType); }
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
                {
                    // minor 1: skipScheduleToday also sets the daily schedule aside until midnight / a new timer
                    bool skip = m.TryGetProperty("skipScheduleToday", out var sk) && sk.ValueKind == JsonValueKind.True;
                    if (m.TryGetProperty("skipScheduleToday", out sk) && sk.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                        return Reply.Error("bad_request");
                    return t.Lift(skip, ActionSource.Phone, context.HubName) ? Ok("state", State()) : Reply.Error("not_running");
                }

                case "timer.end":
                    return t.EndNow(ActionSource.Phone, context.HubName) ? Ok("state", State()) : Reply.Error("not_running");

                case "timer.setEnd":
                {
                    if (!Int(m, "hours", out var eh) || !Int(m, "minutes", out var em)) return Reply.Error("bad_request");
                    if (eh is < 0 or > 23 || em is < 0 or > 59) return Reply.Error("invalid_time");
                    return t.SetEnd(eh, em, ActionSource.Phone, context.HubName) ? Ok("state", State()) : Reply.Error("not_running");
                }

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

        /// <summary>
        /// §7.2 history.get: newest first (= recording order; times can be backdated or the clock changed),
        /// <c>limit</c> 1–100 (default 50), opaque cursor <c>before</c> = the last event of the previous page.
        /// A cursor whose event has been trimmed from the log → bad_request (start again from the top).
        /// </summary>
        private Reply History(JsonElement m)
        {
            int limit = 50;
            if (m.TryGetProperty("limit", out var l) && (!l.TryGetInt32(out limit) || limit is < 1 or > 100)) return Reply.Error("bad_request");

            var all = _s._sessionLog.Recent(int.MaxValue).Select(e => (Event: e, Json: JsonSerializer.Serialize(e, Json))).ToList();
            // Cursor of entry i = hash of its JSON + which identical copy it is, counted from the newest
            // (two identical entries must not send a page back to itself)
            var seen = new Dictionary<string, int>(StringComparer.Ordinal);
            var cursors = all.Select(x =>
            {
                var h = Hash(x.Json);
                seen[h] = seen.GetValueOrDefault(h) + 1;
                return h + "." + seen[h].ToString(CultureInfo.InvariantCulture);
            }).ToList();

            int start = 0;
            if (Str(m, "before") is { } cursor)
            {
                int at = cursors.IndexOf(cursor);
                if (at < 0) return Reply.Error("bad_request"); // trimmed from the log meanwhile: start from the top
                start = at + 1;
            }
            var page = Enumerable.Range(start, Math.Max(0, Math.Min(limit + 1, all.Count - start))).ToList();
            bool more = page.Count > limit;
            if (more) page.RemoveAt(page.Count - 1);
            var events = new JsonArray();
            foreach (var i in page) events.Add(JsonNode.Parse(all[i].Json));
            var reply = new JsonObject { ["events"] = events, ["more"] = more };
            if (more) reply["next"] = cursors[page[^1]];
            return Reply.Ok(reply);
        }

        private static string Hash(string eventJson) =>
            GameTimer.Protocol.Encodings.Base64Url.Encode(
                System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(eventJson)).AsSpan(0, 12));

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
