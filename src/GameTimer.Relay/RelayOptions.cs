namespace GameTimer.Relay;

/// <summary>Limits and timeouts of the relay (docs/protocol.md §10.2–§10.4). Section "Relay" in configuration.</summary>
public sealed class RelayOptions
{
    /// <summary>
    /// The header that carries the client's address when the relay runs behind a proxy (Fly.io: "Fly-Client-IP").
    /// Unset: the TCP peer address is used. Never set it without a proxy that overwrites the header.
    /// </summary>
    public string? ClientIpHeader { get; set; }

    public int NewSocketsPerIpPerSecond { get; set; } = 10;
    public int HubSocketsPerIp { get; set; } = 32;   // mobile carriers put many phones behind one address
    public int AgentSocketsPerIp { get; set; } = 8;
    public int NotFoundPerIpPerMinute { get; set; } = 60;
    public int StreamsPerAgent { get; set; } = 16;
    public int BufferPerStream { get; set; } = 64 * 1024;
    /// <summary>Queued frames to one agent that aren't hub data (pong, open, close); beyond it → 1008.</summary>
    public int AgentControlBytes { get; set; } = 16 * 1024;

    // Totals, sized for one small machine (shared-cpu-1x, 256 MB; the GC heap is capped in the Dockerfile)
    public int MaxAgentSockets { get; set; } = 400;
    public int MaxHubSockets { get; set; } = 600;
    public int MaxClaims { get; set; } = 30_000;
    public long MaxBufferedBytes { get; set; } = 48L * 1024 * 1024;
    public int MaxIpEntries { get; set; } = 50_000;

    public TimeSpan IdleStream { get; set; } = TimeSpan.FromSeconds(120);
    public TimeSpan AgentPingAfter { get; set; } = TimeSpan.FromSeconds(30);
    public TimeSpan AgentDeadAfter { get; set; } = TimeSpan.FromSeconds(90);
    public TimeSpan RegisterWithin { get; set; } = TimeSpan.FromSeconds(5);
    public TimeSpan RegisterMinInterval { get; set; } = TimeSpan.FromSeconds(1);
    public TimeSpan CloseEchoWait { get; set; } = TimeSpan.FromSeconds(5);
    public TimeSpan Sweep { get; set; } = TimeSpan.FromSeconds(1);
    public TimeSpan StatsEvery { get; set; } = TimeSpan.FromMinutes(10);
}
