using System.Buffers.Binary;
using System.Net.WebSockets;
using System.Threading.Channels;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;

namespace GameTimer.Relay;

/// <summary>
/// One agent socket (§10.2): reads its frames, owns its streams, and sends frames to it in order.
/// </summary>
internal sealed class AgentSession
{
    private readonly RelayState _s;
    private readonly WebSocket _ws;
    private readonly Channel<Out> _out = Channel.CreateUnbounded<Out>(new UnboundedChannelOptions { SingleReader = true });
    private readonly object _gate = new();
    private readonly Dictionary<uint, HubStream> _streams = new();
    private uint _nextId;
    private bool _closing;
    private bool _registered;
    private readonly long _connected;
    private long _lastReceived, _lastSent, _lastRegister, _closingSince;

    /// <summary>The routes this socket holds; guarded by the <see cref="Registry"/> lock.</summary>
    public HashSet<string> Routes { get; } = new(StringComparer.Ordinal);

    public string IpKey { get; }

    public AgentSession(RelayState state, WebSocket ws, string ipKey)
    {
        _s = state;
        _ws = ws;
        IpKey = ipKey;
        _connected = _lastReceived = _lastSent = state.Time.GetTimestamp();
    }

    public async Task RunAsync()
    {
        _s.Agents[this] = 0;
        var send = WsIo.SendLoopAsync(_ws, _out.Reader, Sent);
        try
        {
            await ReceiveLoopAsync().ConfigureAwait(false);
        }
        catch (Exception) { /* aborted or broken */ }
        finally
        {
            _s.Agents.TryRemove(this, out _);
            _s.Registry.Remove(this);
            List<HubStream> streams;
            lock (_gate)
            {
                _closing = true;
                streams = _streams.Values.ToList();
                _streams.Clear();
            }
            foreach (var st in streams) st.Terminate(hubCode: 1001, agentCode: null);
            _out.Writer.TryComplete();
            await Task.WhenAny(send, Task.Delay(_s.Options.CloseEchoWait)).ConfigureAwait(false);
            _ws.Abort();
            await send.ConfigureAwait(false);
        }
    }

    private async Task ReceiveLoopAsync()
    {
        while (true)
        {
            var (kind, data) = await WsIo.ReceiveAsync(_ws, Frames.MaxFrame).ConfigureAwait(false);
            Volatile.Write(ref _lastReceived, _s.Time.GetTimestamp());
            switch (kind)
            {
                case Received.Close:
                    Close((int)(_ws.CloseStatus ?? WebSocketCloseStatus.NormalClosure)); // echo
                    return;
                case Received.Text:
                    Close(1003);
                    break;
                case Received.TooBig:
                    Close(1009);
                    break;
                default:
                    if (!IsClosing) Handle(data!);
                    break;
            }
        }
    }

    private bool IsClosing { get { lock (_gate) return _closing; } }

    private void Handle(byte[] f)
    {
        if (f.Length == 0) { Close(1002); return; }
        switch ((FrameType)f[0])
        {
            case FrameType.Register:
                if ((f.Length - 1) % Frames.ClaimSize != 0 || (f.Length - 1) / Frames.ClaimSize > Frames.MaxClaims) Close(1002);
                else Register(f);
                break;
            case FrameType.Data:
                if (f.Length < Frames.StreamHeader + 1) { Close(1002); break; }
                Find(Frames.StreamOf(f))?.FromAgent(f.AsMemory(Frames.StreamHeader));
                break;
            case FrameType.Close:
                if (f.Length != Frames.StreamHeader + 2) { Close(1002); break; }
                Find(Frames.StreamOf(f))?.Terminate(
                    hubCode: Frames.MapCode(BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(Frames.StreamHeader))), agentCode: null);
                break;
            case FrameType.Ping:
                if (f.Length > 1 + Frames.MaxPingData) { Close(1002); break; }
                Enqueue(Frames.Pong(f.AsSpan(1)), owner: null);
                break;
            case FrameType.Pong:
                if (f.Length > 1 + Frames.MaxPingData) Close(1002);
                break;
            default:
                Close(1002); // open, unknown or reserved types never come from an agent
                break;
        }
    }

    private void Register(byte[] f)
    {
        var now = _s.Time.GetTimestamp();
        if (_registered && _s.Time.GetElapsedTime(_lastRegister, now) < _s.Options.RegisterMinInterval)
        {
            Close(1008);
            return;
        }
        _registered = true;
        _lastRegister = now;
        var routes = new Dictionary<string, byte[]>(StringComparer.Ordinal);
        for (int off = 1; off < f.Length; off += Frames.ClaimSize)
        {
            var route = RouteToken.Route(f.AsSpan(off, Frames.ClaimSize));
            routes[Base32.Encode(route)] = route;
        }
        if (!_s.Registry.Register(this, routes, _s.Options.MaxClaims)) Close(1013);
    }

    private HubStream? Find(uint id)
    {
        lock (_gate) return _streams.TryGetValue(id, out var st) ? st : null;
    }

    /// <summary>Whether a new stream would fit (checked before the hub's upgrade is accepted, §10.3: 503).</summary>
    public bool HasRoom
    {
        get { lock (_gate) return !_closing && _streams.Count < _s.Options.StreamsPerAgent; }
    }

    /// <summary>Adds the stream and tells the agent (<c>open</c>). False if the socket is closing or full.</summary>
    public bool Open(HubStream st, byte[] route)
    {
        lock (_gate)
        {
            if (_closing || _streams.Count >= _s.Options.StreamsPerAgent || _nextId == uint.MaxValue) return false;
            st.Id = ++_nextId;
            _streams[st.Id] = st;
        }
        return Enqueue(Frames.Open(st.Id, route), owner: null);
    }

    public void RemoveStream(uint id)
    {
        lock (_gate) _streams.Remove(id);
    }

    /// <summary>A hub's Noise message towards the agent; false when the stream's or the relay's buffer is full.</summary>
    public bool SendFromHub(HubStream st, ReadOnlySpan<byte> noise)
    {
        var frame = Frames.Data(st.Id, noise);
        long queued = Interlocked.Add(ref st.ToAgentBuffered, frame.Length);
        if (queued > _s.Options.BufferPerStream && queued != frame.Length) // one message always fits
        {
            Interlocked.Add(ref st.ToAgentBuffered, -frame.Length);
            return false;
        }
        if (!_s.TryBuffer(frame.Length))
        {
            Interlocked.Add(ref st.ToAgentBuffered, -frame.Length);
            return false;
        }
        if (_out.Writer.TryWrite(new Out(frame, 0, st))) return true;
        Release(new Out(frame, 0, st));
        return true; // the agent socket is going away; its close reaches the hub
    }

    /// <summary>Tells the agent that a stream ended (§10.2 <c>close</c>).</summary>
    public void SendClose(uint id, int code) => Enqueue(Frames.Close(id, code), owner: null);

    private bool Enqueue(byte[] frame, HubStream? owner) => _out.Writer.TryWrite(new Out(frame, 0, owner));

    /// <summary>Starts closing this socket with <paramref name="code"/>; the receive loop waits for the echo.</summary>
    public void Close(int code)
    {
        lock (_gate)
        {
            if (_closing) return;
            _closing = true;
            _closingSince = _s.Time.GetTimestamp();
        }
        _out.Writer.TryWrite(new Out(null, code, null));
        _out.Writer.TryComplete();
    }

    private void Sent(Out o)
    {
        Volatile.Write(ref _lastSent, _s.Time.GetTimestamp());
        Release(o);
    }

    private void Release(Out o)
    {
        if (o.Owner != null && o.Data != null)
        {
            Interlocked.Add(ref o.Owner.ToAgentBuffered, -o.Data.Length);
            _s.Unbuffer(o.Data.Length);
        }
    }

    /// <summary>Periodic checks (§10.2): register deadline, keepalive, a close that is never echoed, idle streams.</summary>
    public void Tick()
    {
        var now = _s.Time.GetTimestamp();
        bool closing;
        long closingSince;
        List<HubStream> streams;
        lock (_gate)
        {
            closing = _closing;
            closingSince = _closingSince;
            streams = _streams.Values.ToList();
        }
        if (closing)
        {
            if (_s.Time.GetElapsedTime(closingSince, now) > _s.Options.CloseEchoWait) _ws.Abort();
            return;
        }
        if (!_registered && _s.Time.GetElapsedTime(_connected, now) > _s.Options.RegisterWithin)
        {
            Close(1008);
            return;
        }
        if (_s.Time.GetElapsedTime(Volatile.Read(ref _lastReceived), now) > _s.Options.AgentDeadAfter)
        {
            _ws.Abort();
            return;
        }
        if (_s.Time.GetElapsedTime(Volatile.Read(ref _lastSent), now) > _s.Options.AgentPingAfter)
        {
            Volatile.Write(ref _lastSent, now);
            Enqueue(Frames.Ping(ReadOnlySpan<byte>.Empty), owner: null);
        }
        foreach (var st in streams) st.Tick(now);
    }
}
