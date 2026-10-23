using System.Text.Json.Nodes;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Noise;
using Xunit;

namespace GameTimer.Protocol.Tests.Agent;

/// <summary>/v2/pair (protocol §8.2).</summary>
public class PairingTests
{
    private static JsonObject Hello(string name = "Phone") =>
        new() { ["t"] = "pair.hello", ["protocol"] = 2, ["minor"] = 0, ["name"] = name, ["app"] = "test" };

    [Fact]
    public async Task First_hub_pairs_as_admin_with_its_own_ak()
    {
        using var f = new AgentFixture();
        var (key, result) = await f.PairAsync("Dmytro's phone");

        Assert.True(result.GetProperty("ok").GetBoolean());
        Assert.Equal("admin", result.GetProperty("role").GetString());
        Assert.Equal(Ids.AgentId(f.Host.AgentPublicKey), result.GetProperty("agent").GetProperty("id").GetString());
        Assert.Equal(32, Base64Url.TryDecode(result.GetProperty("ak").GetString())!.Length);

        var peer = Assert.Single(f.Host.Peers);
        Assert.Equal(Ids.HubId(key.PublicKey), peer.Id);
        Assert.Equal("Dmytro's phone", peer.Name);
        Assert.Equal(PeerRole.Admin, peer.Role);
        Assert.Equal(new[] { "Dmytro's phone" }, f.Ui.Asked);
    }

    [Fact]
    public async Task Second_hub_pairs_as_parent()
    {
        using var f = new AgentFixture();
        await f.PairAsync("A");
        var (_, result) = await f.PairAsync("B");
        Assert.Equal("parent", result.GetProperty("role").GetString());
    }

    [Fact]
    public async Task Wrong_code_is_denied_and_nothing_is_stored()
    {
        using var f = new AgentFixture();
        var offer = f.Host.StartPairing("PC");
        var (hub, ok) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        Assert.True(ok);
        var right = PairingCode.Derive(hub.HandshakeHash!);
        f.Ui.Answer = (_, _) => Task.FromResult<string?>(right == "000000" ? "000001" : "000000");
        await hub.SendJsonAsync(Hello());
        var bye = await hub.ReceiveJsonAsync();
        Assert.Equal("bye", bye.GetProperty("t").GetString());
        Assert.Equal("denied", bye.GetProperty("code").GetString());
        Assert.Empty(f.Host.Peers);
    }

    [Fact]
    public async Task Owner_cancel_is_denied()
    {
        using var f = new AgentFixture();
        var offer = f.Host.StartPairing("PC");
        var (hub, _) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        await hub.SendJsonAsync(Hello()); // default UI answer: cancel
        Assert.Equal("denied", (await hub.ReceiveJsonAsync()).GetProperty("code").GetString());
        Assert.Empty(f.Host.Peers);
    }

    [Fact]
    public async Task Wrong_secret_closes_silently_at_message_1()
    {
        using var f = new AgentFixture();
        f.Host.StartPairing("PC");
        var (hub, gotMsg2) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, new byte[32], firstType: "pair");
        Assert.False(gotMsg2);
        await hub.Run;
    }

    [Fact]
    public async Task No_open_pairing_window_closes_silently()
    {
        using var f = new AgentFixture();
        var offer = f.Host.StartPairing("PC");
        f.Host.CancelPairing();
        var (_, gotMsg2) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        Assert.False(gotMsg2);
    }

    [Fact]
    public async Task Two_phones_with_one_qr_cancel_the_pairing()
    {
        using var f = new AgentFixture();
        var offer = f.Host.StartPairing("PC");
        var asked = new TaskCompletionSource();
        f.Ui.Answer = async (_, cancel) =>
        {
            asked.TrySetResult();
            await Task.Delay(Timeout.Infinite, cancel); // the owner is still typing
            return null;
        };

        // The child photographed the QR and pairs first; the owner is asked for the code...
        var (child, _) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        await child.SendJsonAsync(Hello("Child"));
        await asked.Task.WaitAsync(TimeSpan.FromSeconds(10));

        // ...then the parent's phone scans the same QR
        var (parent, ok) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, ip: "192.168.1.21", firstType: "pair");
        Assert.True(ok); // it knows s, so the handshake works
        await parent.SendJsonAsync(Hello("Parent"));

        Assert.Equal("code_used", (await parent.ReceiveJsonAsync()).GetProperty("code").GetString());
        Assert.Equal("denied", (await child.ReceiveJsonAsync()).GetProperty("code").GetString());
        Assert.Equal(new[] { PairingAbortReason.TwoPhones }, f.Ui.Aborts);
        Assert.Empty(f.Host.Peers);
    }

    [Fact]
    public async Task A_replayed_pairing_message_1_does_not_use_up_the_secret()
    {
        using var f = new AgentFixture();
        var offer = f.Host.StartPairing("PC");
        var (sniffed, _) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        sniffed.End.CloseAsync().AsTask().Wait();

        // Replay of that msg 1: the agent answers msg 2, but nobody can continue
        var (_, gotMsg2) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, ip: "192.168.1.66", replayMsg1: sniffed.Msg1, firstType: "pair");
        Assert.True(gotMsg2);

        // The real phone still pairs
        var code = (string?)null;
        var (phone, ok) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        Assert.True(ok);
        code = PairingCode.Derive(phone.HandshakeHash!);
        f.Ui.Answer = (_, _) => Task.FromResult<string?>(code);
        await phone.SendJsonAsync(Hello());
        Assert.Equal("pair.result", (await phone.ReceiveJsonAsync()).GetProperty("t").GetString());
    }

    [Fact]
    public async Task Pairing_adds_a_peer_and_tells_existing_hubs()
    {
        using var f = new AgentFixture();
        var (firstKey, _) = await f.PairAsync("First");
        var (first, _) = await TestHub.ConnectAsync(f.Host, firstKey);

        var (_, _) = await f.PairAsync("Second");
        var ev = await first.ReceiveJsonAsync();
        Assert.Equal("ev.peers", ev.GetProperty("t").GetString());
        Assert.Equal("added", ev.GetProperty("change").GetString());
        Assert.Equal(2, ev.GetProperty("peers").GetArrayLength());
    }

    [Fact]
    public async Task Bad_pair_hello_closes_silently()
    {
        using var f = new AgentFixture();
        var offer = f.Host.StartPairing("PC");
        var (hub, _) = await TestHub.HandshakeAsync(f.Host, KeyPair.Generate(), true, offer.Secret, firstType: "pair");
        await hub.SendJsonAsync(new JsonObject { ["t"] = "pair.hello", ["protocol"] = 2, ["name"] = new string('x', 65) });
        Assert.Null(await hub.TryReceiveJsonAsync());
        Assert.Empty(f.Ui.Asked);
    }
}
