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
    public const int ProtocolMinor = 0;

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
            store.Save(_state);
        }
        if (priv != null) CryptographicOperations.ZeroMemory(priv);
        AgentId = Ids.AgentId(_key.PublicKey);
    }

    /// <summary>The agent's static public key (goes into the pairing QR as <c>k</c>).</summary>
    public byte[] AgentPublicKey => _key.PublicKey;

    public string AgentId { get; }

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
    /// Publishes an event (§7.3) to every live session; any thread. Pass <paramref name="causedBy"/>
    /// (<see cref="RequestContext.Origin"/>) when the event is the direct effect of a hub request: the
    /// requester then doesn't get it, and the others get it after the requester has its result (§5).
    /// Events from timers, the PC or anything later pass null and reach every session.
    /// </summary>
    public void Publish(string type, JsonObject body, RequestOrigin? causedBy = null)
    {
        if (!type.StartsWith("ev.", StringComparison.Ordinal)) throw new ArgumentException("events start with ev.", nameof(type));
        var message = new JsonObject { ["t"] = type };
        foreach (var (k, v) in body) message[k] = v?.DeepClone();

        if (causedBy != null && causedBy.TryDefer(null, message)) return;
        foreach (var s in LiveSessions())
            if (s != causedBy?.Session) s.Enqueue((JsonObject)message.DeepClone());
    }

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

    internal void Release(HandshakeTicket ticket)
    {
        lock (_lock)
        {
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
        lock (_saveLock)
        {
            if (seq <= _savedSeq) return; // a newer snapshot is already on disk
            try { _store.Save(snapshot); }
            catch (Exception ex) { StoreError?.Invoke(ex); }
            _savedSeq = seq;
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
        foreach (var s in LiveSessions())
        {
            if (origin?.Session == s) continue;
            var msg = new JsonObject { ["t"] = "ev.peers", ["peers"] = PeersJson(s.HubId), ["change"] = change, ["peer"] = hubId };
            if (origin == null || !origin.TryDefer(s, msg)) s.Enqueue(msg);
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

/// <summary>A reserved handshake slot (§3). Disposing it releases the slot; safe to dispose twice.</summary>
public sealed class HandshakeTicket : IDisposable
{
    private readonly AgentHost _host;
    private int _released;

    internal HandshakeTicket(AgentHost host, string remoteIp)
    {
        _host = host;
        RemoteIp = remoteIp;
    }

    public string RemoteIp { get; }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _released, 1) == 0) _host.Release(this);
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
/// Identifies one hub request while it runs (§5 ordering). The backend passes it to
/// <see cref="AgentHost.Publish"/> for events its request caused; they are held until the requester
/// has its result. Once the result is out, the origin is closed and such events go out at once.
/// Explicit on purpose: nothing flows implicitly into timers or tasks the backend starts.
/// </summary>
public sealed class RequestOrigin
{
    private readonly object _gate = new();
    private List<(AgentHost.Session? To, JsonObject Message)>? _deferred = new();
    private string? _byeAfter;

    internal RequestOrigin(AgentHost.Session session) => Session = session;

    internal AgentHost.Session Session { get; }

    /// <summary>Holds <paramref name="message"/> for <paramref name="to"/> (null = every other session). False once closed.</summary>
    internal bool TryDefer(AgentHost.Session? to, JsonObject message)
    {
        lock (_gate)
        {
            if (_deferred == null) return false;
            _deferred.Add((to, message));
            return true;
        }
    }

    /// <summary>The requester itself must be closed after its reply (self-revoke). False once closed.</summary>
    internal bool TrySetByeAfter(string code)
    {
        lock (_gate)
        {
            if (_deferred == null) return false;
            _byeAfter = code;
            return true;
        }
    }

    /// <summary>Ends the request: returns what was held, and lets later events through directly.</summary>
    internal (List<(AgentHost.Session? To, JsonObject Message)> Deferred, string? ByeAfter) Close()
    {
        lock (_gate)
        {
            var d = _deferred ?? new();
            _deferred = null;
            return (d, _byeAfter);
        }
    }
}
