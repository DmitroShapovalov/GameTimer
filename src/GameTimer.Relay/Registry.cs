namespace GameTimer.Relay;

/// <summary>
/// Route → agent socket (§10.1, §10.2). An agent registers claims; the relay indexes the routes it computes
/// from them. A route another socket holds moves to the newest registration.
/// </summary>
internal sealed class Registry
{
    private readonly object _lock = new();
    private readonly Dictionary<string, (AgentSession Agent, byte[] Route)> _map = new(StringComparer.Ordinal);

    public int Count { get { lock (_lock) return _map.Count; } }

    /// <summary>
    /// Replaces <paramref name="agent"/>'s whole set with <paramref name="routes"/> (base32 → bytes).
    /// False if the total would go over <paramref name="maxTotal"/>; the agent then holds nothing.
    /// Null if the agent socket is already closing (it must not take routes back from a newer socket).
    /// </summary>
    public bool? Register(AgentSession agent, IReadOnlyDictionary<string, byte[]> routes, int maxTotal)
    {
        lock (_lock)
        {
            RemoveLocked(agent);
            if (agent.IsClosing) return null;
            int added = routes.Keys.Count(r => !_map.ContainsKey(r));
            if (_map.Count + added > maxTotal) return false;
            foreach (var (route, bytes) in routes)
            {
                if (_map.TryGetValue(route, out var held) && held.Agent != agent) held.Agent.Routes.Remove(route);
                _map[route] = (agent, bytes);
                agent.Routes.Add(route);
            }
            return true;
        }
    }

    public (AgentSession Agent, byte[] Route)? Find(string route)
    {
        lock (_lock) return _map.TryGetValue(route, out var e) ? e : null;
    }

    public void Remove(AgentSession agent)
    {
        lock (_lock) RemoveLocked(agent);
    }

    private void RemoveLocked(AgentSession agent)
    {
        foreach (var r in agent.Routes)
            if (_map.TryGetValue(r, out var e) && e.Agent == agent) _map.Remove(r);
        agent.Routes.Clear();
    }
}
