using System.Collections.Concurrent;
using Microsoft.Extensions.Options;

namespace GameTimer.Relay;

/// <summary>Everything the relay holds, in memory only.</summary>
internal sealed class RelayState
{
    public RelayOptions Options { get; }
    public TimeProvider Time { get; }
    public IpLimits Limits { get; }
    public Registry Registry { get; } = new();
    public ConcurrentDictionary<AgentSession, byte> Agents { get; } = new();

    private int _sockets;
    private long _buffered;

    public RelayState(IOptions<RelayOptions> options, TimeProvider time)
    {
        Options = options.Value;
        Time = time;
        Limits = new IpLimits(Options, time);
    }

    public int Sockets => Volatile.Read(ref _sockets);
    public long Buffered => Interlocked.Read(ref _buffered);

    /// <summary>Takes one socket from the total, or false when the relay is full.</summary>
    public bool TryTakeSocket()
    {
        while (true)
        {
            int n = Volatile.Read(ref _sockets);
            if (n >= Options.MaxSockets) return false;
            if (Interlocked.CompareExchange(ref _sockets, n + 1, n) == n) return true;
        }
    }

    public void ReleaseSocket() => Interlocked.Decrement(ref _sockets);

    /// <summary>Adds queued bytes to the total; false (nothing added) when that would go over the cap.</summary>
    public bool TryBuffer(int bytes)
    {
        if (Interlocked.Add(ref _buffered, bytes) <= Options.MaxBufferedBytes) return true;
        Interlocked.Add(ref _buffered, -bytes);
        return false;
    }

    public void Unbuffer(int bytes) => Interlocked.Add(ref _buffered, -bytes);
}
