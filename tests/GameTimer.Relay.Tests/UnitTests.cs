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
        return new RelayState(Options.Create(o), TimeProvider.System, NullLogger<RelayState>.Instance, new FakePush());
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

    /// <summary>Plays Google: checks the signed JWT, hands out a token, checks the FCM call.</summary>
    private sealed class FakeGoogle : HttpMessageHandler
    {
        public required System.Security.Cryptography.RSA Key { get; init; }
        public List<System.Text.Json.Nodes.JsonNode> Messages { get; } = new();
        public int TokenCalls;
        public int Unauthorized; // answer 401 this many times first
        public string? Unregistered; // answer 404 for this token

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage req, CancellationToken ct)
        {
            if (req.RequestUri!.AbsoluteUri == "https://oauth2.example/token")
            {
                Interlocked.Increment(ref TokenCalls);
                var form = System.Web.HttpUtility.ParseQueryString(await req.Content!.ReadAsStringAsync(ct));
                Assert.Equal("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"]);
                var parts = form["assertion"]!.Split('.');
                static byte[] D(string s) => Convert.FromBase64String(s.Replace('-', '+').Replace('_', '/').PadRight((s.Length + 3) / 4 * 4, '='));
                Assert.True(Key.VerifyData(System.Text.Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]), D(parts[2]),
                    System.Security.Cryptography.HashAlgorithmName.SHA256, System.Security.Cryptography.RSASignaturePadding.Pkcs1));
                var claims = System.Text.Json.Nodes.JsonNode.Parse(D(parts[1]))!;
                Assert.Equal("relay@test.iam", claims["iss"]!.GetValue<string>());
                Assert.Equal("https://www.googleapis.com/auth/firebase.messaging", claims["scope"]!.GetValue<string>());
                Assert.Equal("https://oauth2.example/token", claims["aud"]!.GetValue<string>());
                return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent("{\"access_token\":\"AT\",\"expires_in\":3600}") };
            }
            Assert.Equal("https://fcm.googleapis.com/v1/projects/proj-1/messages:send", req.RequestUri.AbsoluteUri);
            Assert.Equal("Bearer AT", req.Headers.Authorization!.ToString());
            if (Interlocked.Decrement(ref Unauthorized) >= 0) return new HttpResponseMessage(System.Net.HttpStatusCode.Unauthorized);
            var body = System.Text.Json.Nodes.JsonNode.Parse(await req.Content!.ReadAsStringAsync(ct))!;
            if (body["message"]!["token"]!.GetValue<string>() == Unregistered) return new HttpResponseMessage(System.Net.HttpStatusCode.NotFound);
            lock (Messages) Messages.Add(body);
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent("{}") };
        }
    }

    [Fact]
    public async Task Fcm_wake_up_is_a_signed_content_free_data_message()
    {
        using var key = System.Security.Cryptography.RSA.Create(2048);
        var account = new System.Text.Json.Nodes.JsonObject
        {
            ["type"] = "service_account", ["project_id"] = "proj-1", ["client_email"] = "relay@test.iam",
            ["private_key"] = key.ExportPkcs8PrivateKeyPem(), ["token_uri"] = "https://oauth2.example/token",
        };
        var options = Options.Create(new RelayOptions { FcmServiceAccountB64 = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(account.ToJsonString())) });
        var google = new FakeGoogle { Key = key };
        using var fcm = new FcmSender(options, NullLogger<FcmSender>.Instance, google);
        Assert.True(fcm.Configured);
        await fcm.StartAsync(CancellationToken.None);
        Assert.True(fcm.TryWake("phone-token-1"));
        Assert.True(fcm.TryWake("phone-token-2"));
        for (int i = 0; i < 100 && fcm.Counts.Sent < 2; i++) await Task.Delay(20);
        await fcm.StopAsync(CancellationToken.None);
        Assert.Equal((2L, 0L), fcm.Counts);
        Assert.Equal(1, google.TokenCalls); // the access token is reused
        var m = google.Messages.Select(x => x["message"]!).OrderBy(x => x["token"]!.GetValue<string>()).ToList();
        Assert.Equal("phone-token-1", m[0]["token"]!.GetValue<string>());
        Assert.Equal("{\"t\":\"wake\"}", m[0]["data"]!.ToJsonString());
        Assert.Equal("HIGH", m[0]["android"]!["priority"]!.GetValue<string>());
        Assert.Null(m[0]["notification"]); // no visible content through Google
    }

    [Fact]
    public async Task A_401_gets_a_new_access_token_and_a_404_token_is_never_tried_again()
    {
        using var key = System.Security.Cryptography.RSA.Create(2048);
        var account = new System.Text.Json.Nodes.JsonObject
        {
            ["project_id"] = "proj-1", ["client_email"] = "relay@test.iam",
            ["private_key"] = key.ExportPkcs8PrivateKeyPem(), ["token_uri"] = "https://oauth2.example/token",
        };
        var options = Options.Create(new RelayOptions { FcmServiceAccountB64 = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(account.ToJsonString())) });
        var google = new FakeGoogle { Key = key, Unauthorized = 1, Unregistered = "gone" };
        using var fcm = new FcmSender(options, NullLogger<FcmSender>.Instance, google);
        await fcm.StartAsync(CancellationToken.None);
        Assert.True(fcm.TryWake("ok"));
        for (int i = 0; i < 100 && fcm.Counts.Sent < 1; i++) await Task.Delay(20);
        Assert.Equal(2, google.TokenCalls); // refreshed after the 401
        Assert.True(fcm.TryWake("gone"));
        for (int i = 0; i < 100 && fcm.Counts.Failed < 1; i++) await Task.Delay(20);
        Assert.False(fcm.TryWake("gone")); // FCM said it doesn't exist
        await fcm.StopAsync(CancellationToken.None);
    }

    [Fact]
    public void Without_an_account_wake_ups_are_dropped()
    {
        using var fcm = new FcmSender(Options.Create(new RelayOptions()), NullLogger<FcmSender>.Instance);
        Assert.False(fcm.Configured);
        Assert.False(fcm.TryWake("x"));
        using var bad = new FcmSender(Options.Create(new RelayOptions { FcmServiceAccountB64 = "bm90IGpzb24=" }), NullLogger<FcmSender>.Instance);
        Assert.False(bad.Configured);
    }
}
