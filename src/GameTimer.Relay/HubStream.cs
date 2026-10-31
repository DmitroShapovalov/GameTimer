using System.Net.WebSockets;
using System.Threading.Channels;

namespace GameTimer.Relay;

/// <summary>
/// One hub socket joined to one stream of an agent socket (§10.3): one binary message = one Noise message.
/// </summary>
internal sealed class HubStream
{
    private readonly RelayState _s;
    private readonly AgentSession _agent;
    private readonly WebSocket _ws;
    private readonly Channel<Out> _out = Channel.CreateUnbounded<Out>(new UnboundedChannelOptions { SingleReader = true });
    private int _terminated;
    private long _lastData, _toHubBuffered;

    /// <summary>Bytes queued towards the agent for this stream (updated by <see cref="AgentSession"/>).</summary>
    public long ToAgentBuffered;

    public uint Id { get; set; }

    public HubStream(RelayState state, AgentSession agent, WebSocket ws)
    {
        _s = state;
        _agent = agent;
        _ws = ws;
        _lastData = state.Time.GetTimestamp();
    }

    public bool Terminated => Volatile.Read(ref _terminated) != 0;

    public async Task RunAsync()
    {
        var send = WsIo.SendLoopAsync(_ws, _out.Reader, Sent);
        try
        {
            while (true)
            {
                var (kind, data) = await WsIo.ReceiveAsync(_ws, Frames.MaxNoise, _s).ConfigureAwait(false);
                if (kind == Received.Close)
                {
                    int code = (int)(_ws.CloseStatus ?? WebSocketCloseStatus.NormalClosure);
                    Terminate(hubCode: code, agentCode: Frames.MapCode(code)); // echo + tell the agent
                    break;
                }
                if (Terminated) continue; // waiting for the hub's echo of our close
                if (kind == Received.Text) { Terminate(1003, Frames.MapCode(1003)); continue; }
                if (kind == Received.TooBig) { Terminate(1009, Frames.MapCode(1009)); continue; }
                if (kind == Received.Overloaded) { Terminate(1013, 1013); continue; }
                if (data!.Length == 0) continue; // never a Noise message; nothing to forward
                Volatile.Write(ref _lastData, _s.Time.GetTimestamp());
                if (!_agent.SendFromHub(this, data)) Terminate(1013, 1013);
            }
        }
        catch (Exception e) { _s.Unexpected(e); }
        finally
        {
            Terminate(hubCode: 1001, agentCode: 1001); // a hub that just drops (no-op if already ended)
            await Task.WhenAny(send, Task.Delay(_s.Options.CloseEchoWait)).ConfigureAwait(false);
            _ws.Abort();
            await send.ConfigureAwait(false);
            WsIo.Drain(_out, Sent);
        }
    }

    /// <summary>A Noise message from the agent towards the hub.</summary>
    public void FromAgent(ReadOnlyMemory<byte> noise)
    {
        if (Terminated) return;
        Volatile.Write(ref _lastData, _s.Time.GetTimestamp());
        int n = noise.Length;
        long queued = Interlocked.Add(ref _toHubBuffered, n);
        if ((queued > _s.Options.BufferPerStream && queued != n) || !_s.TryBuffer(n)) // one message always fits
        {
            Interlocked.Add(ref _toHubBuffered, -n);
            Terminate(1013, 1013);
            return;
        }
        var o = new Out(noise.ToArray(), 0, this);
        if (!_out.Writer.TryWrite(o)) Sent(o);
    }

    /// <summary>
    /// Ends the stream once: closes the hub socket with <paramref name="hubCode"/> and, unless the agent ended
    /// it, sends the agent <c>close</c> with <paramref name="agentCode"/>.
    /// </summary>
    public void Terminate(int hubCode, int? agentCode)
    {
        if (Interlocked.Exchange(ref _terminated, 1) != 0) return;
        if (Id != 0)
        {
            _agent.RemoveStream(Id);
            if (agentCode is int c) _agent.SendClose(Id, c);
        }
        _out.Writer.TryWrite(new Out(null, hubCode, null));
        _out.Writer.TryComplete();
        _ = AbortIfNoEchoAsync();
    }

    /// <summary>A hub that doesn't answer our close in time is cut off, so its receive loop ends.</summary>
    private async Task AbortIfNoEchoAsync()
    {
        await Task.Delay(_s.Options.CloseEchoWait).ConfigureAwait(false);
        if (_ws.State != WebSocketState.Closed) _ws.Abort();
    }

    private void Sent(Out o)
    {
        if (o.Data == null) return;
        Interlocked.Add(ref _toHubBuffered, -o.Data.Length);
        _s.Unbuffer(o.Data.Length);
    }

    public void Tick(long now)
    {
        if (Terminated) return;
        if (_s.Time.GetElapsedTime(Volatile.Read(ref _lastData), now) > _s.Options.IdleStream) Terminate(1001, 1001);
    }
}
