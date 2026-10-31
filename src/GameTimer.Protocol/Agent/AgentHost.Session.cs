using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using GameTimer.Protocol.Messages;
using GameTimer.Protocol.Noise;

namespace GameTimer.Protocol.Agent;

public sealed partial class AgentHost
{
    /// <summary>
    /// Runs one accepted WebSocket connection to its end: <c>/v2</c> (<paramref name="pairing"/> false)
    /// or <c>/v2/pair</c>. Never throws for anything the remote side does; the connection is always closed.
    /// </summary>
    public async Task RunAsync(IFrameTransport transport, HandshakeTicket ticket, bool pairing, CancellationToken cancel)
    {
        try
        {
            if (pairing) await RunPairingAsync(transport, ticket, cancel).ConfigureAwait(false);
            else await RunSessionAsync(transport, ticket, cancel).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Remote misbehaviour or a dropped socket: nothing to report (§3: no oracle before the handshake)
        }
        finally
        {
            ticket.Dispose();
            try { await transport.CloseAsync().ConfigureAwait(false); } catch { /* already gone */ }
        }
    }

    /// <summary>
    /// Runs an accepted <c>/v2</c> or <c>/v2/pair</c> WebSocket (the web server has checked the path, the
    /// LAN filter and the missing Origin header, and reserved <paramref name="ticket"/>). Returns once the
    /// socket is closed, so the server can dispose it.
    /// </summary>
    public async Task RunWebSocketAsync(System.Net.WebSockets.WebSocket socket, HandshakeTicket ticket, bool pairing, CancellationToken cancel)
    {
        var transport = new WebSocketFrameTransport(socket);
        await RunAsync(transport, ticket, pairing, cancel).ConfigureAwait(false);
        await transport.ReadLoop.ConfigureAwait(false);
    }

    // ---------------- /v2: IK, ready rule, session ----------------

    private async Task RunSessionAsync(IFrameTransport transport, HandshakeTicket ticket, CancellationToken cancel)
    {
        var ip = ticket.RemoteIp;
        CipherState send, recv;
        Peer peer;
        int hubMinor;

        bool relayed = ticket.RelayHubId != null;
        using (var timeout = new CancellationTokenSource(HandshakeTimeout, _time))
        using (var hsCancel = CancellationTokenSource.CreateLinkedTokenSource(cancel, timeout.Token, ticket.Evicted))
        {
            try
            {
                byte[]? msg1;
                // §10.4: a relayed stream brings msg 1 at once, or gives its slot back early
                using (var msg1Timeout = new CancellationTokenSource(relayed ? RelayFirstMessageTimeout : HandshakeTimeout, _time))
                using (var msg1Cancel = CancellationTokenSource.CreateLinkedTokenSource(hsCancel.Token, msg1Timeout.Token))
                    msg1 = await transport.ReceiveAsync(msg1Cancel.Token).ConfigureAwait(false);
                if (msg1 == null) return;

                var hs = new HandshakeState(NoisePattern.IK, initiator: false, Prologue, _key);
                byte[] payload;
                try { payload = hs.ReadMessage(msg1); }
                catch (NoiseException) { RecordFailure(ip); return; }

                // §4.1: only a paired hub key may continue; §4.2: msg 1 carries { "t": "connect" } only.
                // The failed-handshake counter (§3) is never applied to a known hub key, and an unknown key
                // is always refused here, so on /v2 it only counts; it gates /v2/pair.
                var known = FindPeerByKey(hs.RemoteStaticPublicKey!);
                if (known == null || !IsBareMessage(payload, "connect")) { RecordFailure(ip); return; }
                // §10.4: a relayed stream is only for the hub whose route it was opened with
                if (relayed && known.Id != ticket.RelayHubId) { RecordFailure(ip); return; }

                await transport.SendAsync(hs.WriteMessage(ReadOnlySpan<byte>.Empty), hsCancel.Token).ConfigureAwait(false);
                (send, recv) = hs.Split();

                // §4.3: nothing happens until the first transport message proves a live hub
                var first = await transport.ReceiveAsync(hsCancel.Token).ConfigureAwait(false);
                if (first == null) { RecordFailure(ip); return; }
                if (!TryReadReady(recv, first, out hubMinor)) { RecordFailure(ip); return; }
                peer = known;
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                RecordFailure(ip); // handshake timeout
                return;
            }
            catch (ProtocolViolation)
            {
                RecordFailure(ip);
                return;
            }
        }

        // Confirmed. From here on failures are answered with bye (§3).
        ticket.Dispose();
        var session = new Session(this, transport, send, peer, _time) { Relayed = relayed };
        var registered = Register(session);
        if (registered != null)
        {
            session.Bye(registered);
            await session.Completion.ConfigureAwait(false);
            return;
        }

        try
        {
            Persist(); // lastSeen, set by Register
            // Events published from now on wait in the session until hello is out (§6: hello comes first)
            JsonObject hello;
            try { hello = await HelloAsync(session, hubMinor).ConfigureAwait(false); }
            catch (Exception)
            {
                session.Bye("internal"); // the backend failed to produce the state
                return;
            }
            session.SendHello(hello);
            if (!relayed) RaiseSessionStarted(ip); // a relayed session says nothing about this network (§9)
            await ReceiveLoopAsync(session, transport, recv, cancel).ConfigureAwait(false);
        }
        finally
        {
            Unregister(session);
            session.Complete();
            await Task.WhenAny(session.Completion, Task.Delay(TimeSpan.FromSeconds(2), _time)).ConfigureAwait(false);
            if (session.Completion.IsCompleted) session.DisposeTimers();
        }
    }

    private bool TryReadReady(CipherState recv, byte[] frame, out int hubMinor)
    {
        hubMinor = 0;
        byte[] plain;
        try { plain = recv.Decrypt(frame); }
        catch (NoiseException) { return false; }
        using var doc = StrictJson.Parse(plain);
        var r = doc.RootElement;
        if (Str(r, "t") != "ready") return false;
        if (!r.TryGetProperty("protocol", out var p) || !p.TryGetInt32(out var major) || major != ProtocolMajor) return false;
        if (!r.TryGetProperty("minor", out var m) || !m.TryGetInt32(out hubMinor) || hubMinor < 0) return false;
        return true;
    }

    private async Task<JsonObject> HelloAsync(Session session, int hubMinor)
    {
        string ak;
        lock (_lock) ak = _state.Peers.FirstOrDefault(p => p.Id == session.HubId)?.Ak ?? ""; // revoked meanwhile: the bye is queued already
        var caps = new JsonArray();
        foreach (var c in Capabilities) caps.Add(c);
        return new JsonObject
        {
            ["t"] = "hello",
            ["protocol"] = ProtocolMajor,
            ["minor"] = Math.Min(hubMinor, ProtocolMinor),
            ["agent"] = AgentJson(),
            ["you"] = new JsonObject { ["id"] = session.HubId, ["role"] = RoleName(session.Role) },
            ["capabilities"] = caps,
            ["remoteAccess"] = RemoteAccess, // §6 / §10.2: the hub dials the relay only while this is on
            ["ak"] = ak,
            ["state"] = await _backend.GetStateAsync().ConfigureAwait(false),
        };
    }

    /// <summary>
    /// §3 session limits: 2 per hub (a new one closes that hub's oldest), 16 in total. Re-checks under
    /// the lock that the hub is still paired (it may have been revoked during the handshake).
    /// Returns null when registered, else the bye code to close with.
    /// </summary>
    private string? Register(Session session)
    {
        Session? evict = null;
        lock (_lock)
        {
            if (_disposed) return "shutdown";
            var peer = _state.Peers.FirstOrDefault(p => p.Id == session.HubId);
            if (peer == null) return "revoked";
            var mine = _sessions.Where(s => s.HubId == session.HubId).OrderBy(s => s.StartedAt).ToList();
            if (mine.Count >= MaxSessionsPerHub) evict = mine[0];
            else if (_sessions.Count >= MaxSessions) return "busy";
            if (evict != null) _sessions.Remove(evict);
            session.Role = peer.Role;
            peer.LastSeen = _time.GetUtcNow();
            _sessions.Add(session);
        }
        evict?.Bye("busy");
        return null;
    }

    private void Unregister(Session session)
    {
        lock (_lock) _sessions.Remove(session);
    }

    private async Task ReceiveLoopAsync(Session session, IFrameTransport transport, CipherState recv, CancellationToken cancel)
    {
        while (!session.IsClosing)
        {
            using var idle = new CancellationTokenSource(IdleTimeout, _time);
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancel, idle.Token, session.Lifetime);
            byte[]? frame;
            try
            {
                frame = await transport.ReceiveAsync(linked.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                if (session.Lifetime.IsCancellationRequested) session.Bye("expired");
                else if (cancel.IsCancellationRequested) session.Bye("shutdown");
                else session.Abort(); // 90 s of silence
                return;
            }
            catch (ProtocolViolation)
            {
                session.Bye("protocol_error"); // e.g. a text message
                return;
            }
            if (frame == null) { session.Abort(); return; }
            if (session.IsClosing) return; // revoked or evicted meanwhile: nothing more is executed

            try
            {
                byte[] plain;
                try { plain = recv.Decrypt(frame); }
                catch (NoiseException) { throw new ProtocolViolation("transport message did not decrypt"); }
                await HandleMessageAsync(session, plain).ConfigureAwait(false);
            }
            catch (ProtocolViolation)
            {
                session.Bye("protocol_error");
                return;
            }
        }
    }

    // ---------------- helpers ----------------

    /// <summary>True if the plaintext is exactly an I-JSON object whose <c>t</c> is <paramref name="type"/>.</summary>
    private static bool IsBareMessage(byte[] payload, string type)
    {
        try
        {
            using var doc = StrictJson.Parse(payload);
            return Str(doc.RootElement, "t") == type;
        }
        catch (ProtocolViolation)
        {
            return false;
        }
    }

    internal static string? Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    internal static byte[] Serialize(JsonObject message) => JsonSerializer.SerializeToUtf8Bytes(message);

    /// <summary>
    /// A confirmed session. Everything it sends goes through one outbox and one send loop, so the
    /// send CipherState (not thread-safe) is only ever used by that loop, in order. Until
    /// <see cref="SendHello"/> runs, messages are held back so that hello is always first (§6).
    /// </summary>
    internal sealed class Session
    {
        private const int OutboxCapacity = 257; // hello + up to 256 held-back messages
        private const int HeldBackLimit = OutboxCapacity - 1;
        /// <summary>A peer that doesn't read its bye in this time is cut off (it would otherwise hold a slot).</summary>
        private static readonly TimeSpan ByeGrace = TimeSpan.FromSeconds(2);
        private readonly IFrameTransport _transport;
        private readonly CipherState _send;
        private readonly Channel<JsonObject> _outbox =
            Channel.CreateBounded<JsonObject>(new BoundedChannelOptions(OutboxCapacity) { SingleReader = true });
        private readonly CancellationTokenSource _lifetime;
        private readonly CancellationTokenSource _abort = new();
        private readonly object _gate = new();
        private List<JsonObject>? _beforeHello = new();
        private string? _byeCode;
        private int _closing;
        private readonly TimeProvider _time;
        private ITimer? _byeTimer;

        public Session(AgentHost host, IFrameTransport transport, CipherState send, Peer peer, TimeProvider time)
        {
            _ = host;
            _time = time;
            _transport = transport;
            _send = send;
            HubId = peer.Id;
            HubName = peer.Name;
            Role = peer.Role;
            StartedAt = time.GetUtcNow();
            _lifetime = new CancellationTokenSource(SessionLifetime, time);
            Completion = Task.Run(SendLoopAsync);
        }

        public string HubId { get; }
        public string HubName { get; }
        /// <summary>The session came through the relay (§10): ended when "Control from anywhere" is turned off.</summary>
        public bool Relayed { get; init; }
        private volatile PeerRole _role;
        public PeerRole Role { get => _role; set => _role = value; }
        public DateTimeOffset StartedAt { get; }
        public long LastId { get; set; }
        public CancellationToken Lifetime => _lifetime.Token;
        public Task Completion { get; }
        public bool IsClosing => Volatile.Read(ref _closing) != 0;

        /// <summary>Queues a message; dropped once the session is closing. A full outbox (dead reader) aborts.</summary>
        public void Enqueue(JsonObject message)
        {
            lock (_gate)
            {
                if (IsClosing) return;
                if (_beforeHello != null)
                {
                    if (_beforeHello.Count < HeldBackLimit) { _beforeHello.Add(message); return; }
                }
                else if (_outbox.Writer.TryWrite(message)) return;
            }
            Abort(); // a reader that can't keep up
        }

        /// <summary>Sends hello, then whatever was published while it was being built.</summary>
        public void SendHello(JsonObject hello)
        {
            lock (_gate)
            {
                var held = _beforeHello ?? new List<JsonObject>();
                _beforeHello = null;
                if (IsClosing) return;
                // Capacity is hello + the held-back limit, so these writes can't fail
                _outbox.Writer.TryWrite(hello);
                foreach (var m in held) _outbox.Writer.TryWrite(m);
            }
        }

        /// <summary>Sends <c>bye</c> after what is already queued (never dropped, even with a full outbox), then closes (§3).</summary>
        public void Bye(string code)
        {
            lock (_gate)
            {
                if (IsClosing) return;
                Volatile.Write(ref _closing, 1);
                _byeCode = code;
                _beforeHello = null; // hello never went out: just the bye
                _outbox.Writer.TryComplete();
                _byeTimer = _time.CreateTimer(_ => Abort(), null, ByeGrace, Timeout.InfiniteTimeSpan);
            }
        }

        /// <summary>Closes without a goodbye (dead connection, idle timeout, bye not read); unblocks a stuck send.</summary>
        public void Abort()
        {
            lock (_gate)
            {
                Volatile.Write(ref _closing, 1);
                _byeCode = null;
                _beforeHello = null;
                _outbox.Writer.TryComplete();
            }
            // Outside the lock: cancellation callbacks and the transport's close run arbitrary code
            try { _abort.Cancel(); } catch (ObjectDisposedException) { }
            _ = CloseQuietlyAsync();
        }

        /// <summary>The receive side ended: let the send loop drain and stop.</summary>
        public void Complete()
        {
            lock (_gate)
            {
                Volatile.Write(ref _closing, 1);
                _outbox.Writer.TryComplete();
            }
        }

        private async Task SendLoopAsync()
        {
            try
            {
                await foreach (var message in _outbox.Reader.ReadAllAsync().ConfigureAwait(false))
                    await SendAsync(message).ConfigureAwait(false);
                string? bye;
                lock (_gate) bye = _byeCode;
                if (bye != null) await SendAsync(new JsonObject { ["t"] = "bye", ["code"] = bye }).ConfigureAwait(false);
            }
            catch
            {
                // socket gone; the receive side notices too
            }
            finally
            {
                await CloseQuietlyAsync().ConfigureAwait(false);
            }
        }

        private async Task SendAsync(JsonObject message)
        {
            var frame = _send.Encrypt(Serialize(message));
            await _transport.SendAsync(frame, _abort.Token).ConfigureAwait(false);
        }

        private async Task CloseQuietlyAsync()
        {
            try { await _transport.CloseAsync().ConfigureAwait(false); }
            catch { /* already gone */ }
        }

        /// <summary>After both loops have ended.</summary>
        public void DisposeTimers()
        {
            lock (_gate) _byeTimer?.Dispose();
            _lifetime.Dispose();
            _abort.Dispose();
        }
    }
}
