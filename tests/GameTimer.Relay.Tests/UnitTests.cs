using System.Net;
using System.Net.WebSockets;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace GameTimer.Relay.Tests;

/// <summary>Pieces tested without a server: message assembly and its accounting, the per-IP table.</summary>
public class UnitTests
{
    private static RelayState State(Action<RelayOptions>? tweak = null)
    {
        var o = new RelayOptions();
        tweak?.Invoke(o);
        return new RelayState(Options.Create(o), TimeProvider.System, NullLogger<RelayState>.Instance);
    }

    /// <summary>A socket that delivers scripted pieces (then throws, like an aborted socket).</summary>
    private sealed class Script : WebSocket
    {
        private readonly Queue<(byte[] Data, WebSocketMessageType Type, bool End)> _q;

        public Script(params (byte[] Data, WebSocketMessageType Type, bool End)[] pieces) => _q = new(pieces);

        public override async Task<WebSocketReceiveResult> ReceiveAsync(ArraySegment<byte> buffer, CancellationToken ct)
        {
            await Task.Yield();
            if (_q.Count == 0) throw new WebSocketException("aborted");
            var (d, t, end) = _q.Dequeue();
            if (t == WebSocketMessageType.Close) return new WebSocketReceiveResult(0, t, true, WebSocketCloseStatus.NormalClosure, null);
            Assert.True(d.Length <= buffer.Count);
            d.CopyTo(buffer.Array!, buffer.Offset);
            return new WebSocketReceiveResult(d.Length, t, end);
        }

        public override WebSocketCloseStatus? CloseStatus => null;
        public override string? CloseStatusDescription => null;
        public override WebSocketState State => WebSocketState.Open;
        public override string? SubProtocol => null;
        public override void Abort() { }
        public override Task CloseAsync(WebSocketCloseStatus s, string? d, CancellationToken ct) => Task.CompletedTask;
        public override Task CloseOutputAsync(WebSocketCloseStatus s, string? d, CancellationToken ct) => Task.CompletedTask;
        public override Task SendAsync(ArraySegment<byte> b, WebSocketMessageType t, bool e, CancellationToken ct) => Task.CompletedTask;
        public override void Dispose() { }
    }

    private static (byte[], WebSocketMessageType, bool) Piece(int n, byte first = 3, bool end = false)
    {
        var d = new byte[n];
        if (n > 0) d[0] = first;
        for (int i = 1; i < n; i++) d[i] = (byte)i;
        return (d, WebSocketMessageType.Binary, end);
    }

    [Fact]
    public async Task Pieces_are_joined_and_the_budget_returns_to_zero()
    {
        var s = State();
        var (kind, data) = await WsIo.ReceiveAsync(new Script(Piece(4096), Piece(4096), Piece(100, end: true)), 70_000, s);
        Assert.Equal(Received.Binary, kind);
        Assert.Equal(8292, data!.Length);
        Assert.Equal(0, s.Buffered);
    }

    [Fact]
    public async Task Every_way_out_releases_the_budget()
    {
        var s = State();
        Assert.Equal(Received.TooBig, (await WsIo.ReceiveAsync(new Script(Piece(4096), Piece(4096)), 6000, s)).Kind);
        Assert.Equal(0, s.Buffered);
        Assert.Equal(Received.Close, (await WsIo.ReceiveAsync(new Script(Piece(4096), (Array.Empty<byte>(), WebSocketMessageType.Close, true)), 70_000, s)).Kind);
        Assert.Equal(0, s.Buffered);
        await Assert.ThrowsAsync<WebSocketException>(() => WsIo.ReceiveAsync(new Script(Piece(4096)), 70_000, s));
        Assert.Equal(0, s.Buffered);
    }

    [Fact]
    public async Task Over_budget_the_message_is_dropped_but_its_header_is_kept()
    {
        var s = State(o => o.MaxBufferedBytes = 1000);
        var (kind, head) = await WsIo.ReceiveAsync(new Script(Piece(4096, first: 3), Piece(4096), Piece(10, end: true)), 70_000, s);
        Assert.Equal(Received.Overloaded, kind);
        Assert.Equal(new byte[] { 3, 1, 2, 3, 4 }, head);
        Assert.Equal(0, s.Buffered);
        // The next message is read normally
        var (k2, d2) = await WsIo.ReceiveAsync(new Script(Piece(10, end: true)), 70_000, s);
        Assert.Equal(Received.Binary, k2);
        Assert.Equal(10, d2!.Length);
    }

    [Fact]
    public void Full_table_evicts_idle_entries_but_never_open_sockets()
    {
        var o = new RelayOptions { MaxIpEntries = 16, NewSocketsPerIpPerSecond = 100 };
        var l = new IpLimits(o, TimeProvider.System);
        // 8 clients with an open socket (2 entries each: address + /24)
        var open = Enumerable.Range(0, 8).Select(i => IpLimits.ClientOf(IPAddress.Parse($"10.{i}.0.1"))).ToList();
        foreach (var c in open) Assert.True(l.TryStart(c, agent: true));
        Assert.Equal(16, l.Count());
        // The table is full of open sockets: a new client is refused
        Assert.False(l.TryStart(IpLimits.ClientOf(IPAddress.Parse("10.99.0.1")), agent: true));
        // Free some: their entries can now be evicted for newcomers
        foreach (var c in open.Take(4)) l.Closed(c, agent: true);
        Assert.True(l.TryStart(IpLimits.ClientOf(IPAddress.Parse("10.99.0.1")), agent: true));
        Assert.True(l.Count() <= 16);
        // The clients still holding sockets kept their entries (their limits still apply)
        foreach (var c in open.Skip(4)) { l.Closed(c, agent: true); }
    }

    [Fact]
    public void Ipv4_24_has_a_wider_budget()
    {
        var o = new RelayOptions { AgentSocketsPerIp = 1, NewSocketsPerIpPerSecond = 100 };
        var l = new IpLimits(o, TimeProvider.System);
        for (int i = 1; i <= IpLimits.WideFactor; i++) Assert.True(l.TryStart(IpLimits.ClientOf(IPAddress.Parse($"192.0.2.{i}")), agent: true));
        Assert.False(l.TryStart(IpLimits.ClientOf(IPAddress.Parse("192.0.2.200")), agent: true));
        Assert.True(l.TryStart(IpLimits.ClientOf(IPAddress.Parse("192.0.3.1")), agent: true));
    }
}
