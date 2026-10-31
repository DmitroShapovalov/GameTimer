using System.Net;
using System.Net.Sockets;
using Microsoft.AspNetCore.Http;

namespace GameTimer.Relay;

/// <summary>
/// Per-IP limits (§10.4): new sockets per second, open hub / agent sockets, 404 answers per minute.
/// "Per IP" is per IPv4 address or per IPv6 /64. Nothing here is logged or kept longer than the windows.
/// </summary>
internal sealed class IpLimits
{
    private sealed class Entry
    {
        public readonly Queue<long> New = new();
        public readonly Queue<long> NotFound = new();
        public int Hubs, Agents;
    }

    private readonly RelayOptions _o;
    private readonly TimeProvider _time;
    private readonly Dictionary<string, Entry> _map = new(StringComparer.Ordinal);
    private readonly object _lock = new();

    public IpLimits(RelayOptions options, TimeProvider time)
    {
        _o = options;
        _time = time;
    }

    /// <summary>The limit key of a request: the client address from the trusted header, else the TCP peer.</summary>
    public static string KeyOf(HttpContext ctx, RelayOptions o)
    {
        IPAddress? ip = null;
        if (o.ClientIpHeader is { Length: > 0 } h && ctx.Request.Headers.TryGetValue(h, out var v)
            && IPAddress.TryParse(v.ToString().Trim(), out var parsed))
            ip = parsed;
        ip ??= ctx.Connection.RemoteIpAddress;
        return KeyOf(ip);
    }

    public static string KeyOf(IPAddress? ip)
    {
        if (ip == null) return "none";
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
            return "6:" + Convert.ToHexString(ip.GetAddressBytes(), 0, 8);
        return "4:" + ip;
    }

    /// <summary>
    /// A new socket from <paramref name="key"/>: within the per-second rate and the open-socket limit?
    /// Counts the attempt towards the rate when allowed.
    /// </summary>
    public bool TryStart(string key, bool agent)
    {
        lock (_lock)
        {
            var e = Get(key);
            var now = _time.GetTimestamp();
            Trim(e.New, now, TimeSpan.FromSeconds(1));
            if (e.New.Count >= _o.NewSocketsPerIpPerSecond) return false;
            if (agent ? e.Agents >= _o.AgentSocketsPerIp : e.Hubs >= _o.HubSocketsPerIp) return false;
            e.New.Enqueue(now);
            return true;
        }
    }

    public bool NotFoundBlocked(string key)
    {
        lock (_lock)
        {
            var e = Get(key);
            Trim(e.NotFound, _time.GetTimestamp(), TimeSpan.FromMinutes(1));
            return e.NotFound.Count >= _o.NotFoundPerIpPerMinute;
        }
    }

    public void CountNotFound(string key)
    {
        lock (_lock) Get(key).NotFound.Enqueue(_time.GetTimestamp());
    }

    public void Opened(string key, bool agent)
    {
        lock (_lock)
        {
            var e = Get(key);
            if (agent) e.Agents++; else e.Hubs++;
        }
    }

    public void Closed(string key, bool agent)
    {
        lock (_lock)
        {
            var e = Get(key);
            if (agent) e.Agents--; else e.Hubs--;
        }
    }

    /// <summary>Drops entries with nothing open and nothing in their windows.</summary>
    public void Cleanup()
    {
        lock (_lock)
        {
            var now = _time.GetTimestamp();
            foreach (var (key, e) in _map.ToList())
            {
                Trim(e.New, now, TimeSpan.FromSeconds(1));
                Trim(e.NotFound, now, TimeSpan.FromMinutes(1));
                if (e.Hubs <= 0 && e.Agents <= 0 && e.New.Count == 0 && e.NotFound.Count == 0) _map.Remove(key);
            }
        }
    }

    internal int Count { get { lock (_lock) return _map.Count; } }

    private Entry Get(string key)
    {
        if (!_map.TryGetValue(key, out var e)) _map[key] = e = new Entry();
        return e;
    }

    private void Trim(Queue<long> q, long now, TimeSpan window)
    {
        while (q.Count > 0 && _time.GetElapsedTime(q.Peek(), now) >= window) q.Dequeue();
    }
}
