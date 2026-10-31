using System.Text.Json;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Tests.Agent;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>Relay route tokens (§10.1) against the Python vectors, and the set the agent registers.</summary>
public class RouteTokenTests
{
    [Fact]
    public void Tokens_match_the_vectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "vectors", "protocol-vectors.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        int n = 0;
        foreach (var v in doc.RootElement.GetProperty("routeTokens").EnumerateArray())
        {
            var ak = Convert.FromHexString(v.GetProperty("ak").GetString()!);
            var date = DateOnly.ParseExact(v.GetProperty("date").GetString()!, "yyyy-MM-dd");
            var bytes = RouteToken.DeriveBytes(ak, date);
            Assert.Equal(v.GetProperty("bytes").GetString(), Convert.ToHexString(bytes).ToLowerInvariant());
            var token = RouteToken.Derive(ak, date);
            Assert.Equal(v.GetProperty("token").GetString(), token);
            Assert.Equal(RouteToken.Size, bytes.Length);
            Assert.Equal(RouteToken.TextLength, token.Length);
            n++;
        }
        Assert.True(n >= 4);
    }

    [Fact]
    public void Route_token_differs_from_the_discovery_tag_and_by_day()
    {
        var ak = new byte[32];
        var day = new DateOnly(2026, 10, 24);
        Assert.False(RouteToken.Derive(ak, day).StartsWith(DiscoveryTag.Derive(ak, day), StringComparison.Ordinal));
        Assert.NotEqual(RouteToken.Derive(ak, day), RouteToken.Derive(ak, day.AddDays(1)));
        Assert.Throws<ArgumentException>(() => RouteToken.Derive(new byte[31], day));
    }

    [Fact]
    public async Task Host_registers_yesterday_today_and_tomorrow_per_hub_newest_first()
    {
        using var f = new AgentFixture();
        var day = new DateOnly(2026, 10, 24);
        Assert.Empty(f.Host.RouteTokens(day));
        var (_, r1) = await f.PairAsync("A");
        var (_, r2) = await f.PairAsync("B");
        byte[] Ak(JsonElement r) => Base64Url.TryDecode(r.GetProperty("ak").GetString())!;
        var expected = new[] { r2, r1 }
            .SelectMany(r => new[] { -1, 0, 1 }.Select(d => RouteToken.DeriveBytes(Ak(r), day.AddDays(d))))
            .ToList();
        Assert.Equal(expected, f.Host.RouteTokens(day));
    }

    [Fact]
    public async Task Host_caps_the_tokens_by_whole_hubs()
    {
        using var f = new AgentFixture();
        var hubs = AgentHost.MaxRouteTokens / 3 + 2;
        var results = new List<JsonElement>();
        for (int i = 0; i < hubs; i++) results.Add((await f.PairAsync("H" + i)).Item2);
        var day = new DateOnly(2026, 10, 24);
        var tokens = f.Host.RouteTokens(day);
        Assert.Equal(AgentHost.MaxRouteTokens / 3 * 3, tokens.Count);
        // The newest hub is in, the oldest ones are not
        var newest = Base64Url.TryDecode(results[^1].GetProperty("ak").GetString())!;
        var oldest = Base64Url.TryDecode(results[0].GetProperty("ak").GetString())!;
        Assert.Equal(RouteToken.DeriveBytes(newest, day.AddDays(-1)), tokens[0]);
        Assert.DoesNotContain(tokens, t => t.SequenceEqual(RouteToken.DeriveBytes(oldest, day)));
    }
}
