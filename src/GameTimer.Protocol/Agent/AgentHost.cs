using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Noise;

namespace GameTimer.Protocol.Agent;

/// <summary>
/// The agent side of protocol v2 (docs/protocol.md), independent of the web server and the UI:
/// handshakes and the <c>ready</c> rule, sessions and their limits, pairing, peers and roles, the
/// idempotency cache, and event fan-out. The Windows app feeds it WebSocket connections and
/// implements <see cref="IAgentBackend"/> and <see cref="IPairingUi"/>.
/// Thread-safe: all shared state is guarded by one lock; sends go through per-session outboxes.
/// </summary>
public sealed partial class AgentHost : IDisposable
{
    public const int ProtocolMajor = 2;
    public const int ProtocolMinor = 1; // 1: timer.end, timer.setEnd, timer.stop skipScheduleToday, schedule status skippedToday

    // §3 limits
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(90);
    public static readonly TimeSpan SessionLifetime = TimeSpan.FromHours(24);
    public const int MaxPendingHandshakes = 8;
    public const int MaxPendingPerIp = 2;
    public const int MaxSessionsPerHub = 2;
    public const int MaxSessions = 16;
    public const int MaxFailedHandshakesPerMinute = 10;

    // §8.2 pairing
    public static readonly TimeSpan PairingWindow = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan PairingConfirmTimeout = TimeSpan.FromSeconds(60);
    public const int MaxPairingAttempts = 3;

    // §5 idempotency
    public static readonly TimeSpan RidLifetime = TimeSpan.FromMinutes(10);
    public const int MaxRidsPerHub = 256;

    internal static readonly byte[] Prologue = "GameTimer/2"u8.ToArray();

    internal static readonly JsonSerializerOptions StoreJson = new()
    {
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly object _lock = new();
    private readonly IAgentStore _store;
    private readonly IAgentBackend _backend;
    private readonly IPairingUi _pairingUi;
    private readonly TimeProvider _time;
    private readonly AgentState _state;
    private readonly KeyPair _key;
    private readonly List<Session> _sessions = new();
    private readonly Dictionary<string, int> _pendingPerIp = new();
    private int _pendingTotal;
    private readonly Dictionary<string, Queue<DateTimeOffset>> _failures = new();
    private PairingOffer? _pairing;
    private bool _disposed;

    /// <summary>Peers were added, revoked or changed role (any thread). The PC's settings page refreshes.</summary>
    public event Action? PeersChanged;

    /// <summary>A hub completed pairing (any thread), also when a known hub paired again.</summary>
    public event Action<Peer>? Paired;

    public AgentHost(IAgentStore store, IAgentBackend backend, IPairingUi pairingUi, TimeProvider? time = null)
    {
        _store = store;
        _backend = backend;
        _pairingUi = pairingUi;
        _time = time ?? TimeProvider.System;

        var loaded = store.Load();
        var priv = loaded == null ? null : Base64Url.TryDecode(loaded.PrivateKey);
        if (loaded != null && priv is { Length: 32 })
        {
            _state = loaded;
            _key = KeyPair.FromPrivateKey(priv);
        }
        else
        {
            // First run (or an unreadable store): a new identity. Existing hubs must pair again.
            _key = KeyPair.Generate();
            _state = new AgentState { PrivateKey = Base64Url.Encode(_key.PrivateKey) };
            try { store.Save(_state); }
            catch (Exception ex) { InitialSaveError = ex; } // keep running with the identity in memory
        }
        if (priv != null) CryptographicOperations.ZeroMemory(priv);
        AgentId = Ids.AgentId(_key.PublicKey);
    }

    /// <summary>The agent's static public key (goes into the pairing QR as <c>k</c>).</summary>
    public byte[] AgentPublicKey => _key.PublicKey;

    public string AgentId { get; }

    /// <summary>
    /// A new identity was created but could not be saved (e.g. disk full): it lives only until the app
    /// exits, and the next start creates another one. Null when the identity is on disk.
    /// </summary>
    public Exception? InitialSaveError { get; }

    internal IReadOnlyCollection<string> Capabilities
    {
        get
        {
            var caps = new List<string>(_backend.Capabilities.Where(c => c != "peers")) { "peers" };
            return caps;
        }
    }

    // ---------------- PC-side API ----------------

    /// <summary>A copy of the paired hubs, for the PC's settings page.</summary>
    public IReadOnlyList<Peer> Peers
    {
        get { lock (_lock) return _state.Peers.Select(Clone).ToList(); }
    }

    /// <summary>
    /// The discovery tags (§9) for <paramref name="utcDate"/>: one per paired hub, in pairing order.
    /// They change when <see cref="PeersChanged"/> fires and at the UTC date change.
    /// </summary>
    public IReadOnlyList<string> DiscoveryTags(DateOnly utcDate)
    {
        lock (_lock)
        {
            var tags = new List<string>(_state.Peers.Count);
            foreach (var p in _state.Peers)
            {
                var ak = Base64Url.TryDecode(p.Ak);
                if (ak is not { Length: 32 }) continue;
                tags.Add(DiscoveryTag.Derive(ak, utcDate));
                CryptographicOperations.ZeroMemory(ak);
            }
            return tags;
        }
    }

    /// <summary>At most this many route claims on one relay socket (§10.4): 21 hubs × 3 days.</summary>
    public const int MaxRouteTokens = 63;

    /// <summary>
    /// The relay route claims (§10.1) to register around <paramref name="utcToday"/>: yesterday's, today's
    /// and tomorrow's for each paired hub, so that the phone's date and a PC clock that is somewhat off still
    /// meet. Hubs used most recently come first (last seen, else paired); beyond <see cref="MaxRouteTokens"/>
    /// the least recently used hubs are left out, whole. The order inside the result is shuffled, so the relay
    /// can't tell which three belong together from their position (§10.5).
    /// They change when <see cref="PeersChanged"/> fires and at the UTC date change.
    /// </summary>
    public IReadOnlyList<byte[]> RouteClaims(DateOnly utcToday) => RelayRoutes(utcToday).Select(r => r.Claim).ToList();

    /// <summary>
    /// The same set as <see cref="RouteClaims"/>, each with its public route (base32, as the relay names it
    /// in <c>open</c>) and the hub it belongs to, so a relayed stream can be bound to that hub's key (§10.4).
    /// </summary>
    public IReadOnlyList<RelayRoute> RelayRoutes(DateOnly utcToday)
    {
        lock (_lock)
        {
            var routes = new List<RelayRoute>();
            var byUse = _state.Peers.Select((p, i) => (p, i))
                .OrderByDescending(x => x.p.LastSeen ?? x.p.PairedAt).ThenByDescending(x => x.i)
                .Select(x => x.p);
            foreach (var p in byUse)
            {
                if (routes.Count + 3 > MaxRouteTokens) break;
                var ak = Base64Url.TryDecode(p.Ak);
                if (ak is not { Length: 32 }) continue;
                for (int d = -1; d <= 1; d++)
                {
                    var claim = RouteToken.Claim(ak, utcToday.AddDays(d));
                    routes.Add(new RelayRoute(claim, Base32.Encode(RouteToken.Route(claim)), p.Id));
                }
                CryptographicOperations.ZeroMemory(ak);
            }
            RandomNumberGenerator.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(routes));
            return routes;
        }
    }

    /// <summary>
    /// "Control from anywhere" (§10.2) is on: reported to hubs in <c>hello</c> (and by the app in
    /// <c>settings</c>). Set by the app; the relay connection itself is the app's <c>RelayClient</c>.
    /// </summary>
    public bool RemoteAccess { get => Volatile.Read(ref _remoteAccess); set => Volatile.Write(ref _remoteAccess, value); }
    private bool _remoteAccess;

    /// <summary>
    /// Ends every session that came through the relay with <c>bye</c> <paramref name="code"/> (§10.2: remote_off)
    /// and waits (at most 2 s) until they are out, so the bye isn't lost when the relay connection stops next.
    /// </summary>
    public async Task EndRelaySessionsAsync(string code = "remote_off")
    {
        var relayed = LiveSessions().Where(s => s.Relayed).ToList();
        foreach (var s in relayed) s.Bye(code);
        await Task.WhenAny(Task.WhenAll(relayed.Select(s => s.Completion)), Task.Delay(TimeSpan.FromSeconds(2), _time)).ConfigureAwait(false);
    }

    public const int MaxKnownNetworks = 16;

    /// <summary>
    /// A hub's session started (hello sent), with its remote address; any thread. The app uses it to
    /// remember the network (<see cref="RememberNetwork"/>).
    /// </summary>
    public event Action<string>? SessionStarted;

    internal void RaiseSessionStarted(string remoteIp)
    {
        if (SessionStarted is not { } handlers) return;
        foreach (var h in handlers.GetInvocationList().Cast<Action<string>>())
        {
            try { h(remoteIp); }
            catch (Exception) { /* an app handler must not break a session, nor the other handlers */ }
        }
    }

    /// <summary>How many networks are known (<see cref="RememberNetwork"/>).</summary>
    public int KnownNetworkCount
    {
        get { lock (_lock) return _state.KnownNetworks.Count; }
    }

    /// <summary>True if a hub had a session on network <paramref name="id"/> (an id the app chose).</summary>
    public bool IsKnownNetwork(string id)
    {
        lock (_lock) return _state.KnownNetworks.Contains(id);
    }

    /// <summary>
    /// Remembers network <paramref name="id"/> (most recent last; the oldest is dropped beyond
    /// <see cref="MaxKnownNetworks"/>). Returns true if it was new. Persists only when something changed.
    /// </summary>
    public bool RememberNetwork(string id)
    {
        if (string.IsNullOrEmpty(id) || id.Length > 128) return false;
        bool added;
        lock (_lock)
        {
            var list = _state.KnownNetworks;
            int at = list.IndexOf(id);
            if (at == list.Count - 1 && at >= 0) return false; // already the most recent
            added = at < 0;
            if (!added) list.RemoveAt(at);
            list.Add(id);
            while (list.Count > MaxKnownNetworks) list.RemoveAt(0);
        }
        Persist();
        return added;
    }

    /// <summary>Today's UTC date by this host's clock (for <see cref="DiscoveryTags"/> and <see cref="RouteClaims"/>).</summary>
    public DateOnly UtcToday => DateOnly.FromDateTime(_time.GetUtcNow().UtcDateTime);

    /// <summary>
    /// The PC owner revokes a hub (behind Windows Hello in the app). Allowed even for the last admin
    /// (§8.1: e.g. a lost phone); the next hub paired then becomes admin.
    /// </summary>
    public bool RevokeByOwner(string hubId) => Revoke(hubId, origin: null, protectLastAdmin: false) == PeerChange.Done;

    /// <summary>The PC owner changes a hub's role (no last-admin protection on the PC, §8.1).</summary>
    public bool SetRoleByOwner(string hubId, PeerRole role) =>
        SetRole(hubId, role, origin: null, protectLastAdmin: false) == PeerChange.Done;

    /// <summary>Persisting the state failed (disk full, DPAPI error). The change stays in memory; the app should log it.</summary>
    public event Action<Exception>? StoreError;

    /// <summary>
    /// Opens a pairing window (§8.2): a fresh one-time secret, valid 2 minutes. Replaces any previous
    /// offer and aborts attempts still in progress for it. The QR fragment is <see cref="PairingOffer.QrFragment"/>.
    /// </summary>
    public PairingOffer StartPairing(string pcName)
    {
        PairingOffer offer;
        PairingOffer? old;
        lock (_lock)
        {
            old = _pairing;
            offer = new PairingOffer(RandomNumberGenerator.GetBytes(32), _time.GetUtcNow() + PairingWindow, _key.PublicKey, pcName);
            _pairing = offer;
        }
        old?.Abort();
        return offer;
    }

    /// <summary>Closes the pairing window (dialog closed); attempts in progress get <c>bye denied</c>.</summary>
    public void CancelPairing()
    {
        PairingOffer? old;
        lock (_lock)
        {
            old = _pairing;
            _pairing = null;
        }
        old?.Abort();
    }

    /// <summary>
    /// Publishes an event (§7.3) to every live session, at once and in call order; any thread. Pass
    /// <paramref name="causedBy"/> (<see cref="RequestContext.Origin"/>) when the event is the direct
    /// effect of a hub request: that requester gets its <c>result</c> instead (§5). Events from timers,
    /// the PC or anything later pass null and reach every session.
    /// </summary>
    public void Publish(string type, JsonObject body, RequestOrigin? causedBy = null)
    {
        if (!type.StartsWith("ev.", StringComparison.Ordinal)) throw new ArgumentException("events start with ev.", nameof(type));
        var message = new JsonObject { ["t"] = type };
        foreach (var (k, v) in body) message[k] = v?.DeepClone();
        // One fan-out at a time, so every session sees events in the same order even when several
        // threads publish (Enqueue never blocks)
        lock (_publishLock)
            foreach (var s in LiveSessions())
                if (s != causedBy?.Session) s.Enqueue((JsonObject)message.DeepClone());
    }

    private readonly object _publishLock = new();

    /// <summary>Says <c>bye shutdown</c> to every session (app exit or update).</summary>
    public async Task ShutdownAsync()
    {
        foreach (var s in LiveSessions()) s.Bye("shutdown");
        var all = LiveSessions().Select(s => s.Completion).ToArray();
        await Task.WhenAny(Task.WhenAll(all), Task.Delay(TimeSpan.FromSeconds(2), _time)).ConfigureAwait(false);
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_disposed) return;
            _disposed = true;
        }
        CancelPairing();
        foreach (var s in LiveSessions()) s.Abort();
        _key.Dispose();
    }

    // ---------------- admission (§3), called by the web endpoint before accepting the upgrade ----------------

    /// <summary>
    /// Reserves one of the handshake slots (8 total, 2 per remote IP). Null → answer the upgrade with 503.
    /// The ticket is passed to <see cref="RunAsync"/>, which releases it when the handshake ends.
    /// </summary>
    public HandshakeTicket? TryAdmit(string remoteIp)
    {
        lock (_lock)
        {
            if (_disposed) return null;
            _pendingPerIp.TryGetValue(remoteIp, out var n);
            if (_pendingTotal >= MaxPendingHandshakes || n >= MaxPendingPerIp) return null;
            _pendingPerIp[remoteIp] = n + 1;
            _pendingTotal++;
            return new HandshakeTicket(this, remoteIp);
        }
    }

    /// <summary>Relay streams waiting for a handshake: their own pool, never LAN slots (§10.4).</summary>
    public const int MaxRelayPending = 4;
    public const int MaxRelayPendingPerHub = 2;

    /// <summary>A relayed stream must bring msg 1 this soon after <c>open</c> (§10.4).</summary>
    public static readonly TimeSpan RelayFirstMessageTimeout = TimeSpan.FromSeconds(2);

    private readonly List<HandshakeTicket> _relayPending = new();

    /// <summary>
    /// Reserves a handshake slot for a stream the relay opened with one of <paramref name="hubId"/>'s routes
    /// (§10.4): at most 2 per hub and 4 in total, apart from the LAN slots. A full pool evicts instead of
    /// refusing, so silent streams can't keep hubs out: the hub's own oldest waiting stream if it has one,
    /// else the oldest of the hub with the most waiting — never the only stream of another hub while that
    /// can be avoided. A stream leaves the pool once its msg 1 proved the hub's key (§10.4), so a real
    /// handshake is never evicted after that. Null when "Control from anywhere" is off or the host is disposed.
    /// </summary>
    public HandshakeTicket? TryAdmitRelay(string hubId)
    {
        HandshakeTicket? evict = null, ticket;
        lock (_lock)
        {
            if (_disposed || !RemoteAccess) return null;
            var mine = _relayPending.Where(t => t.RelayHubId == hubId).ToList();
            if (mine.Count > 0 && (mine.Count >= MaxRelayPendingPerHub || _relayPending.Count >= MaxRelayPending))
                evict = mine[0];
            else if (_relayPending.Count >= MaxRelayPending)
                evict = _relayPending.GroupBy(t => t.RelayHubId).OrderByDescending(g => g.Count()).First().First();
            if (evict != null) _relayPending.Remove(evict);
            ticket = new HandshakeTicket(this, "relay:" + hubId, hubId);
            _relayPending.Add(ticket);
        }
        evict?.Evict();
        return ticket;
    }

    internal int RelayPendingCount { get { lock (_lock) return _relayPending.Count; } }

    internal void Release(HandshakeTicket ticket)
    {
        lock (_lock)
        {
            if (ticket.RelayHubId != null)
            {
                _relayPending.Remove(ticket);
                return;
            }
            _pendingTotal--;
            if (--_pendingPerIp[ticket.RemoteIp] == 0) _pendingPerIp.Remove(ticket.RemoteIp);
        }
    }

    private void RecordFailure(string ip)
    {
        lock (_lock)
        {
            if (!_failures.TryGetValue(ip, out var q))
            {
                if (_failures.Count >= MaxTrackedIps) SweepFailures();
                _failures[ip] = q = new Queue<DateTimeOffset>();
            }
            q.Enqueue(_time.GetUtcNow());
            Trim(q);
        }
    }

    private bool OverFailureLimit(string ip)
    {
        lock (_lock)
        {
            if (!_failures.TryGetValue(ip, out var q)) return false;
            Trim(q);
            if (q.Count == 0) _failures.Remove(ip);
            return q.Count >= MaxFailedHandshakesPerMinute;
        }
    }

    /// <summary>Bounds the per-IP counters (an IPv6 LAN offers many source addresses). Under the lock.</summary>
    private void SweepFailures()
    {
        foreach (var (ip, q) in _failures.ToList())
        {
            Trim(q);
            if (q.Count == 0) _failures.Remove(ip);
        }
        // Still full of active addresses: forget the ones that failed longest ago
        foreach (var ip in _failures.OrderBy(kv => kv.Value.Last()).Take(_failures.Count - MaxTrackedIps / 2).Select(kv => kv.Key).ToList())
            _failures.Remove(ip);
    }

    private const int MaxTrackedIps = 1024;

    private void Trim(Queue<DateTimeOffset> q)
    {
        var cutoff = _time.GetUtcNow() - TimeSpan.FromMinutes(1);
        while (q.Count > 0 && q.Peek() < cutoff) q.Dequeue();
    }

    // ---------------- peers (§8.1) ----------------

    private Peer? FindPeerByKey(byte[] publicKey)
    {
        var encoded = Base64Url.Encode(publicKey);
        lock (_lock) return _state.Peers.FirstOrDefault(p => p.PublicKey == encoded);
    }

    private Peer? FindPeer(string id)
    {
        lock (_lock) return _state.Peers.FirstOrDefault(p => p.Id == id);
    }

    private enum PeerChange { Done, NotFound, LastAdmin }

    /// <summary>
    /// Removes a hub: closes its sessions (bye revoked), drops its ak and cached replies, tells the others.
    /// The last-admin check and the removal happen under one lock, so two admins revoking each other
    /// at once can't leave none.
    /// </summary>
    private PeerChange Revoke(string hubId, RequestOrigin? origin, bool protectLastAdmin)
    {
        List<Session> closing;
        lock (_lock)
        {
            var peer = _state.Peers.FirstOrDefault(p => p.Id == hubId);
            if (peer == null) return PeerChange.NotFound;
            if (protectLastAdmin && IsLastAdminLocked(peer)) return PeerChange.LastAdmin;
            _state.Peers.Remove(peer);
            _state.Rids.Remove(hubId);
            _ridLocks.Remove(hubId);
            closing = _sessions.Where(s => s.HubId == hubId).ToList();
        }
        Persist();
        foreach (var s in closing)
        {
            // A hub that revokes itself gets its result first, then the bye (§8.1)
            if (origin?.Session == s && origin.TrySetByeAfter("revoked")) continue;
            s.Bye("revoked");
        }
        PublishPeers("revoked", hubId, origin);
        PeersChanged?.Invoke();
        return PeerChange.Done;
    }

    private PeerChange SetRole(string hubId, PeerRole role, RequestOrigin? origin, bool protectLastAdmin)
    {
        lock (_lock)
        {
            var peer = _state.Peers.FirstOrDefault(p => p.Id == hubId);
            if (peer == null) return PeerChange.NotFound;
            if (peer.Role == role) return PeerChange.Done;
            if (protectLastAdmin && role != PeerRole.Admin && IsLastAdminLocked(peer)) return PeerChange.LastAdmin;
            peer.Role = role;
            foreach (var s in _sessions.Where(s => s.HubId == hubId)) s.Role = role;
        }
        Persist();
        PublishPeers("role", hubId, origin);
        PeersChanged?.Invoke();
        return PeerChange.Done;
    }

    private bool IsLastAdminLocked(Peer peer) =>
        peer.Role == PeerRole.Admin && _state.Peers.Count(p => p.Role == PeerRole.Admin) == 1;

    // ---------------- persistence: snapshot under the lock, write outside it, newest wins ----------------

    private readonly object _saveLock = new();
    private long _saveSeq;
    private long _savedSeq;

    /// <summary>Saves the current state. Call outside <c>_lock</c>: the store may write to disk (DPAPI).</summary>
    private void Persist()
    {
        AgentState snapshot;
        long seq;
        lock (_lock)
        {
            snapshot = JsonSerializer.Deserialize<AgentState>(JsonSerializer.SerializeToUtf8Bytes(_state, StoreJson), StoreJson)!;
            seq = ++_saveSeq;
        }
        Exception? error = null;
        lock (_saveLock)
        {
            if (seq <= _savedSeq) return; // a newer snapshot is already on disk
            try { _store.Save(snapshot); }
            catch (Exception ex) { error = ex; }
            _savedSeq = seq;
        }
        // Outside the lock: a handler that marshals to the UI thread can't deadlock with a UI-thread Persist
        if (error != null)
        {
            try { StoreError?.Invoke(error); }
            catch { /* the app's logger failed; the state is still in memory */ }
        }
    }

    /// <summary><c>peers[]</c> as seen by one hub (<c>current</c> marks that hub).</summary>
    private JsonArray PeersJson(string forHubId)
    {
        lock (_lock)
        {
            var arr = new JsonArray();
            foreach (var p in _state.Peers)
            {
                arr.Add(new JsonObject
                {
                    ["id"] = p.Id,
                    ["name"] = p.Name,
                    ["role"] = RoleName(p.Role),
                    ["pairedAt"] = p.PairedAt.ToString("o"),
                    ["lastSeen"] = p.LastSeen?.ToString("o"),
                    ["current"] = p.Id == forHubId,
                });
            }
            return arr;
        }
    }

    /// <summary>ev.peers is per session (each hub sees its own <c>current</c>), so it bypasses <see cref="Publish"/>.</summary>
    private void PublishPeers(string change, string hubId, RequestOrigin? origin)
    {
        lock (_publishLock)
            foreach (var s in LiveSessions())
            {
                if (origin?.Session == s) continue;
                s.Enqueue(new JsonObject { ["t"] = "ev.peers", ["peers"] = PeersJson(s.HubId), ["change"] = change, ["peer"] = hubId });
            }
    }

    internal static string RoleName(PeerRole role) => role == PeerRole.Admin ? "admin" : "parent";

    private static Peer Clone(Peer p) => new()
    {
        Id = p.Id, PublicKey = p.PublicKey, Name = p.Name, Role = p.Role,
        PairedAt = p.PairedAt, LastSeen = p.LastSeen, Ak = p.Ak,
    };

    private List<Session> LiveSessions()
    {
        lock (_lock) return _sessions.ToList();
    }

    private JsonObject AgentJson() => new()
    {
        ["id"] = AgentId,
        ["name"] = _backend.Info.Name,
        ["platform"] = _backend.Info.Platform,
        ["version"] = _backend.Info.Version,
    };
}

/// <summary>A reserved handshake slot (§3, §10.4). Disposing it releases the slot; safe to dispose twice.</summary>
public sealed class HandshakeTicket : IDisposable
{
    private readonly AgentHost _host;
    private readonly CancellationTokenSource _evicted = new();
    private int _released;

    internal HandshakeTicket(AgentHost host, string remoteIp, string? relayHubId = null)
    {
        _host = host;
        RemoteIp = remoteIp;
        RelayHubId = relayHubId;
    }

    /// <summary>The remote IP, or <c>relay:&lt;hub id&gt;</c> for a relayed stream (its failure-counter key).</summary>
    public string RemoteIp { get; }

    /// <summary>For a relayed stream: the hub whose route the relay opened it with; its key must match (§10.4).</summary>
    public string? RelayHubId { get; }

    /// <summary>Fires when a newer relayed stream took this slot (the handshake is abandoned).</summary>
    public CancellationToken Evicted => _evicted.Token;

    internal void Evict()
    {
        try { _evicted.Cancel(); } catch (ObjectDisposedException) { }
    }

    /// <summary>A relayed stream's msg 1 proved the hub's key: its pool slot is free again (§10.4).</summary>
    internal void Proven() => Dispose();

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _released, 1) == 0) _host.Release(this);
    }

    /// <summary>Called once the handshake is over either way (the eviction token is no longer needed).</summary>
    internal void DisposeEviction()
    {
        Dispose();
        _evicted.Dispose();
    }
}

/// <summary>An open pairing window (§8.2): the one-time secret and what goes into the QR.</summary>
public sealed class PairingOffer
{
    private readonly CancellationTokenSource _abort = new();

    internal PairingOffer(byte[] secret, DateTimeOffset expiresAt, byte[] agentPublicKey, string pcName)
    {
        Secret = secret;
        ExpiresAt = expiresAt;
        AgentPublicKey = agentPublicKey;
        PcName = pcName;
    }

    internal byte[] Secret { get; }
    public DateTimeOffset ExpiresAt { get; }
    public byte[] AgentPublicKey { get; }
    public string PcName { get; }

    /// <summary>The hub key that first reached pair.hello with this secret (base64url), if any.</summary>
    internal string? UsedBy { get; set; }
    internal int Attempts { get; set; }
    /// <summary>The attempt currently waiting for the owner's code (one per phone key).</summary>
    internal CancellationTokenSource? ActiveAttempt { get; set; }
    internal CancellationToken Aborted => _abort.Token;

    /// <summary>URL fragment for the QR: <c>v=2&amp;k=…&amp;s=…&amp;n=…</c> (without the leading <c>#</c>).</summary>
    public string QrFragment =>
        $"v=2&k={Base64Url.Encode(AgentPublicKey)}&s={Base64Url.Encode(Secret)}&n={Uri.EscapeDataString(PcName)}";

    internal void Abort()
    {
        try { _abort.Cancel(); } catch (ObjectDisposedException) { }
    }
}

/// <summary>
/// Identifies one hub request while it runs (§5). The backend passes it to <see cref="AgentHost.Publish"/>
/// for events its request caused, so the requester (which gets the result instead) is left out.
/// Explicit on purpose: nothing flows implicitly into timers or tasks the backend starts.
/// </summary>
public sealed class RequestOrigin
{
    private readonly object _gate = new();
    private bool _closed;
    private string? _byeAfter;

    internal RequestOrigin(AgentHost.Session session) => Session = session;

    internal AgentHost.Session Session { get; }

    /// <summary>The requester itself must be closed after its reply (self-revoke). False once closed.</summary>
    internal bool TrySetByeAfter(string code)
    {
        lock (_gate)
        {
            if (_closed) return false;
            _byeAfter = code;
            return true;
        }
    }

    /// <summary>Ends the request: returns a bye the requester must get after its reply, if any.</summary>
    internal string? Close()
    {
        lock (_gate)
        {
            _closed = true;
            return _byeAfter;
        }
    }
}

/// <summary>One relay route of a hub (§10.1): the claim to register, its public route (base32), the hub.</summary>
public sealed record RelayRoute(byte[] Claim, string Route, string HubId);
