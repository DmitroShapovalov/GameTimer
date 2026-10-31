using System.Collections.Concurrent;
using Microsoft.Extensions.Options;

namespace GameTimer.Relay;

/// <summary>Everything the relay holds, in memory only.</summary>
internal sealed class RelayState
{
    public RelayOptions Options { get; }
    public TimeProvider Time { get; }
    public IpLimits Limits { get; }
    public ILogger Log { get; }
    public Registry Registry { get; } = new();
    public ConcurrentDictionary<AgentSession, byte> Agents { get; } = new();

    private int _agentSockets, _hubSockets;
    private long _buffered;

    public IPushSender Push { get; }

    public RelayState(IOptions<RelayOptions> options, TimeProvider time, ILogger<RelayState> log, IPushSender push)
    {
        Push = push;
        Options = options.Value;
        Time = time;
        Log = log;
        Limits = new IpLimits(Options, time);
    }

    /// <summary>Set when the relay is stopping (a deploy): new upgrades get 503 so agents don't come back to it.</summary>
    public volatile bool Stopping;

    public int AgentSockets => Volatile.Read(ref _agentSockets);
    public int HubSockets => Volatile.Read(ref _hubSockets);
    public long Buffered => Interlocked.Read(ref _buffered);

    /// <summary>Takes one socket from its pool (agents and hubs are counted apart), or false when it is full.</summary>
    public bool TryTakeSocket(bool agent)
    {
        ref int n = ref agent ? ref _agentSockets : ref _hubSockets;
        int max = agent ? Options.MaxAgentSockets : Options.MaxHubSockets;
        while (true)
        {
            int cur = Volatile.Read(ref n);
            if (cur >= max) return false;
            if (Interlocked.CompareExchange(ref n, cur + 1, cur) == cur) return true;
        }
    }

    public void ReleaseSocket(bool agent)
    {
        if (agent) Interlocked.Decrement(ref _agentSockets); else Interlocked.Decrement(ref _hubSockets);
    }

    /// <summary>Close to the total byte budget: new hub upgrades are refused with 503 (§10.4).</summary>
    public bool NearlyFull => Buffered > Options.MaxBufferedBytes * 9 / 10;

    /// <summary>Adds queued or partly received bytes to the total; false (nothing added) over the cap.</summary>
    public bool TryBuffer(int bytes)
    {
        if (Interlocked.Add(ref _buffered, bytes) <= Options.MaxBufferedBytes) return true;
        Interlocked.Add(ref _buffered, -bytes);
        return false;
    }

    public void Unbuffer(int bytes) => Interlocked.Add(ref _buffered, -bytes);

    // §10.6: one wake-up per token per WakeSameTokenEvery across the whole relay (whoever asks); keyed by
    // a hash, so the table holds no token
    private readonly Dictionary<string, long> _tokenWakes = new(StringComparer.Ordinal);
    private const int MaxTokenWakes = 20_000;

    private static string TokenKey(string token) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.ASCII.GetBytes(token)), 0, 16);

    public void ForgetWakeToken(string token)
    {
        lock (_tokenWakes) _tokenWakes.Remove(TokenKey(token));
    }

    private readonly Queue<long> _wakeTotal = new();

    /// <summary>§10.6: all wake-ups through the relay per minute.</summary>
    public bool TryWakeTotal()
    {
        var now = Time.GetTimestamp();
        lock (_wakeTotal)
        {
            while (_wakeTotal.Count > 0 && Time.GetElapsedTime(_wakeTotal.Peek(), now) >= TimeSpan.FromMinutes(1)) _wakeTotal.Dequeue();
            if (_wakeTotal.Count >= Options.WakesPerMinute) return false;
            _wakeTotal.Enqueue(now);
            return true;
        }
    }

    public bool TryWakeToken(string token)
    {
        var key = TokenKey(token);
        var now = Time.GetTimestamp();
        lock (_tokenWakes)
        {
            if (_tokenWakes.TryGetValue(key, out var last) && Time.GetElapsedTime(last, now) < Options.WakeSameTokenEvery) return false;
            if (_tokenWakes.Count >= MaxTokenWakes)
            {
                foreach (var old in _tokenWakes.Where(kv => Time.GetElapsedTime(kv.Value, now) >= Options.WakeSameTokenEvery).Select(kv => kv.Key).ToList())
                    _tokenWakes.Remove(old);
                if (_tokenWakes.Count >= MaxTokenWakes) return false;
            }
            _tokenWakes[key] = now;
            return true;
        }
    }

    /// <summary>Unexpected failures are logged by type only: never a path, an address or a route.</summary>
    public void Unexpected(Exception e)
    {
        if (e is System.Net.WebSockets.WebSocketException or OperationCanceledException or IOException
            or ObjectDisposedException or Microsoft.AspNetCore.Connections.ConnectionResetException) return;
        Log.LogWarning("Unexpected {Type} in a relay socket", e.GetType().FullName);
    }
}
