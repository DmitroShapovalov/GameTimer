namespace GameTimer.Protocol.Agent;

/// <summary>Role of a hub on this agent (§8.1). On the wire and on disk: "admin" / "parent".</summary>
public enum PeerRole
{
    Admin,
    Parent,
}

/// <summary>A paired hub (parent app) as the agent stores it (protocol §8).</summary>
public sealed class Peer
{
    public required string Id { get; init; }
    /// <summary>The hub's static X25519 public key, base64url.</summary>
    public required string PublicKey { get; init; }
    public required string Name { get; set; }
    public required PeerRole Role { get; set; }
    public required DateTimeOffset PairedAt { get; init; }
    public DateTimeOffset? LastSeen { get; set; }
    /// <summary>This hub's own secret <c>ak</c> (§4.4), base64url.</summary>
    public required string Ak { get; init; }
    /// <summary>
    /// The hub's push token (FCM registration token, §10.6), set by <c>push.set</c>; used only to wake it
    /// through the relay while "Control from anywhere" is on. Never shown or sent to other hubs.
    /// </summary>
    public string? PushToken { get; set; }
    /// <summary>What may wake this hub (§10.6): history event types and <c>peerAdded</c>; null = all of them.</summary>
    public List<string>? PushEvents { get; set; }
}

/// <summary>One idempotency entry (§5): rid → (body hash, stored reply without <c>re</c>).</summary>
public sealed class RidEntry
{
    public required string Rid { get; init; }
    public required string BodyHash { get; init; }
    public required string Reply { get; init; }
    public required DateTimeOffset At { get; init; }
}

/// <summary>Everything the agent persists for protocol v2. The host saves it through <see cref="IAgentStore"/>.</summary>
public sealed class AgentState
{
    /// <summary>The agent's static X25519 private key, base64url. Never leaves the device.</summary>
    public required string PrivateKey { get; init; }
    public List<Peer> Peers { get; init; } = new();
    /// <summary>Idempotency cache per hub id.</summary>
    public Dictionary<string, List<RidEntry>> Rids { get; init; } = new();
    /// <summary>
    /// Networks a hub had a session on, as opaque ids the app chooses (Windows: a hash of the gateway).
    /// Discovery (§9) is announced only there. Oldest first, at most <see cref="AgentHost.MaxKnownNetworks"/>.
    /// </summary>
    public List<string> KnownNetworks { get; init; } = new();
}

/// <summary>Persistence for <see cref="AgentState"/> (Windows: DPAPI-protected file). Called under the host lock.</summary>
public interface IAgentStore
{
    AgentState? Load();
    void Save(AgentState state);
}

/// <summary>In-memory store for tests and tools.</summary>
public sealed class MemoryAgentStore : IAgentStore
{
    public AgentState? State { get; set; }
    public int Saves { get; private set; }
    public AgentState? Load() => State;
    public void Save(AgentState state) { State = state; Saves++; }
}
