using System.Text.Json;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Tests.Agent;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>Relay route claims and routes (§10.1) against the Python vectors, and the set the agent registers.</summary>
public class RouteTokenTests
{
    [Fact]
    public void Claims_and_routes_match_the_vectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "vectors", "protocol-vectors.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        int n = 0;
        foreach (var v in doc.RootElement.GetProperty("routeTokens").EnumerateArray())
        {
            var ak = Convert.FromHexString(v.GetProperty("ak").GetString()!);
            var date = DateOnly.ParseExact(v.GetProperty("date").GetString()!, "yyyy-MM-dd");
            var claim = RouteToken.Claim(ak, date);
            Assert.Equal(v.GetProperty("claim").GetString(), Hex(claim));
            Assert.Equal(v.GetProperty("route").GetString(), Hex(RouteToken.Route(claim)));
            var token = RouteToken.Derive(ak, date);
            Assert.Equal(v.GetProperty("token").GetString(), token);
            Assert.Equal(RouteToken.TextLength, token.Length);
            Assert.Equal(token.ToUpperInvariant(), token);
            n++;
        }
        Assert.True(n >= 6);
    }

    [Fact]
    public void Outlen_16_is_not_the_same_hash()
    {
        // The likeliest implementer mistake: BLAKE2b's output length is part of the hash
        var ak = new byte[32];
        var input = "GameTimer route v2"u8.ToArray().Concat("2026-10-24"u8.ToArray()).ToArray();
        Assert.NotEqual(Sodium.GenericHash.Hash(input, ak, 16), RouteToken.Claim(ak, new DateOnly(2026, 10, 24)));
    }

    [Fact]
    public void Route_differs_from_the_claim_the_discovery_tag_and_by_day()
    {
        var ak = new byte[32];
        var day = new DateOnly(2026, 10, 24);
        var claim = RouteToken.Claim(ak, day);
        Assert.NotEqual(claim, RouteToken.Route(claim));
        Assert.False(RouteToken.Derive(ak, day).StartsWith(DiscoveryTag.Derive(ak, day), StringComparison.Ordinal));
        Assert.NotEqual(RouteToken.Derive(ak, day), RouteToken.Derive(ak, day.AddDays(1)));
        Assert.Throws<ArgumentException>(() => RouteToken.Claim(new byte[31], day));
        Assert.Throws<ArgumentException>(() => RouteToken.Route(new byte[15]));
    }

    [Fact]
    public async Task Host_registers_yesterday_today_and_tomorrow_per_hub()
    {
        using var f = new AgentFixture();
        var day = new DateOnly(2026, 10, 24);
        Assert.Empty(f.Host.RouteClaims(day));
        var (_, r1) = await f.PairAsync("A");
        var (_, r2) = await f.PairAsync("B");
        var expected = new[] { r1, r2 }
            .SelectMany(r => new[] { -1, 0, 1 }.Select(d => Hex(RouteToken.Claim(Ak(r), day.AddDays(d)))))
            .OrderBy(x => x).ToList();
        Assert.Equal(expected, f.Host.RouteClaims(day).Select(Hex).OrderBy(x => x).ToList());
    }

    [Fact]
    public async Task Host_caps_the_claims_by_whole_hubs_keeping_the_most_recently_used()
    {
        using var f = new AgentFixture();
        var hubs = AgentHost.MaxRouteTokens / 3 + 2;
        var results = new List<(Noise.KeyPair Key, JsonElement R)>();
        for (int i = 0; i < hubs; i++) results.Add(await f.PairAsync("H" + i));
        // The first hub paired connects again: it is now the most recently used
        await TestHub.ConnectAsync(f.Host, results[0].Key);
        var day = new DateOnly(2026, 10, 24);
        var claims = f.Host.RouteClaims(day).Select(Hex).ToHashSet();
        Assert.Equal(AgentHost.MaxRouteTokens, claims.Count);
        Assert.Contains(Hex(RouteToken.Claim(Ak(results[0].R), day)), claims);
        Assert.Contains(Hex(RouteToken.Claim(Ak(results[^1].R), day)), claims);
        // Two hubs fall out: those paired earliest and not seen since
        Assert.DoesNotContain(Hex(RouteToken.Claim(Ak(results[1].R), day)), claims);
        Assert.DoesNotContain(Hex(RouteToken.Claim(Ak(results[2].R), day)), claims);
    }

    private static byte[] Ak(JsonElement r) => Base64Url.TryDecode(r.GetProperty("ak").GetString())!;

    private static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();
}
