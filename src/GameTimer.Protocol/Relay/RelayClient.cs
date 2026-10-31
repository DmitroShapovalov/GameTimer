using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Threading.Channels;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Encodings;

namespace GameTimer.Protocol.Relay;

public enum RelayStatus { Off, Connecting, Connected }

/// <summary>
/// The agent's side of the relay (docs/protocol.md §10.2): keeps one outbound WebSocket to
/// <c>wss://&lt;relay&gt;/v2/agent</c>, registers the route claims of the paired hubs, and runs every stream
/// the relay opens through <see cref="AgentHost.RunAsync"/> like a LAN socket (with the relay pool and the
/// route ↔ key binding of §10.4). Reconnects with back-off; the app starts it only while "Control from
/// anywhere" is on and a hub is paired.
/// </summary>
public sealed class RelayClient : IAsyncDisposable
{
    public static readonly Uri DefaultUrl = new("wss://gametimer-relay.fly.dev/v2/agent");

    internal static readonly TimeSpan PingAfter = TimeSpan.FromSeconds(30);
    internal static readonly TimeSpan DeadAfter = TimeSpan.FromSeconds(90);
    internal static readonly TimeSpan RecentSets = TimeSpan.FromSeconds(30);
    internal static readonly TimeSpan RegisterMinInterval = TimeSpan.FromSeconds(1.1);
    internal static readonly TimeSpan RecheckEvery = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan MinBackoff = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan MaxBackoff = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan StableAfter = TimeSpan.FromSeconds(60);
    /// <summary>§10.6: one wake-up per phone in this time; events in between are sent as one trailing wake-up.</summary>
    internal static readonly TimeSpan WakeEvery = TimeSpan.FromSeconds(30);
    /// <summary>A send the relay doesn't take in this time means a stuck socket: it is aborted and reconnected.</summary>
    internal static readonly TimeSpan SendTimeout = TimeSpan.FromSeconds(10);
    /// <summary>Streams on one relay socket, counted here whatever the relay sends (§10.4): 4 waiting + 16 sessions.</summary>
    public const int MaxStreams = AgentHost.MaxRelayPending + AgentHost.MaxSessions;

    private readonly AgentHost _host;
    private readonly Uri _url;
    private readonly TimeProvider _time;
    private readonly Func<Uri, CancellationToken, Task<WebSocket>> _connect;
    private readonly object _lock = new();
    private CancellationTokenSource? _run;
    private Task? _loop;
    private Connection? _current;
    private RelayStatus _status;

    public RelayClient(AgentHost host, Uri? url = null, TimeProvider? time = null,
        Func<Uri, CancellationToken, Task<WebSocket>>? connect = null)
    {
        _host = host;
        _url = url ?? DefaultUrl;
        _time = time ?? TimeProvider.System;
        _connect = connect ?? DefaultConnectAsync;
    }

    public RelayStatus Status { get { lock (_lock) return _status; } }

    /// <summary>
    /// False while the app is stopping: new streams are refused (1008) so no session starts after the
    /// <c>bye shutdown</c> went out; existing ones keep the connection until the client stops.
    /// </summary>
    public bool AcceptStreams { get => Volatile.Read(ref _acceptStreams); set => Volatile.Write(ref _acceptStreams, value); }
    private bool _acceptStreams = true;

    /// <summary>The status changed (any thread).</summary>
    public event Action<RelayStatus>? StatusChanged;

    public void Start()
    {
        lock (_lock)
        {
            if (_run != null) return;
            _run = new CancellationTokenSource();
            var token = _run.Token;
            _host.PeersChanged += OnPeersChanged;
            _host.WakeNeeded += OnWakeNeeded;
            _loop = Task.Run(() => RunAsync(token));
        }
    }

    public async Task StopAsync()
    {
        CancellationTokenSource? run;
        Task? loop;
        lock (_lock)
        {
            run = _run;
            loop = _loop;
            _run = null;
            _loop = null;
        }
        if (run == null) return;
        _host.PeersChanged -= OnPeersChanged;
        _host.WakeNeeded -= OnWakeNeeded;
        run.Cancel();
        if (loop != null) await loop.ConfigureAwait(false);
        run.Dispose();
        lock (_lock)
        {
            if (_run != null) return; // started again meanwhile: its loop reports its own status
        }
        SetStatus(RelayStatus.Off);
    }

    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);

    private void OnPeersChanged() => Volatile.Read(ref _current)?.MarkDirty();

    // Not connected: nothing to do (the phones catch up when they next connect)
    private void OnWakeNeeded(IReadOnlyList<string> tokens) => Volatile.Read(ref _current)?.Wake(tokens);

    private static async Task<WebSocket> DefaultConnectAsync(Uri url, CancellationToken cancel)
    {
        var ws = new ClientWebSocket(); // no Origin header: the relay refuses browsers (§10.3)
        ws.Options.KeepAliveInterval = TimeSpan.Zero; // keepalive is the relay's own ping (§10.2)
        try
        {
            await ws.ConnectAsync(url, cancel).ConfigureAwait(false);
            return ws;
        }
        catch
        {
            ws.Dispose();
            throw;
        }
    }

    private async Task RunAsync(CancellationToken stop)
    {
        var backoff = MinBackoff;
        while (!stop.IsCancellationRequested)
        {
            SetStatus(RelayStatus.Connecting);
            var started = _time.GetTimestamp();
            try
            {
                using var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(stop);
                connectTimeout.CancelAfter(TimeSpan.FromSeconds(30));
                using var ws = await _connect(_url, connectTimeout.Token).ConfigureAwait(false);
                var conn = new Connection(this, ws);
                Volatile.Write(ref _current, conn);
                try { await conn.RunAsync(stop).ConfigureAwait(false); }
                finally { Volatile.Write(ref _current, null); }
            }
            catch (Exception) { /* refused, dropped or malformed: reconnect */ }
            if (stop.IsCancellationRequested) break;
            if (_time.GetElapsedTime(started) >= StableAfter) backoff = MinBackoff;
            SetStatus(RelayStatus.Connecting);
            try
            {
                var jitter = 0.5 + Random.Shared.NextDouble() / 2;
                await Task.Delay(backoff * jitter, _time, stop).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
            backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, MaxBackoff.Ticks));
        }
    }

    private void SetStatus(RelayStatus status)
    {
        lock (_lock)
        {
            if (_status == status) return;
            _status = status;
        }
        try { StatusChanged?.Invoke(status); } catch (Exception) { /* an app handler must not stop the relay */ }
    }

    /// <summary>One relay socket: register, frames in and out, the streams on it.</summary>
    private sealed class Connection
    {
        private readonly RelayClient _c;
        private readonly WebSocket _ws;
        private readonly SemaphoreSlim _sendLock = new(1, 1);
        private readonly ConcurrentDictionary<uint, Stream> _streams = new();
        private readonly CancellationTokenSource _dead = new();
        private Dictionary<string, string> _routes = new(StringComparer.Ordinal); // route → hub id
        private readonly List<(long At, Dictionary<string, string> Routes)> _recent = new();
        private readonly object _routesLock = new();
        private string _registeredKey = "";
        private long _lastSent, _lastReceived, _lastRegister, _lastCheck;
        private int _dirty = 1;
        /// <summary>Pongs and closes the receive loop answers with, sent by one task; full → the relay misbehaves.</summary>
        private readonly Channel<byte[]> _control = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(64) { SingleReader = true });
        private int _pongQueued;
        private readonly Dictionary<string, (long Last, bool Pending)> _wakes = new(StringComparer.Ordinal);

        /// <summary>
        /// §10.6: a wake-up per token at most every <see cref="WakeEvery"/>; one that comes too soon is sent
        /// when the time is up (one trailing wake-up for any number of events).
        /// </summary>
        public void Wake(IReadOnlyList<string> tokens)
        {
            var now = Time.GetTimestamp();
            var due = new List<string>();
            lock (_wakes)
            {
                foreach (var t in tokens)
                {
                    if (!_wakes.TryGetValue(t, out var w) || Time.GetElapsedTime(w.Last, now) >= WakeEvery)
                    {
                        _wakes[t] = (now, false);
                        due.Add(t);
                    }
                    else _wakes[t] = (w.Last, true);
                }
            }
            foreach (var t in due) SendSoon(RelayFrames.WakeFrame(t));
        }

        private void SendPendingWakes(long now)
        {
            var due = new List<string>();
            lock (_wakes)
            {
                foreach (var (t, w) in _wakes.ToList())
                {
                    var age = Time.GetElapsedTime(w.Last, now);
                    if (w.Pending && age >= WakeEvery) { _wakes[t] = (now, false); due.Add(t); }
                    else if (!w.Pending && age > TimeSpan.FromMinutes(10)) _wakes.Remove(t);
                }
            }
            // Outside the lock (the host takes its own): a hub that connected meanwhile has caught up already
            foreach (var t in due)
                if (_c._host.StillToWake(t)) SendSoon(RelayFrames.WakeFrame(t));
        }

        public Connection(RelayClient client, WebSocket ws)
        {
            _c = client;
            _ws = ws;
            _lastSent = _lastReceived = _lastCheck = client._time.GetTimestamp();
            _lastRegister = long.MinValue;
        }

        private TimeProvider Time => _c._time;

        public void MarkDirty() => Volatile.Write(ref _dirty, 1);

        public async Task RunAsync(CancellationToken stop)
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(stop, _dead.Token);
            // Turned off: close politely (the receive loop then sees the relay's echo), or abort after 2 s
            using var onStop = stop.Register(() => _ = CloseOnStopAsync());
            try
            {
                await RegisterIfChangedAsync(force: true).ConfigureAwait(false);
                _c.SetStatus(RelayStatus.Connected);
                var maintain = MaintainAsync(linked.Token);
                var control = ControlLoopAsync(linked.Token);
                try { await ReceiveLoopAsync().ConfigureAwait(false); }
                finally
                {
                    _dead.Cancel();
                    _control.Writer.TryComplete();
                    try { await maintain.ConfigureAwait(false); } catch (OperationCanceledException) { }
                    try { await control.ConfigureAwait(false); } catch (Exception) { }
                }
            }
            finally
            {
                foreach (var s in _streams.Values) s.RemoteClosed();
                _streams.Clear();
                _ws.Abort();
            }
        }

        private async Task CloseOnStopAsync()
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await _sendLock.WaitAsync(cts.Token).ConfigureAwait(false);
                try
                {
                    if (_ws.State == WebSocketState.Open)
                        await _ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, cts.Token).ConfigureAwait(false);
                }
                finally { _sendLock.Release(); }
                await Task.Delay(TimeSpan.FromSeconds(2), Time).ConfigureAwait(false);
            }
            catch (Exception) { }
            _ws.Abort(); // no echo in time (or the close failed): the receive loop ends now
        }

        private async Task ReceiveLoopAsync()
        {
            var buffer = new byte[RelayFrames.MaxFrame];
            while (true)
            {
                int len = 0;
                ValueWebSocketReceiveResult r;
                do
                {
                    if (len == buffer.Length) throw new InvalidDataException("frame too long"); // §10.2: reconnect
                    r = await _ws.ReceiveAsync(buffer.AsMemory(len), CancellationToken.None).ConfigureAwait(false);
                    if (r.MessageType == WebSocketMessageType.Close) return;
                    if (r.MessageType == WebSocketMessageType.Text) throw new InvalidDataException("text from the relay");
                    len += r.Count;
                } while (!r.EndOfMessage);
                Volatile.Write(ref _lastReceived, Time.GetTimestamp());
                Handle(buffer.AsSpan(0, len).ToArray());
            }
        }

        private void Handle(byte[] f)
        {
            if (f.Length == 0) throw new InvalidDataException("empty frame");
            switch (f[0])
            {
                case RelayFrames.Open:
                    if (f.Length != RelayFrames.StreamHeader + 16) throw new InvalidDataException("bad open");
                    Open(RelayFrames.StreamOf(f), Base32.Encode(f.AsSpan(RelayFrames.StreamHeader)));
                    break;
                case RelayFrames.Data:
                    if (f.Length < RelayFrames.StreamHeader + 1) throw new InvalidDataException("bad data");
                    if (_streams.TryGetValue(RelayFrames.StreamOf(f), out var s) && !s.Deliver(f[RelayFrames.StreamHeader..]))
                    {
                        // The session isn't reading: end the stream rather than buffer without limit
                        s.RemoteClosed();
                        SendSoon(RelayFrames.CloseFrame(s.Id, 1008));
                    }
                    break;
                case RelayFrames.Close:
                    if (f.Length != RelayFrames.StreamHeader + 2) throw new InvalidDataException("bad close");
                    if (_streams.TryRemove(RelayFrames.StreamOf(f), out var closed)) closed.RemoteClosed();
                    break;
                case RelayFrames.Ping:
                    if (f.Length > 1 + RelayFrames.MaxPingData) throw new InvalidDataException("bad ping");
                    SendSoon(RelayFrames.PongFrame(f.AsSpan(1)));
                    break;
                case RelayFrames.Pong:
                    if (f.Length > 1 + RelayFrames.MaxPingData) throw new InvalidDataException("bad pong");
                    break;
                case RelayFrames.Register:
                    throw new InvalidDataException("register from the relay");
                default:
                    break; // §10.2: the agent ignores types it doesn't know
            }
        }

        private void Open(uint id, string route)
        {
            // §10.2: ids are never reused while the socket lives; a relay that does is broken: reconnect
            if (id == 0 || _streams.ContainsKey(id)) throw new InvalidDataException("bad stream id");
            string? hubId = FindHub(route);
            HandshakeTicket? ticket = hubId == null || !_c.AcceptStreams || _streams.Count >= MaxStreams
                ? null
                : _c._host.TryAdmitRelay(hubId);
            if (ticket == null)
            {
                SendSoon(RelayFrames.CloseFrame(id, 1008));
                return;
            }
            var stream = new Stream(this, id);
            _streams[id] = stream;
            var dead = _dead.Token;
            _ = Task.Run(async () =>
            {
                try { await _c._host.RunAsync(stream, ticket, pairing: false, dead).ConfigureAwait(false); }
                finally { _streams.TryRemove(new KeyValuePair<uint, Stream>(id, stream)); }
            });
        }

        /// <summary>The hub of a route in the current set, or in a set replaced less than 30 s ago (§10.2).</summary>
        private string? FindHub(string route)
        {
            lock (_routesLock)
            {
                if (_routes.TryGetValue(route, out var hub)) return hub;
                var now = Time.GetTimestamp();
                _recent.RemoveAll(x => Time.GetElapsedTime(x.At, now) > RecentSets);
                foreach (var (_, set) in _recent)
                    if (set.TryGetValue(route, out hub)) return hub;
                return null;
            }
        }

        private async Task MaintainAsync(CancellationToken cancel)
        {
            try { await MaintainLoopAsync(cancel).ConfigureAwait(false); }
            catch (OperationCanceledException) { }
            catch (Exception)
            {
                _ws.Abort(); // without keepalive a silently dead socket would never end: reconnect instead
            }
        }

        private async Task MaintainLoopAsync(CancellationToken cancel)
        {
            using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1), Time);
            while (await timer.WaitForNextTickAsync(cancel).ConfigureAwait(false))
            {
                var now = Time.GetTimestamp();
                if (Time.GetElapsedTime(Volatile.Read(ref _lastReceived), now) > DeadAfter)
                {
                    _ws.Abort(); // the receive loop ends; reconnect
                    return;
                }
                bool due = Interlocked.Exchange(ref _dirty, 0) == 1
                           || Time.GetElapsedTime(_lastCheck, now) >= RecheckEvery;
                if (due)
                {
                    _lastCheck = now;
                    if (!await RegisterIfChangedAsync(force: false).ConfigureAwait(false)) MarkDirty(); // too soon: next second
                }
                SendPendingWakes(now);
                if (Time.GetElapsedTime(Volatile.Read(ref _lastSent), now) > PingAfter)
                    await SendAsync(RelayFrames.PingFrame(ReadOnlySpan<byte>.Empty), cancel).ConfigureAwait(false);
            }
        }

        /// <summary>
        /// Sends <c>register</c> when the membership of the set changed (a hub paired or revoked, the UTC date,
        /// a hub moving in or out of the 21), at most once per second. False when it had to wait.
        /// </summary>
        private async Task<bool> RegisterIfChangedAsync(bool force)
        {
            var routes = _c._host.RelayRoutes(_c._host.UtcToday);
            var key = string.Join(",", routes.Select(r => r.Route).Order(StringComparer.Ordinal));
            if (!force && key == _registeredKey) return true;
            var now = Time.GetTimestamp();
            if (_lastRegister != long.MinValue && Time.GetElapsedTime(_lastRegister, now) < RegisterMinInterval) return false;
            lock (_routesLock)
            {
                // The new set is valid at once; the old one stays valid for opens already in flight
                _recent.RemoveAll(x => Time.GetElapsedTime(x.At, now) > RecentSets);
                if (_routes.Count > 0) _recent.Add((now, _routes));
                _routes = routes.ToDictionary(r => r.Route, r => r.HubId, StringComparer.Ordinal);
            }
            _registeredKey = key;
            await SendAsync(RelayFrames.RegisterFrame(routes.Select(r => r.Claim)), CancellationToken.None).ConfigureAwait(false);
            _lastRegister = Time.GetTimestamp(); // after the send: the relay's 1 s counts from its arrival
            return true;
        }

        public async Task SendAsync(byte[] frame, CancellationToken cancel)
        {
            await _sendLock.WaitAsync(cancel).ConfigureAwait(false);
            try
            {
                // A send the relay doesn't take in time means a stuck socket: abort it (the timeout
                // cancels the send, which aborts the socket) and reconnect
                using var stuck = new CancellationTokenSource(SendTimeout);
                await _ws.SendAsync(frame, WebSocketMessageType.Binary, true, stuck.Token).ConfigureAwait(false);
                Volatile.Write(ref _lastSent, Time.GetTimestamp());
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                _ws.Abort();
                throw new IOException("the relay socket is stuck");
            }
            finally { _sendLock.Release(); }
        }

        /// <summary>
        /// A pong or close from the receive loop, sent by <see cref="ControlLoopAsync"/> so the loop never waits
        /// for the socket. One pong waits at a time; more than 64 closes waiting means a relay flooding a
        /// stuck socket: abort and reconnect.
        /// </summary>
        private void SendSoon(byte[] frame)
        {
            if (frame[0] == RelayFrames.Pong && Interlocked.Exchange(ref _pongQueued, 1) == 1) return;
            if (!_control.Writer.TryWrite(frame)) _ws.Abort();
        }

        private async Task ControlLoopAsync(CancellationToken cancel)
        {
            await foreach (var f in _control.Reader.ReadAllAsync(cancel).ConfigureAwait(false))
            {
                if (f[0] == RelayFrames.Pong) Volatile.Write(ref _pongQueued, 0);
                await SendAsync(f, cancel).ConfigureAwait(false);
            }
        }

        public void Forget(Stream s) => _streams.TryRemove(new KeyValuePair<uint, Stream>(s.Id, s));
    }

    /// <summary>One relayed stream as the agent host sees it: one Noise message per <c>data</c> frame.</summary>
    private sealed class Stream : IFrameTransport
    {
        private const int InboxLimit = 64;
        private const int InboxBytes = 128 * 1024;
        private readonly Connection _conn;
        private readonly Channel<byte[]> _in = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(InboxLimit) { SingleReader = true });
        private int _remoteClosed, _closed, _queuedBytes;

        public Stream(Connection conn, uint id)
        {
            _conn = conn;
            Id = id;
        }

        public uint Id { get; }

        /// <summary>Queues a message for the session; false when the session doesn't keep up (64 messages / 128 KiB).</summary>
        public bool Deliver(byte[] noise)
        {
            if (Volatile.Read(ref _remoteClosed) != 0 || Volatile.Read(ref _closed) != 0) return true;
            if (Interlocked.Add(ref _queuedBytes, noise.Length) > InboxBytes || !_in.Writer.TryWrite(noise))
            {
                Interlocked.Add(ref _queuedBytes, -noise.Length);
                return false;
            }
            return true;
        }

        public void RemoteClosed()
        {
            Volatile.Write(ref _remoteClosed, 1);
            _in.Writer.TryComplete();
        }

        public async ValueTask<byte[]?> ReceiveAsync(CancellationToken cancel)
        {
            try
            {
                var m = await _in.Reader.ReadAsync(cancel).ConfigureAwait(false);
                Interlocked.Add(ref _queuedBytes, -m.Length);
                return m;
            }
            catch (ChannelClosedException) { return null; }
        }

        public async ValueTask SendAsync(byte[] frame, CancellationToken cancel)
        {
            if (Volatile.Read(ref _remoteClosed) != 0 || Volatile.Read(ref _closed) != 0)
                throw new IOException("the relayed stream is closed");
            await _conn.SendAsync(RelayFrames.DataFrame(Id, frame), cancel).ConfigureAwait(false);
        }

        public async ValueTask CloseAsync()
        {
            if (Interlocked.Exchange(ref _closed, 1) != 0) return;
            _in.Writer.TryComplete();
            _conn.Forget(this);
            if (Volatile.Read(ref _remoteClosed) != 0) return;
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            try { await _conn.SendAsync(RelayFrames.CloseFrame(Id, 1000), cts.Token).ConfigureAwait(false); }
            catch (Exception) { /* the relay socket is gone: the relay closes the hub itself */ }
        }
    }
}
