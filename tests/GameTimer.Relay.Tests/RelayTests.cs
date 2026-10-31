using System.Buffers.Binary;
using System.Net.WebSockets;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using Xunit;
using static GameTimer.Relay.Tests.RelayFixture;

namespace GameTimer.Relay.Tests;

/// <summary>The relay against docs/protocol.md §10.2–§10.4, over in-memory WebSockets.</summary>
public class RelayTests
{
    [Fact]
    public async Task Joins_a_hub_to_the_agent_and_copies_both_ways()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var hub = await f.HubAsync(Route(1));

        var open = await NextFrameAsync(agent, 0x02);
        Assert.Equal(21, open.Length);
        uint id = BinaryPrimitives.ReadUInt32BigEndian(open.AsSpan(1));
        Assert.NotEqual(0u, id);
        Assert.Equal(RouteToken.Route(Claim(1)), open[5..]); // the route the hub dialled

        await SendAsync(hub, new byte[] { 1, 2, 3 });
        Assert.Equal(Data(id, 1, 2, 3), await NextFrameAsync(agent, 0x03));

        await SendAsync(agent, Data(id, 9, 8));
        var (type, data) = await ReceiveAsync(hub);
        Assert.Equal(WebSocketMessageType.Binary, type);
        Assert.Equal(new byte[] { 9, 8 }, data);

        // A full-size Noise message passes unchanged
        var big = Enumerable.Range(0, 65_535).Select(i => (byte)i).ToArray();
        await SendAsync(hub, big);
        Assert.Equal(big, (await NextFrameAsync(agent, 0x03))[5..]);
    }

    [Fact]
    public async Task Each_hub_gets_its_own_stream()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1, 2);
        var a = await f.HubAsync(Route(1));
        uint idA = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        var b = await f.HubAsync(Route(2));
        uint idB = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        Assert.NotEqual(idA, idB);
        await SendAsync(agent, Data(idB, 2));
        await SendAsync(agent, Data(idA, 1));
        Assert.Equal(new byte[] { 1 }, (await ReceiveAsync(a)).Data);
        Assert.Equal(new byte[] { 2 }, (await ReceiveAsync(b)).Data);
    }

    [Fact]
    public async Task Close_codes_pass_between_hub_and_agent()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);

        // Agent ends a stream with an application code → the hub sees it
        var hub = await f.HubAsync(Route(1));
        uint id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await SendAsync(agent, Close(id, 4001));
        Assert.Equal(4001, await ClosedWithAsync(hub));

        // A code an endpoint may not send becomes 1000
        hub = await f.HubAsync(Route(1));
        id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await SendAsync(agent, Close(id, 1006));
        Assert.Equal(1000, await ClosedWithAsync(hub));

        // The hub closes → the agent gets close with the hub's code
        hub = await f.HubAsync(Route(1));
        id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await hub.CloseOutputAsync((WebSocketCloseStatus)4002, null, CancellationToken.None);
        Assert.Equal(Close(id, 4002), await NextFrameAsync(agent, 0x04));

        // The hub just drops → 1001
        hub = await f.HubAsync(Route(1));
        id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        hub.Abort();
        Assert.Equal(Close(id, 1001), await NextFrameAsync(agent, 0x04));

        // Data for a stream that is gone is ignored; the socket stays up
        await SendAsync(agent, Data(id, 1));
        await SendAsync(agent, new byte[] { 0x05, 7 });
        Assert.Equal(new byte[] { 0x06, 7 }, await NextFrameAsync(agent, 0x06));
    }

    [Theory]
    [InlineData(2999, 1000)]
    [InlineData(3000, 3000)]
    [InlineData(4999, 4999)]
    [InlineData(1008, 1008)]
    [InlineData(1011, 1011)]
    [InlineData(1002, 1000)]
    public async Task Agent_close_codes_are_mapped(int sent, int seen)
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var hub = await f.HubAsync(Route(1));
        uint id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await SendAsync(agent, Close(id, (ushort)sent));
        Assert.Equal(seen, await ClosedWithAsync(hub));
    }

    [Fact]
    public async Task Counters_return_to_zero_when_everything_goes_away()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1, 2);
        var a = await f.HubAsync(Route(1), "10.0.0.2");
        uint id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        var b = await f.HubAsync(Route(2), "2001:db8::7");
        await NextFrameAsync(agent, 0x02);
        // Queue data towards a hub that never reads, then drop it; then drop the agent with a stream open
        for (int i = 0; i < 5; i++) await SendAsync(agent, Data(id, new byte[1000]));
        a.Abort();
        await NextFrameAsync(agent, 0x04);
        agent.Abort();
        Assert.Equal(1001, await ClosedWithAsync(b));
        for (int i = 0; i < 300 && (f.State.AgentSockets + f.State.HubSockets > 0 || f.State.Buffered != 0); i++) await Task.Delay(10);
        Assert.Equal(0, f.State.AgentSockets);
        Assert.Equal(0, f.State.HubSockets);
        Assert.Equal(0, f.State.Buffered);
        Assert.Equal(0, f.State.Registry.Count);
        await Task.Delay(1100); // the 1 s windows expire
        f.State.Limits.Cleanup();
        for (int i = 0; i < 100 && f.State.Limits.Count() > 0; i++) { await Task.Delay(20); f.State.Limits.Cleanup(); }
        Assert.Equal(0, f.State.Limits.Count());
    }

    [Fact]
    public async Task A_closing_agent_answers_404_at_once()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        await SendAsync(agent, new byte[] { 0x42 }); // malformed: the relay starts closing (1002)
        var (type, _) = await ReceiveAsync(agent);
        Assert.Equal(WebSocketMessageType.Close, type); // not answered yet: the relay waits for the echo
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(1), "10.0.0.3"));
    }

    [Fact]
    public async Task Stopping_the_relay_closes_agents_with_1012()
    {
        var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var closed = ClosedWithAsync(agent, seconds: 10);
        await f.DisposeAsync();
        Assert.Equal(1012, await closed);
    }

    [Fact]
    public async Task Agent_going_away_closes_its_hubs_with_1001_and_frees_the_routes()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var hub = await f.HubAsync(Route(1));
        await NextFrameAsync(agent, 0x02);
        agent.Abort();
        Assert.Equal(1001, await ClosedWithAsync(hub));
        for (int i = 0; i < 100 && f.State.Registry.Count > 0; i++) await Task.Delay(10);
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(1), "10.0.0.3"));
    }

    [Fact]
    public async Task Upgrade_answers_follow_the_spec_order()
    {
        await using var f = await StartAsync(o => o.StreamsPerAgent = 1);
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        Assert.Equal(403, await f.StatusAsync("v2/hub/" + Route(1), "10.0.0.2", origin: true));
        Assert.Equal(403, await f.StatusAsync("v2/agent", "10.0.0.2", origin: true));
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(2), "10.0.0.2"));               // not registered
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(1).ToLowerInvariant(), "10.0.0.2")); // not canonical
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(1)[..25], "10.0.0.2"));
        var hub = await f.HubAsync(Route(1));
        await NextFrameAsync(agent, 0x02);
        Assert.Equal(503, await f.StatusAsync("v2/hub/" + Route(1), "10.0.0.4")); // the agent is full
        Assert.Equal(WebSocketState.Open, hub.State);
    }

    [Theory]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAAA", true)]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAAE", true)]   // E = 4: low bits zero
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAAB", false)]  // B = 1: a trailing bit set
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAA6", false)]  // 6 = 30
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAA4", true)]   // 4 = 28
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAAa", false)]  // lowercase
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAA", false)]   // 25 characters
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAA1A", false)]
    public void Canonical_routes(string route, bool ok) => Assert.Equal(ok, RelayApp.IsCanonicalRoute(route));

    [Fact]
    public void Every_derived_route_is_canonical()
    {
        for (int i = 0; i < 50; i++) Assert.True(RelayApp.IsCanonicalRoute(Base32.Encode(RouteToken.Route(Claim(i)))));
    }

    [Fact]
    public async Task Unknown_route_dials_are_limited_per_ip()
    {
        await using var f = await StartAsync(o => { o.NotFoundPerIpPerMinute = 3; o.NewSocketsPerIpPerSecond = 100; o.HubSocketsPerIp = 100; });
        for (int i = 0; i < 3; i++) Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "10.0.0.7"));
        Assert.Equal(429, await f.StatusAsync("v2/hub/" + Route(9), "10.0.0.7"));
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "10.0.0.8")); // another IP
        // IPv6: one /64 is one client
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:1:2::1"));
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:1:2::2"));
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:1:2:ffff::3"));
        Assert.Equal(429, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:1:2::4"));
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:1:3::1"));
    }

    [Fact]
    public async Task One_ipv6_48_has_a_wider_but_finite_budget()
    {
        await using var f = await StartAsync(o => { o.NotFoundPerIpPerMinute = 1; o.NewSocketsPerIpPerSecond = 100; o.HubSocketsPerIp = 100; });
        // Each /64 gets 1, the /48 around them gets 4 (IpLimits.WideFactor)
        for (int i = 1; i <= IpLimits.WideFactor; i++)
            Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), $"2001:db8:5:{i}::1"));
        Assert.Equal(429, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:5:99::1"));
        Assert.Equal(404, await f.StatusAsync("v2/hub/" + Route(9), "2001:db8:6:1::1")); // another /48
    }

    [Fact]
    public async Task Missing_client_address_header_is_refused()
    {
        await using var f = await StartAsync();
        var client = f.Server.CreateWebSocketClient();
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => client.ConnectAsync(new Uri(f.Server.BaseAddress, "v2/agent"), CancellationToken.None));
        Assert.Contains("400", e.Message);
    }

    [Fact]
    public async Task New_sockets_and_open_sockets_are_limited_per_ip()
    {
        await using var f = await StartAsync(o => { o.NewSocketsPerIpPerSecond = 2; o.AgentSocketsPerIp = 100; });
        var a1 = await f.AgentAsync("10.0.0.9");
        var a2 = await f.AgentAsync("10.0.0.9");
        Assert.Equal(429, await f.StatusAsync("v2/agent", "10.0.0.9"));
        await Task.Delay(1100);
        Assert.Equal(101, await f.StatusAsync("v2/agent", "10.0.0.9"));

        await using var g = await StartAsync(o => { o.AgentSocketsPerIp = 1; o.NewSocketsPerIpPerSecond = 100; });
        var only = await g.AgentAsync("10.0.0.9");
        Assert.Equal(429, await g.StatusAsync("v2/agent", "10.0.0.9"));
        Assert.Equal(101, await g.StatusAsync("v2/agent", "10.0.0.10"));
    }

    [Fact]
    public async Task A_route_moves_to_the_newest_registration()
    {
        await using var f = await StartAsync();
        var old = await f.AgentAsync();
        await f.RegisterAsync(old, 1, 2);
        var fresh = await f.AgentAsync("10.0.0.5");
        await SendAsync(fresh, Register(1));
        for (int i = 0; i < 200 && f.State.Registry.Find(Route(1))?.Agent.IpKey != "4:10.0.0.5"; i++) await Task.Delay(10);

        await f.HubAsync(Route(1));
        await NextFrameAsync(fresh, 0x02);
        await f.HubAsync(Route(2));
        await NextFrameAsync(old, 0x02); // route 2 stayed with the old socket
        Assert.Equal(2, f.State.Registry.Count);
    }

    [Fact]
    public async Task Register_replaces_the_whole_set_and_counts_duplicates_once()
    {
        await using var f = await StartAsync(o => o.RegisterMinInterval = TimeSpan.Zero);
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1, 1, 2);
        Assert.Equal(2, f.State.Registry.Count);
        await SendAsync(agent, Register(3));
        for (int i = 0; i < 200 && f.State.Registry.Find(Route(3)) == null; i++) await Task.Delay(10);
        Assert.Null(f.State.Registry.Find(Route(1)));
        Assert.Equal(1, f.State.Registry.Count);
        await SendAsync(agent, Register());
        for (int i = 0; i < 200 && f.State.Registry.Count > 0; i++) await Task.Delay(10);
        Assert.Equal(0, f.State.Registry.Count);
    }

    public static IEnumerable<object[]> BadFrames() => new[]
    {
        new object[] { new byte[] { 0x01, 1, 2, 3 } },                    // register: not a multiple of 16
        new object[] { new byte[] { 0x01 }.Concat(new byte[16 * 65]).ToArray() }, // 65 claims
        new object[] { new byte[] { 0x02, 0, 0, 0, 1 }.Concat(new byte[16]).ToArray() }, // open from an agent
        new object[] { new byte[] { 0x03, 0, 0, 0, 1 } },                 // empty data
        new object[] { new byte[] { 0x04, 0, 0, 0, 1, 3 } },              // short close
        new object[] { new byte[] { 0x05, 1, 2, 3, 4, 5, 6, 7, 8, 9 } },  // ping too long
        new object[] { new byte[] { 0x07 } },                             // reserved
        new object[] { new byte[] { 0x42 } },                             // unknown
        new object[] { Array.Empty<byte>() },
    };

    [Theory]
    [MemberData(nameof(BadFrames))]
    public async Task Malformed_agent_frames_close_the_socket_with_1002(byte[] frame)
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        await SendAsync(agent, frame);
        Assert.Equal(1002, await ClosedWithAsync(agent));
    }

    [Fact]
    public async Task Text_from_the_agent_is_1003_and_from_a_hub_too()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var hub = await f.HubAsync(Route(1));
        uint id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await hub.SendAsync("hi"u8.ToArray(), WebSocketMessageType.Text, true, CancellationToken.None);
        Assert.Equal(1003, await ClosedWithAsync(hub));
        Assert.Equal(Close(id, 1000), await NextFrameAsync(agent, 0x04));

        await agent.SendAsync("hi"u8.ToArray(), WebSocketMessageType.Text, true, CancellationToken.None);
        Assert.Equal(1003, await ClosedWithAsync(agent));
    }

    [Fact]
    public async Task Register_too_often_is_1008()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await SendAsync(agent, Register(1));
        await SendAsync(agent, Register(2));
        Assert.Equal(1008, await ClosedWithAsync(agent));
    }

    [Fact]
    public async Task No_register_in_time_is_1008()
    {
        await using var f = await StartAsync(o => o.RegisterWithin = TimeSpan.FromMilliseconds(300));
        var agent = await f.AgentAsync();
        Assert.Equal(1008, await ClosedWithAsync(agent));
    }

    [Fact]
    public async Task Claims_over_the_total_cap_are_1013()
    {
        await using var f = await StartAsync(o => o.MaxClaims = 2);
        var a = await f.AgentAsync();
        await f.RegisterAsync(a, 1, 2);
        var b = await f.AgentAsync("10.0.0.6");
        await SendAsync(b, Register(3));
        Assert.Equal(1013, await ClosedWithAsync(b));
        Assert.NotNull(f.State.Registry.Find(Route(1)));
    }

    [Fact]
    public async Task Idle_streams_are_closed_with_1001()
    {
        await using var f = await StartAsync(o => o.IdleStream = TimeSpan.FromMilliseconds(400));
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var hub = await f.HubAsync(Route(1));
        uint id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        Assert.Equal(1001, await ClosedWithAsync(hub));
        Assert.Equal(Close(id, 1001), await NextFrameAsync(agent, 0x04));
    }

    [Fact]
    public async Task Agent_keepalive_pings_and_a_silent_agent_is_dropped()
    {
        await using var f = await StartAsync(o =>
        {
            o.AgentPingAfter = TimeSpan.FromMilliseconds(200);
            o.AgentDeadAfter = TimeSpan.FromMilliseconds(900);
        });
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var ping = await NextFrameAsync(agent, 0x05, seconds: 3);
        Assert.Equal(new byte[] { 0x05 }, ping);
        // Now stay silent: the relay drops the socket and frees the route
        for (int i = 0; i < 300 && f.State.Registry.Count > 0; i++) await Task.Delay(10);
        Assert.Equal(0, f.State.Registry.Count);
    }

    [Fact]
    public async Task Too_much_unread_data_for_a_hub_closes_the_stream_with_1013()
    {
        await using var f = await StartAsync(o => o.MaxBufferedBytes = 10);
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var hub = await f.HubAsync(Route(1));
        uint id = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await SendAsync(agent, Data(id, new byte[11]));
        Assert.Equal(Close(id, 1013), await NextFrameAsync(agent, 0x04));
        Assert.Equal(1013, await ClosedWithAsync(hub));
        Assert.Equal(0, f.State.Buffered);
    }

    [Fact]
    public async Task Out_of_buffer_only_the_stream_of_that_data_ends()
    {
        await using var f = await StartAsync(o => o.MaxBufferedBytes = 20_000);
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1, 2);
        var a = await f.HubAsync(Route(1));
        uint idA = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        var b = await f.HubAsync(Route(2), "10.0.0.3");
        uint idB = Frames.StreamOf(await NextFrameAsync(agent, 0x02));
        await SendAsync(agent, Data(idA, new byte[60_000])); // arrives in pieces, more than the budget
        Assert.Equal(1013, await ClosedWithAsync(a));
        Assert.Equal(Close(idA, 1013), await NextFrameAsync(agent, 0x04));
        // The agent socket and the other stream carry on
        await SendAsync(agent, Data(idB, 5));
        Assert.Equal(new byte[] { 5 }, (await ReceiveAsync(b)).Data);
        Assert.Equal(0, f.State.Buffered);
    }

    private static byte[] WakeFrame(string token) => new byte[] { 0x07 }.Concat(System.Text.Encoding.ASCII.GetBytes(token)).ToArray();

    [Fact]
    public async Task Wake_ups_go_to_the_push_sender_rate_limited()
    {
        await using var f = await StartAsync(o => { o.WakesPerAgentPerMinute = 3; o.WakeSameTokenEvery = TimeSpan.FromMilliseconds(500); });
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        var id = Guid.NewGuid().ToString("N");
        await SendAsync(agent, WakeFrame("tokA-" + id));
        await SendAsync(agent, WakeFrame("tokA-" + id)); // the same token too soon: dropped
        await SendAsync(agent, WakeFrame("tokB-" + id));
        await Task.Delay(600);
        await SendAsync(agent, WakeFrame("tokA-" + id)); // later: passes
        await SendAsync(agent, WakeFrame("tokC-" + id)); // 4th in a minute: over the socket's limit
        await SendAsync(agent, new byte[] { 0x05 });     // a ping round trip: everything above was handled
        await NextFrameAsync(agent, 0x06);
        var mine = Push.Tokens.Where(t => t.EndsWith(id, StringComparison.Ordinal)).ToList();
        Assert.Equal(new[] { "tokA-" + id, "tokB-" + id, "tokA-" + id }, mine);
    }

    [Fact]
    public async Task Wake_ups_only_from_a_socket_that_registered_claims()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        var id = Guid.NewGuid().ToString("N");
        await SendAsync(agent, Register());              // registered, but nothing
        await SendAsync(agent, WakeFrame("tokZ-" + id));
        await SendAsync(agent, new byte[] { 0x05 });
        await NextFrameAsync(agent, 0x06);
        Assert.DoesNotContain(Push.Tokens, t => t.EndsWith(id, StringComparison.Ordinal));
    }

    [Fact]
    public async Task One_token_is_woken_once_per_interval_across_sockets()
    {
        await using var f = await StartAsync();
        var a = await f.AgentAsync("10.0.1.1");
        await f.RegisterAsync(a, 1);
        var b = await f.AgentAsync("10.0.2.1");
        await f.RegisterAsync(b, 2);
        var id = Guid.NewGuid().ToString("N");
        await SendAsync(a, WakeFrame("tokS-" + id));
        await SendAsync(b, WakeFrame("tokS-" + id)); // another socket, same token, too soon
        foreach (var s in new[] { a, b }) { await SendAsync(s, new byte[] { 0x05 }); await NextFrameAsync(s, 0x06); }
        Assert.Single(Push.Tokens, t => t.EndsWith(id, StringComparison.Ordinal));
    }

    [Theory]
    [InlineData(new byte[] { 0x07 })]                 // empty
    [InlineData(new byte[] { 0x07, 0x41, 0x20 })]     // a space
    [InlineData(new byte[] { 0x07, 0x41, 0xC3, 0xA9 })] // not ASCII
    public async Task Malformed_wake_frames_are_1002(byte[] frame)
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        await SendAsync(agent, frame);
        Assert.Equal(1002, await ClosedWithAsync(agent));
    }

    [Fact]
    public async Task A_too_long_push_token_is_1002()
    {
        await using var f = await StartAsync();
        var agent = await f.AgentAsync();
        await f.RegisterAsync(agent, 1);
        await SendAsync(agent, WakeFrame(new string('a', Frames.MaxPushToken + 1)));
        Assert.Equal(1002, await ClosedWithAsync(agent));
    }

    [Fact]
    public async Task Health_check()
    {
        await using var f = await StartAsync();
        var r = await f.Server.CreateClient().GetAsync("/healthz");
        Assert.Equal("ok", await r.Content.ReadAsStringAsync());
    }
}
