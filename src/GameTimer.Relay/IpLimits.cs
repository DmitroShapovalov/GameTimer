using System.Net;
using System.Net.Sockets;
using Microsoft.AspNetCore.Http;

namespace GameTimer.Relay;

/// <summary>
/// Per-IP limits (§10.4): new sockets per second, open hub / agent sockets, 404 answers per minute.
/// "Per IP" is per IPv4 address or per IPv6 /64; an IPv6 /48 also has a wider budget (4× each limit), so
/// one host with many /64s can't take the whole relay. Fixed windows, a bounded table, nothing logged.
/// </summary>
internal sealed class IpLimits
{
    /// <summary>The limit keys of one client: its own, and for IPv6 the /48 around it.</summary>
    public readonly record struct Client(string Key, string? Wide);

    private sealed class Entry
    {
        public long NewWindow, NotFoundWindow;
        public int NewCount, NotFoundCount, Hubs, Agents;
    }

    public const int WideFactor = 4;
    private readonly RelayOptions _o;
    private readonly TimeProvider _time;
    private readonly Dictionary<string, Entry> _map = new(StringComparer.Ordinal);
    private readonly object _lock = new();

    public IpLimits(RelayOptions options, TimeProvider time)
    {
        _o = options;
        _time = time;
    }

    /// <summary>
    /// The client of a request: the address from the trusted proxy header, else the TCP peer. Null when the
    /// header is configured but missing or unreadable (then the request is refused rather than every such
    /// client sharing the proxy's address).
    /// </summary>
    public static Client? ClientOf(HttpContext ctx, RelayOptions o)
    {
        IPAddress? ip;
        if (o.ClientIpHeader is { Length: > 0 } h)
        {
            if (!ctx.Request.Headers.TryGetValue(h, out var v) || !IPAddress.TryParse(v.ToString().Trim(), out ip))
                return null;
        }
        else ip = ctx.Connection.RemoteIpAddress;
        return ClientOf(ip);
    }

    public static Client ClientOf(IPAddress? ip)
    {
        if (ip == null) return new Client("none", null);
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
        {
            var b = ip.GetAddressBytes();
            return new Client("6:" + Convert.ToHexString(b, 0, 8), "6w:" + Convert.ToHexString(b, 0, 6));
        }
        return new Client("4:" + ip, null);
    }

    /// <summary>
    /// A new socket from <paramref name="c"/>: within the per-second rate and the open-socket limit, and the
    /// table not full? On success the attempt is counted and a socket slot reserved: call <see cref="Closed"/>
    /// when the socket ends or the upgrade is refused later.
    /// </summary>
    public bool TryStart(Client c, bool agent)
    {
        lock (_lock)
        {
            var now = _time.GetTimestamp();
            var e = Get(c.Key);
            var w = c.Wide == null ? null : Get(c.Wide);
            if (e == null || (c.Wide != null && w == null)) return false; // table full
            if (!Allows(e, now, agent, 1) || (w != null && !Allows(w, now, agent, WideFactor))) return false;
            Take(e, agent);
            if (w != null) Take(w, agent);
            return true;
        }
    }

    public void Closed(Client c, bool agent)
    {
        lock (_lock)
        {
            Release(c.Key, agent);
            if (c.Wide != null) Release(c.Wide, agent);
        }
    }

    public bool NotFoundBlocked(Client c)
    {
        lock (_lock)
        {
            var now = _time.GetTimestamp();
            return Blocked(c.Key, now, 1) || (c.Wide != null && Blocked(c.Wide, now, WideFactor));
        }
    }

    public void CountNotFound(Client c)
    {
        lock (_lock)
        {
            var now = _time.GetTimestamp();
            Count(c.Key, now);
            if (c.Wide != null) Count(c.Wide, now);
        }
    }

    /// <summary>Drops entries with nothing open and expired windows.</summary>
    public void Cleanup()
    {
        lock (_lock)
        {
            var now = _time.GetTimestamp();
            foreach (var (key, e) in _map.ToList())
                if (e.Hubs <= 0 && e.Agents <= 0 && Expired(e.NewWindow, now, TimeSpan.FromSeconds(1))
                    && Expired(e.NotFoundWindow, now, TimeSpan.FromMinutes(1)))
                    _map.Remove(key);
        }
    }

    internal int Count() { lock (_lock) return _map.Count; }

    private bool Allows(Entry e, long now, bool agent, int factor)
    {
        if (Expired(e.NewWindow, now, TimeSpan.FromSeconds(1))) { e.NewWindow = now; e.NewCount = 0; }
        if (e.NewCount >= _o.NewSocketsPerIpPerSecond * factor) return false;
        return agent ? e.Agents < _o.AgentSocketsPerIp * factor : e.Hubs < _o.HubSocketsPerIp * factor;
    }

    private static void Take(Entry e, bool agent)
    {
        e.NewCount++;
        if (agent) e.Agents++; else e.Hubs++;
    }

    private void Release(string key, bool agent)
    {
        if (!_map.TryGetValue(key, out var e)) return;
        if (agent) e.Agents--; else e.Hubs--;
    }

    private bool Blocked(string key, long now, int factor)
    {
        if (!_map.TryGetValue(key, out var e) || Expired(e.NotFoundWindow, now, TimeSpan.FromMinutes(1))) return false;
        return e.NotFoundCount >= _o.NotFoundPerIpPerMinute * factor;
    }

    private void Count(string key, long now)
    {
        var e = Get(key);
        if (e == null) return;
        if (Expired(e.NotFoundWindow, now, TimeSpan.FromMinutes(1))) { e.NotFoundWindow = now; e.NotFoundCount = 0; }
        e.NotFoundCount++;
    }

    /// <summary>An entry, created if the table has room (else null: the request is refused).</summary>
    private Entry? Get(string key)
    {
        if (_map.TryGetValue(key, out var e)) return e;
        if (_map.Count >= _o.MaxIpEntries) return null;
        _map[key] = e = new Entry { NewWindow = long.MinValue / 2, NotFoundWindow = long.MinValue / 2 };
        return e;
    }

    private bool Expired(long windowStart, long now, TimeSpan window) =>
        windowStart == long.MinValue / 2 || _time.GetElapsedTime(windowStart, now) >= window;
}
