using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Identity;
using Xunit;

namespace GameTimer.Protocol.Tests.Agent;

/// <summary>Roles, revocation and the last-admin rule (protocol §8.1).</summary>
public class PeerTests
{
    private static JsonObject Target(string id, string? role = null)
    {
        var o = new JsonObject { ["peer"] = id };
        if (role != null) o["role"] = role;
        return o;
    }

    private static string Code(JsonElement r) => r.GetProperty("code").GetString()!;

    [Fact]
    public async Task Parent_cannot_revoke_or_promote_others_but_can_revoke_itself()
    {
        using var f = new AgentFixture();
        var (admin, _) = await f.PairAsync("Admin");
        var (parent, _) = await f.PairAsync("Parent");
        var (p, _) = await TestHub.ConnectAsync(f.Host, parent);
        var (a, _) = await TestHub.ConnectAsync(f.Host, admin);

        Assert.Equal("forbidden", Code(await p.RequestAsync("peers.revoke", Target(Ids.HubId(admin.PublicKey)), withRid: true)));
        Assert.Equal("forbidden", Code(await p.RequestAsync("peers.setRole", Target(p.Id, "admin"), withRid: true)));

        var r = await p.RequestAsync("peers.revoke", Target(p.Id), withRid: true);
        Assert.Equal("result", r.GetProperty("t").GetString());
        Assert.Equal("revoked", Code(await p.ReceiveJsonAsync())); // result first, then bye
        Assert.Single(f.Host.Peers);

        var ev = await a.ReceiveJsonAsync();
        Assert.Equal("ev.peers", ev.GetProperty("t").GetString());
        Assert.Equal("revoked", ev.GetProperty("change").GetString());
        Assert.True(ev.GetProperty("peers")[0].GetProperty("current").GetBoolean());
    }

    [Fact]
    public async Task The_last_admin_is_protected_from_hubs()
    {
        using var f = new AgentFixture();
        var (admin, _) = await f.PairAsync("Admin");
        var (parent, _) = await f.PairAsync("Parent");
        var (a, _) = await TestHub.ConnectAsync(f.Host, admin);

        Assert.Equal("last_admin", Code(await a.RequestAsync("peers.revoke", Target(a.Id), withRid: true)));
        Assert.Equal("last_admin", Code(await a.RequestAsync("peers.setRole", Target(a.Id, "parent"), withRid: true)));

        // Promote the other parent; then the first admin may step down
        var parentId = Ids.HubId(parent.PublicKey);
        Assert.Equal("result", (await a.RequestAsync("peers.setRole", Target(parentId, "admin"), withRid: true)).GetProperty("t").GetString());
        Assert.Equal("result", (await a.RequestAsync("peers.setRole", Target(a.Id, "parent"), withRid: true)).GetProperty("t").GetString());
        Assert.Equal("forbidden", Code(await a.RequestAsync("peers.revoke", Target(parentId), withRid: true)));
    }

    [Fact]
    public async Task Owner_can_revoke_the_last_admin_and_the_next_pairing_becomes_admin()
    {
        using var f = new AgentFixture();
        var (admin, _) = await f.PairAsync("Lost phone");
        var (a, _) = await TestHub.ConnectAsync(f.Host, admin);

        Assert.True(f.Host.RevokeByOwner(a.Id));
        Assert.Equal("revoked", Code(await a.ReceiveJsonAsync()));
        Assert.Empty(f.Host.Peers);
        Assert.False(f.Host.RevokeByOwner(a.Id));

        // The revoked key can't connect any more
        var (_, gotMsg2) = await TestHub.HandshakeAsync(f.Host, admin, pairing: false);
        Assert.False(gotMsg2);

        var (_, result) = await f.PairAsync("New phone");
        Assert.Equal("admin", result.GetProperty("role").GetString());
    }

    [Fact]
    public async Task Revoking_a_hub_drops_its_cached_replies()
    {
        using var f = new AgentFixture();
        var (admin, _) = await f.PairAsync("Admin");
        var (parent, _) = await f.PairAsync("Parent");
        var (p, _) = await TestHub.ConnectAsync(f.Host, parent);
        await p.RequestAsync("timer.extend", new JsonObject { ["minutes"] = 5 }, withRid: true);
        Assert.True(f.Store.State!.Rids.ContainsKey(p.Id));

        f.Host.RevokeByOwner(p.Id);
        Assert.False(f.Store.State!.Rids.ContainsKey(p.Id));
        _ = admin;
    }

    [Fact]
    public async Task Peers_list_marks_the_current_hub()
    {
        using var f = new AgentFixture();
        var (a, _) = await f.PairAsync("A");
        await f.PairAsync("B");
        var (hub, _) = await TestHub.ConnectAsync(f.Host, a);
        var peers = (await hub.RequestAsync("peers.list")).GetProperty("peers").EnumerateArray().ToList();
        Assert.Equal(2, peers.Count);
        Assert.Equal("A", peers.Single(x => x.GetProperty("current").GetBoolean()).GetProperty("name").GetString());
        Assert.Equal("parent", peers.Single(x => !x.GetProperty("current").GetBoolean()).GetProperty("role").GetString());
    }
}
