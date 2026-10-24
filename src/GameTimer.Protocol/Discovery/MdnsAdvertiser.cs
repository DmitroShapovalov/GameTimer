using System.Net;
using System.Net.Sockets;

namespace GameTimer.Protocol.Discovery;

/// <summary>
/// Puts <see cref="MdnsResponder"/> on the network: a UDP socket on port 5353 shared with other mDNS
/// responders (Windows' own, Bonjour, browsers), joined to 224.0.0.251 on each LAN address of the service.
///
/// <list type="bullet">
/// <item>Queries are answered by multicast; questions that all carry the QU bit get a unicast reply;
///   a query from a port other than 5353 (a plain resolver) gets a legacy unicast reply (RFC 6762 §6.7).</item>
/// <item>Only queries from <c>acceptFrom</c> sources (the home LAN) are answered.</item>
/// <item>The same multicast reply is sent at most once per second (RFC 6762 §6.2).</item>
/// <item>Announces twice at start (1 s apart, §8.3) and whenever the service changes; sends a goodbye
///   (TTL 0) when stopped. The service is re-read every <see cref="PollInterval"/> and on
///   <see cref="Refresh"/>, so a new day's tags or a new DHCP address go out without the caller's help.</item>
/// </list>
/// IPv4 only, like the pairing QR. Errors are reported through <c>log</c> and never thrown at the caller.
/// </summary>
public sealed class MdnsAdvertiser : IAsyncDisposable
{
    public const int Port = 5353;
    public static readonly IPAddress Group = IPAddress.Parse("224.0.0.251");
    public static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan MulticastRepeat = TimeSpan.FromSeconds(1);

    private readonly Func<MdnsService?> _service;
    private readonly Func<IPAddress, bool> _acceptFrom;
    private readonly Action<string>? _log;
    private readonly object _lock = new();
    private readonly Dictionary<string, DateTime> _lastMulticast = new();
    private readonly SemaphoreSlim _refresh = new(0, 1);
    private readonly SemaphoreSlim _send = new(1, 1); // the multicast interface is a socket option: one send at a time
    private CancellationTokenSource? _cts;
    private Task? _loop;
    private UdpClient? _socket;
    private MdnsService? _current;
    private volatile bool _stopping;
    private volatile bool _rebind; // the receive loop hit a socket error
    private DateTime _lastPacketError;
    private string? _lastBindError;
    private readonly Dictionary<IPAddress, DateTime> _lastUnicast = new();
    private readonly Queue<DateTime> _unicastWindow = new();
    private const int MaxUnicastPerSecond = 20;

    /// <param name="service">The service to announce now; null = announce nothing (e.g. no LAN address).</param>
    /// <param name="acceptFrom">Which query sources to answer (the home LAN).</param>
    public MdnsAdvertiser(Func<MdnsService?> service, Func<IPAddress, bool> acceptFrom, Action<string>? log = null)
    {
        _service = service;
        _acceptFrom = acceptFrom;
        _log = log;
    }

    public bool IsRunning => _loop != null;

    public void Start()
    {
        lock (_lock)
        {
            if (_loop != null) return;
            _cts = new CancellationTokenSource();
            _stopping = false;
            var token = _cts.Token;
            _loop = Task.Run(() => RunAsync(token));
        }
    }

    /// <summary>The service may have changed (peers paired or revoked): re-read it now. Any thread.</summary>
    public void Refresh()
    {
        try { _refresh.Release(); }
        catch (SemaphoreFullException) { } // a refresh is already pending
    }

    public async Task StopAsync()
    {
        Task? loop;
        lock (_lock)
        {
            loop = _loop;
            _cts?.Cancel();
            _loop = null;
        }
        if (loop != null)
        {
            try { await loop.ConfigureAwait(false); }
            catch (OperationCanceledException) { }
        }
        _cts?.Dispose();
        _cts = null;
    }

    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);

    private async Task RunAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                try { await IterateAsync(ct).ConfigureAwait(false); }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception ex)
                {
                    // Never give up while the server runs: log, start over with a fresh socket
                    _log?.Invoke($"mDNS: error, restarting: {ex.Message}");
                    _socket?.Dispose();
                    _socket = null;
                    await Task.Delay(TimeSpan.FromSeconds(5), ct).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            _stopping = true; // a pending second announcement must not go out after the goodbye
            if (_current != null && _socket != null)
            {
                try { await SendAnnouncementAsync(_current, goodbye: true).ConfigureAwait(false); } catch { }
            }
            _current = null;
            _socket?.Dispose();
            _socket = null;
        }
    }

    private async Task IterateAsync(CancellationToken ct)
    {
        if (_rebind) await Task.Delay(TimeSpan.FromSeconds(1), ct).ConfigureAwait(false); // a broken socket can't spin us
        var next = SafeService();
        bool hadSocket = _socket != null;
        bool changed = !SameService(next, _current);
        if (changed)
        {
            var old = _current;
            // A new name or no service: withdraw the old records. Same name: the new records carry the
            // cache-flush bit and replace the old ones (TXT with new tags, A with a new address).
            if (old != null && _socket != null && !SameIdentity(old, next))
                await SendAnnouncementAsync(old, goodbye: true).ConfigureAwait(false);
            if (next == null || old == null || !SameAddresses(old, next))
                Rebind(next);
            _current = next;
        }
        else if (next != null && (_socket == null || _rebind))
        {
            Rebind(next); // the last bind failed (interface not ready, port busy): retry every poll
        }
        if (next != null && _socket != null && (changed || !hadSocket))
        {
            await SendAnnouncementAsync(next, goodbye: false).ConfigureAwait(false);
            _ = AnnounceAgainAsync(next, ct);
        }
        using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var receive = _socket != null ? ReceiveLoopAsync(_socket, wait.Token) : Task.Delay(Timeout.Infinite, wait.Token);
        // Wake up on a refresh, the poll interval, or the receive loop ending (socket error → rebind)
        var refresh = _refresh.WaitAsync(PollInterval, wait.Token);
        try { await Task.WhenAny(refresh, receive).ConfigureAwait(false); }
        finally
        {
            wait.Cancel();
            try { await receive.ConfigureAwait(false); } catch (OperationCanceledException) { }
            try { await refresh.ConfigureAwait(false); } catch (OperationCanceledException) { }
        }
        ct.ThrowIfCancellationRequested();
    }

    // The second announcement, one second later (RFC 6762 §8.3), unless the service changed meanwhile.
    private async Task AnnounceAgainAsync(MdnsService s, CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(1), ct).ConfigureAwait(false);
            if (ReferenceEquals(_current, s) && _socket != null)
                await SendAnnouncementAsync(s, goodbye: false, stillValid: () => !_stopping && ReferenceEquals(_current, s)).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { _log?.Invoke($"mDNS: announce failed: {ex.Message}"); }
    }

    private MdnsService? SafeService()
    {
        try { return _service(); }
        catch (Exception ex)
        {
            _log?.Invoke($"mDNS: service unavailable: {ex.Message}");
            return _current;
        }
    }

    private void Rebind(MdnsService? s)
    {
        _rebind = false;
        _socket?.Dispose();
        _socket = null;
        if (s == null) return;
        var addresses = s.Addresses.Where(a => a.AddressFamily == AddressFamily.InterNetwork).ToList();
        if (addresses.Count == 0) return;
        try
        {
            var socket = new UdpClient(AddressFamily.InterNetwork);
            socket.ExclusiveAddressUse = false;
            socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            socket.Client.Bind(new IPEndPoint(IPAddress.Any, Port));
            socket.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
            socket.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, true);
            int joined = 0;
            foreach (var a in addresses)
            {
                try
                {
                    socket.JoinMulticastGroup(Group, a);
                    joined++;
                }
                catch (SocketException ex) { LogBind($"mDNS: cannot join on {a}: {ex.SocketErrorCode}"); }
            }
            if (joined == 0)
            {
                socket.Dispose();
                return;
            }
            _socket = socket;
            _lastBindError = null;
        }
        catch (SocketException ex)
        {
            LogBind($"mDNS: cannot open port {Port}: {ex.SocketErrorCode}");
        }
    }

    // Bind is retried every poll: log a failure once, not every 30 s
    private void LogBind(string message)
    {
        if (message == _lastBindError) return;
        _lastBindError = message;
        _log?.Invoke(message);
    }

    private async Task ReceiveLoopAsync(UdpClient socket, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult packet;
            try { packet = await socket.ReceiveAsync(ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException ex) when (ex.SocketErrorCode is SocketError.ConnectionReset or SocketError.NetworkReset)
            {
                continue; // ICMP noise on Windows after a unicast reply
            }
            catch (SocketException ex)
            {
                LogBind($"mDNS: receive failed: {ex.SocketErrorCode}");
                _rebind = true; // the poll loop opens a new socket
                return;
            }
            try { await HandleAsync(socket, packet, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            catch (Exception ex)
            {
                // One bad packet never stops the loop; logged at most once a minute (it may be a flood)
                var now = DateTime.UtcNow;
                if (now - _lastPacketError >= TimeSpan.FromMinutes(1))
                {
                    _lastPacketError = now;
                    _log?.Invoke($"mDNS: packet ignored: {ex.GetType().Name}");
                }
            }
        }
    }

    private async Task HandleAsync(UdpClient socket, UdpReceiveResult packet, CancellationToken ct)
    {
        var s = _current;
        if (s == null || packet.Buffer.Length > 9000) return;
        var from = packet.RemoteEndPoint;
        var source = from.Address.IsIPv4MappedToIPv6 ? from.Address.MapToIPv4() : from.Address;
        if (!_acceptFrom(source)) return;
        bool legacy = from.Port != Port;
        var answer = MdnsResponder.Answer(s, packet.Buffer, legacy);
        if (answer == null) return;
        try
        {
            // The source of a UDP query can be forged: cap unicast replies per source and in total, so
            // the PC can't be used to flood another host on the LAN. A QU question over the cap still
            // gets its answer by multicast (RFC 6762 §5.4 allows that), which has its own limit.
            if ((legacy || answer.Value.AllUnicast) && MayUnicast(source))
                await socket.SendAsync(answer.Value.Packet, from, ct).ConfigureAwait(false);
            else if (!legacy && MayMulticast(answer.Value.Packet)) // not legacy: the packet is the multicast form
                await SendMulticastAsync(socket, s, answer.Value.Packet).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
            _log?.Invoke($"mDNS: reply failed: {ex.Message}");
        }
    }

    private bool MayUnicast(IPAddress source)
    {
        var now = DateTime.UtcNow;
        lock (_lastUnicast)
        {
            while (_unicastWindow.Count > 0 && now - _unicastWindow.Peek() >= TimeSpan.FromSeconds(1)) _unicastWindow.Dequeue();
            if (_unicastWindow.Count >= MaxUnicastPerSecond) return false;
            if (_lastUnicast.TryGetValue(source, out var last) && now - last < MulticastRepeat) return false;
            if (_lastUnicast.Count > 256)
                foreach (var old in _lastUnicast.Where(kv => now - kv.Value >= MulticastRepeat).Select(kv => kv.Key).ToList())
                    _lastUnicast.Remove(old);
            _lastUnicast[source] = now;
            _unicastWindow.Enqueue(now);
            return true;
        }
    }

    private bool MayMulticast(byte[] packet)
    {
        var key = Convert.ToBase64String(System.Security.Cryptography.SHA256.HashData(packet));
        var now = DateTime.UtcNow;
        lock (_lastMulticast)
        {
            if (_lastMulticast.TryGetValue(key, out var last) && now - last < MulticastRepeat) return false;
            if (_lastMulticast.Count > 64)
                foreach (var old in _lastMulticast.Where(kv => now - kv.Value >= MulticastRepeat).Select(kv => kv.Key).ToList())
                    _lastMulticast.Remove(old);
            _lastMulticast[key] = now;
            return true;
        }
    }

    private async Task SendAnnouncementAsync(MdnsService s, bool goodbye, Func<bool>? stillValid = null)
    {
        var socket = _socket;
        if (socket == null) return;
        try { await SendMulticastAsync(socket, s, MdnsResponder.Announcement(s, goodbye), stillValid).ConfigureAwait(false); }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
            _log?.Invoke($"mDNS: announce failed: {ex.Message}");
        }
    }

    // Sends out of every LAN interface of the service (the OS would otherwise pick just one).
    private async Task SendMulticastAsync(UdpClient socket, MdnsService s, byte[] packet, Func<bool>? stillValid = null)
    {
        var target = new IPEndPoint(Group, Port);
        await _send.WaitAsync().ConfigureAwait(false);
        try
        {
            if (stillValid != null && !stillValid()) return;
            foreach (var a in s.Addresses.Where(a => a.AddressFamily == AddressFamily.InterNetwork))
            {
                socket.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, a.GetAddressBytes());
                await socket.SendAsync(packet, target).ConfigureAwait(false);
            }
        }
        finally { _send.Release(); }
    }

    private static bool SameIdentity(MdnsService? a, MdnsService? b) =>
        a != null && b != null && a.InstanceName == b.InstanceName && a.HostName == b.HostName;

    private static bool SameAddresses(MdnsService a, MdnsService b) => a.Addresses.SequenceEqual(b.Addresses);

    internal static bool SameService(MdnsService? a, MdnsService? b) =>
        (a == null && b == null)
        || (SameIdentity(a, b) && a!.Port == b!.Port && SameAddresses(a, b) && a.Txt.SequenceEqual(b.Txt));
}
