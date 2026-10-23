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

    // ---------------- /v2: IK, ready rule, session ----------------

    private async Task RunSessionAsync(IFrameTransport transport, HandshakeTicket ticket, CancellationToken cancel)
    {
        var ip = ticket.RemoteIp;
        CipherState send, recv;
        Peer peer;
        int hubMinor;

        using (var timeout = new CancellationTokenSource(HandshakeTimeout, _time))
        using (var hsCancel = CancellationTokenSource.CreateLinkedTokenSource(cancel, timeout.Token))
        {
            try
            {
                var msg1 = await transport.ReceiveAsync(hsCancel.Token).ConfigureAwait(false);
                if (msg1 == null) return;

                var hs = new HandshakeState(NoisePattern.IK, initiator: false, Prologue, _key);
                byte[] payload;
                try { payload = hs.ReadMessage(msg1); }
                catch (NoiseException) { RecordFailure(ip); return; }

                // §4.1: only a paired hub key may continue; §4.2: msg 1 carries { "t": "connect" } only
                var known = FindPeerByKey(hs.RemoteStaticPublicKey!);
                if (known == null || !IsBareMessage(payload, "connect")) { RecordFailure(ip); return; }

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
        var session = new Session(this, transport, send, peer, _time);
        if (!Register(session))
        {
            session.Bye("busy");
            await session.Completion.ConfigureAwait(false);
            return;
        }

        try
        {
            lock (_lock)
            {
                var stored = _state.Peers.FirstOrDefault(p => p.Id == peer.Id);
                if (stored != null)
                {
                    stored.LastSeen = _time.GetUtcNow();
                    _store.Save(_state);
                }
            }
            session.Enqueue(await HelloAsync(session, hubMinor).ConfigureAwait(false));
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
        lock (_lock) ak = _state.Peers.First(p => p.Id == session.HubId).Ak;
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
            ["ak"] = ak,
            ["state"] = await _backend.GetStateAsync().ConfigureAwait(false),
        };
    }

    /// <summary>§3 session limits: 2 per hub (a new one closes that hub's oldest), 16 in total.</summary>
    private bool Register(Session session)
    {
        Session? evict = null;
        lock (_lock)
        {
            if (_disposed) return false;
            var mine = _sessions.Where(s => s.HubId == session.HubId).OrderBy(s => s.StartedAt).ToList();
            if (mine.Count >= MaxSessionsPerHub) evict = mine[0];
            else if (_sessions.Count >= MaxSessions) return false;
            if (evict != null) _sessions.Remove(evict);
            _sessions.Add(session);
        }
        evict?.Bye("busy");
        return true;
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
    /// send CipherState (not thread-safe) is only ever used by that loop, in order.
    /// </summary>
    internal sealed class Session
    {
        private const int OutboxCapacity = 256;
        private readonly AgentHost _host;
        private readonly IFrameTransport _transport;
        private readonly CipherState _send;
        private readonly Channel<JsonObject> _outbox =
            Channel.CreateBounded<JsonObject>(new BoundedChannelOptions(OutboxCapacity) { SingleReader = true });
        private readonly CancellationTokenSource _lifetime;
        private int _closing;

        public Session(AgentHost host, IFrameTransport transport, CipherState send, Peer peer, TimeProvider time)
        {
            _host = host;
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
        public PeerRole Role { get; set; }
        public DateTimeOffset StartedAt { get; }
        public long LastId { get; set; }
        public CancellationToken Lifetime => _lifetime.Token;
        public Task Completion { get; }
        public bool IsClosing => Volatile.Read(ref _closing) != 0;

        /// <summary>Queues a message; dropped once the session is closing. A full outbox (dead reader) aborts.</summary>
        public void Enqueue(JsonObject message)
        {
            if (IsClosing) return;
            if (!_outbox.Writer.TryWrite(message)) Abort();
        }

        /// <summary>Sends <c>bye</c> after what is already queued, then closes (§3).</summary>
        public void Bye(string code)
        {
            if (Interlocked.Exchange(ref _closing, 1) != 0) return;
            if (!_outbox.Writer.TryWrite(new JsonObject { ["t"] = "bye", ["code"] = code })) { }
            _outbox.Writer.TryComplete();
        }

        /// <summary>Closes without a goodbye (dead connection, idle timeout).</summary>
        public void Abort()
        {
            Interlocked.Exchange(ref _closing, 1);
            _outbox.Writer.TryComplete();
            _ = _transport.CloseAsync();
        }

        /// <summary>The receive side ended: let the send loop drain and stop.</summary>
        public void Complete()
        {
            Interlocked.Exchange(ref _closing, 1);
            _outbox.Writer.TryComplete();
        }

        private async Task SendLoopAsync()
        {
            try
            {
                await foreach (var message in _outbox.Reader.ReadAllAsync().ConfigureAwait(false))
                {
                    var frame = _send.Encrypt(Serialize(message));
                    await _transport.SendAsync(frame, CancellationToken.None).ConfigureAwait(false);
                }
            }
            catch
            {
                // socket gone; the receive side notices too
            }
            finally
            {
                try { await _transport.CloseAsync().ConfigureAwait(false); } catch { }
            }
        }

        /// <summary>After both loops have ended.</summary>
        public void DisposeTimers()
        {
            _lifetime.Dispose();
        }
    }
}
