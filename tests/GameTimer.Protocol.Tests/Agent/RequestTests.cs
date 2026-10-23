using System.Text;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using Xunit;

namespace GameTimer.Protocol.Tests.Agent;

/// <summary>Envelope, routing, idempotency and event ordering (protocol §5, §7).</summary>
public class RequestTests
{
    private static JsonObject Extend(int minutes = 5) => new() { ["minutes"] = minutes };

    [Fact]
    public async Task Result_to_the_requester_and_event_to_the_others_only()
    {
        using var f = new AgentFixture();
        var (a, _) = await f.PairAsync("A");
        var (b, _) = await f.PairAsync("B");
        var (hubA, _) = await TestHub.ConnectAsync(f.Host, a);
        var (hubB, _) = await TestHub.ConnectAsync(f.Host, b);

        var r = await hubA.RequestAsync("timer.extend", Extend(), withRid: true);
        Assert.Equal("result", r.GetProperty("t").GetString());
        Assert.Equal(1, r.GetProperty("state").GetProperty("extends").GetInt32());

        var ev = await hubB.ReceiveJsonAsync();
        Assert.Equal("ev.state", ev.GetProperty("t").GetString());

        // The requester does not get the event: the next thing it receives is the pong
        await hubA.SendJsonAsync(new JsonObject { ["t"] = "ping" });
        Assert.Equal("pong", (await hubA.ReceiveJsonAsync()).GetProperty("t").GetString());
    }

    [Fact]
    public async Task Work_started_by_a_request_keeps_publishing_to_everyone()
    {
        // Regression: an implicit (AsyncLocal) request scope leaked into the timer the request started,
        // and every later tick was swallowed
        using var f = new AgentFixture();
        var (a, _) = await f.PairAsync("A");
        var (b, _) = await f.PairAsync("B");
        var (hubA, _) = await TestHub.ConnectAsync(f.Host, a);
        var (hubB, _) = await TestHub.ConnectAsync(f.Host, b);

        await hubA.RequestAsync("timer.start", new JsonObject { ["mode"] = "interval", ["hours"] = 1, ["minutes"] = 0 }, withRid: true);

        foreach (var hub in new[] { hubA, hubB })
        {
            Assert.Equal(1, (await hub.ReceiveJsonAsync()).GetProperty("n").GetInt32());
            Assert.Equal(2, (await hub.ReceiveJsonAsync()).GetProperty("n").GetInt32());
        }
        // the late event attributed to the request goes to the others only
        Assert.True((await hubB.ReceiveJsonAsync()).GetProperty("late").GetBoolean());
        await hubA.SendJsonAsync(new JsonObject { ["t"] = "ping" });
        Assert.Equal("pong", (await hubA.ReceiveJsonAsync()).GetProperty("t").GetString());
    }

    [Fact]
    public async Task Hello_is_always_the_first_message()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        // An event published while the agent is still building hello (state comes from the UI thread)
        f.Backend.BeforeState = () => f.Host.Publish("ev.alert", new JsonObject { ["minutes"] = 5 });
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key); // asserts hello first
        Assert.Equal("ev.alert", (await hub.ReceiveJsonAsync()).GetProperty("t").GetString());
    }

    [Fact]
    public async Task Events_from_the_pc_reach_everyone()
    {
        using var f = new AgentFixture();
        var (a, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, a);
        f.Host.Publish("ev.alert", new JsonObject { ["minutes"] = 5 });
        var ev = await hub.ReceiveJsonAsync();
        Assert.Equal("ev.alert", ev.GetProperty("t").GetString());
        Assert.Equal(5, ev.GetProperty("minutes").GetInt32());
    }

    [Fact]
    public async Task Same_rid_runs_once_and_replays_the_stored_reply_even_in_a_new_session()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        var rid = TestHub.NewRid();

        var first = await hub.RequestAsync("timer.extend", Extend(), rid);
        var again = await hub.RequestAsync("timer.extend", Extend(), rid);
        Assert.Equal(1, f.Backend.Extends);
        Assert.Equal(first.GetProperty("state").GetRawText(), again.GetProperty("state").GetRawText());
        Assert.NotEqual(first.GetProperty("re").GetInt64(), again.GetProperty("re").GetInt64());

        // Reconnect (e.g. Wi-Fi dropped before the reply arrived) and resend with the same rid
        var (hub2, _) = await TestHub.ConnectAsync(f.Host, key);
        var resent = await hub2.RequestAsync("timer.extend", Extend(), rid);
        Assert.Equal("result", resent.GetProperty("t").GetString());
        Assert.Equal(1, f.Backend.Extends);
    }

    [Fact]
    public async Task Same_rid_with_a_different_body_is_a_bad_request()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        var rid = TestHub.NewRid();
        await hub.RequestAsync("timer.extend", Extend(5), rid);
        var r = await hub.RequestAsync("timer.extend", Extend(10), rid);
        Assert.Equal("bad_request", r.GetProperty("code").GetString());
        Assert.Equal(1, f.Backend.Extends);
    }

    [Fact]
    public async Task Errors_are_cached_too()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        var rid = TestHub.NewRid();
        Assert.Equal("not_running", (await hub.RequestAsync("timer.stop", null, rid)).GetProperty("code").GetString());
        var calls = f.Backend.Calls;
        Assert.Equal("not_running", (await hub.RequestAsync("timer.stop", null, rid)).GetProperty("code").GetString());
        Assert.Equal(calls, f.Backend.Calls);
    }

    [Fact]
    public async Task Rid_cache_survives_a_restart_and_expires_after_10_minutes()
    {
        var time = new Microsoft.Extensions.Time.Testing.FakeTimeProvider();
        var store = new MemoryAgentStore();
        KeyPair_ key;
        var rid = TestHub.NewRid();
        using (var f = new AgentFixture(time, store))
        {
            var (k, _) = await f.PairAsync();
            key = new KeyPair_(k);
            var (hub, _) = await TestHub.ConnectAsync(f.Host, k);
            await hub.RequestAsync("timer.extend", Extend(), rid);
        }
        using (var g = new AgentFixture(time, store)) // the PC restarted
        {
            var (hub, _) = await TestHub.ConnectAsync(g.Host, key.Value);
            await hub.RequestAsync("timer.extend", Extend(), rid);
            Assert.Equal(0, g.Backend.Extends); // not executed again

            time.Advance(TimeSpan.FromMinutes(11));
            var (hub2, _) = await TestHub.ConnectAsync(g.Host, key.Value);
            await hub2.RequestAsync("timer.extend", Extend(), rid);
            Assert.Equal(1, g.Backend.Extends); // the entry expired
        }
    }

    private sealed record KeyPair_(GameTimer.Protocol.Noise.KeyPair Value);

    [Theory]
    [InlineData(null)]
    [InlineData("short")]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAA")] // 18 bytes, not 16
    public async Task State_changing_request_needs_a_valid_rid(string? rid)
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        var fields = Extend();
        if (rid != null) fields["rid"] = rid;
        var r = await hub.RequestAsync("timer.extend", fields);
        Assert.Equal("bad_request", r.GetProperty("code").GetString());
        Assert.Equal(0, f.Backend.Calls);
    }

    [Fact]
    public async Task Unknown_command_and_unsupported_capability()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        Assert.Equal("unknown_command", (await hub.RequestAsync("timer.explode")).GetProperty("code").GetString());
        Assert.Equal("unsupported", (await hub.RequestAsync("schedule.get")).GetProperty("code").GetString());
        Assert.Equal("result", (await hub.RequestAsync("settings.get")).GetProperty("t").GetString());
    }

    [Fact]
    public async Task Request_id_must_increase()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        await hub.RequestAsync("state.get");
        await hub.SendJsonAsync(new JsonObject { ["t"] = "state.get", ["id"] = 1 });
        var bye = await hub.ReceiveJsonAsync();
        Assert.Equal("protocol_error", bye.GetProperty("code").GetString());
    }

    [Theory]
    [InlineData("{\"t\":\"state.get\",\"id\":1,\"id\":2}")]            // duplicate key
    [InlineData("{\"t\":\"state.get\",\"id\":9007199254740992}")]       // beyond 2^53-1
    [InlineData("[\"state.get\"]")]                                     // not an object
    [InlineData("{\"t\":\"state.get\"")]                               // broken JSON
    [InlineData("{\"id\":1}")]                                          // no t
    [InlineData("{\"t\":\"state.get\"}")]                               // request without id
    public async Task Malformed_messages_end_the_session(string json)
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        await hub.SendRawAsync(Encoding.UTF8.GetBytes(json));
        var bye = await hub.ReceiveJsonAsync();
        Assert.Equal("bye", bye.GetProperty("t").GetString());
        Assert.Equal("protocol_error", bye.GetProperty("code").GetString());
        Assert.Null(await hub.TryReceiveJsonAsync());
    }

    [Fact]
    public async Task Text_message_ends_the_session()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        await hub.End.SendTextAsync();
        Assert.Equal("protocol_error", (await hub.ReceiveJsonAsync()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Tampered_transport_message_ends_the_session()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var (hub, _) = await TestHub.ConnectAsync(f.Host, key);
        await hub.End.SendAsync(new byte[40], default);
        Assert.Equal("protocol_error", (await hub.ReceiveJsonAsync()).GetProperty("code").GetString());
    }
}
