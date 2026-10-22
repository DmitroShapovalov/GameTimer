using System.Text;
using GameTimer.Protocol.Noise;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>Failure paths the protocol relies on (docs/protocol.md §4).</summary>
public class NoiseBehaviourTests
{
    private static readonly byte[] Prologue = Encoding.ASCII.GetBytes("GameTimer/2");

    private static (HandshakeState Hub, HandshakeState Agent, KeyPair HubKey, KeyPair AgentKey) Pair(
        NoisePattern pattern = NoisePattern.IK, byte[]? hubPsk = null, byte[]? agentPsk = null)
    {
        var hubKey = KeyPair.Generate();
        var agentKey = KeyPair.Generate();
        var hub = new HandshakeState(pattern, true, Prologue, hubKey, agentKey.PublicKey, hubPsk);
        var agent = new HandshakeState(pattern, false, Prologue, agentKey, null, agentPsk);
        return (hub, agent, hubKey, agentKey);
    }

    [Fact]
    public void Full_session_round_trip_and_remote_key_learned()
    {
        var (hub, agent, hubKey, _) = Pair();
        agent.ReadMessage(hub.WriteMessage("{\"t\":\"connect\"}"u8));
        Assert.Equal(hubKey.PublicKey, agent.RemoteStaticPublicKey);
        hub.ReadMessage(agent.WriteMessage(ReadOnlySpan<byte>.Empty));
        Assert.Equal(hub.GetHandshakeHash(), agent.GetHandshakeHash());

        var (hubSend, hubRecv) = hub.Split();
        var (agentSend, agentRecv) = agent.Split();
        Assert.Equal("ready", Encoding.UTF8.GetString(agentRecv.Decrypt(hubSend.Encrypt("ready"u8))));
        Assert.Equal("hello", Encoding.UTF8.GetString(hubRecv.Decrypt(agentSend.Encrypt("hello"u8))));
    }

    [Fact]
    public void Pairing_with_wrong_secret_fails_at_message_1()
    {
        var secret = new byte[32]; secret[0] = 1;
        var wrong = new byte[32]; wrong[0] = 2;
        var (hub, agent, _, _) = Pair(NoisePattern.IKpsk1, hubPsk: wrong, agentPsk: secret);
        var msg1 = hub.WriteMessage("{\"t\":\"pair\"}"u8);
        Assert.Throws<NoiseException>(() => agent.ReadMessage(msg1));
    }

    [Fact]
    public void Pairing_with_right_secret_succeeds()
    {
        var secret = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        var (hub, agent, _, _) = Pair(NoisePattern.IKpsk1, secret, secret);
        agent.ReadMessage(hub.WriteMessage("{\"t\":\"pair\"}"u8));
        hub.ReadMessage(agent.WriteMessage(ReadOnlySpan<byte>.Empty));
        Assert.Equal(hub.GetHandshakeHash(), agent.GetHandshakeHash());
    }

    [Fact]
    public void Hub_refuses_a_different_agent_key()
    {
        // A fake "PC" on the network has its own key; the hub pinned the real one
        var realAgent = KeyPair.Generate();
        var fakeAgent = KeyPair.Generate();
        var hub = new HandshakeState(NoisePattern.IK, true, Prologue, KeyPair.Generate(), realAgent.PublicKey);
        var fake = new HandshakeState(NoisePattern.IK, false, Prologue, fakeAgent);
        Assert.Throws<NoiseException>(() => fake.ReadMessage(hub.WriteMessage(ReadOnlySpan<byte>.Empty)));
    }

    [Fact]
    public void Tampered_message_1_is_rejected()
    {
        var (hub, agent, _, _) = Pair();
        var msg1 = hub.WriteMessage("{\"t\":\"connect\"}"u8);
        msg1[^1] ^= 0x01;
        Assert.Throws<NoiseException>(() => agent.ReadMessage(msg1));
    }

    [Fact]
    public void All_zero_DH_result_aborts()
    {
        // A low-order public key (all zeros) makes X25519 output zero
        var hub = new HandshakeState(NoisePattern.IK, true, Prologue, KeyPair.Generate(), new byte[32]);
        Assert.Throws<NoiseException>(() => hub.WriteMessage(ReadOnlySpan<byte>.Empty));
    }

    [Fact]
    public void Replayed_transport_message_is_rejected()
    {
        var (hub, agent, _, _) = Pair();
        agent.ReadMessage(hub.WriteMessage(ReadOnlySpan<byte>.Empty));
        hub.ReadMessage(agent.WriteMessage(ReadOnlySpan<byte>.Empty));
        var (hubSend, _) = hub.Split();
        var (_, agentRecv) = agent.Split();
        var c = hubSend.Encrypt("timer.extend"u8);
        agentRecv.Decrypt(c);
        Assert.Throws<NoiseException>(() => agentRecv.Decrypt(c));
    }

    [Fact]
    public void Wrong_turn_is_refused()
    {
        var (_, agent, _, _) = Pair();
        Assert.Throws<InvalidOperationException>(() => agent.WriteMessage(ReadOnlySpan<byte>.Empty));
    }

    [Fact]
    public void Oversized_message_is_refused()
    {
        var (hub, _, _, _) = Pair();
        Assert.Throws<NoiseException>(() => hub.WriteMessage(new byte[HandshakeState.MaxMessageLength]));
    }

    [Fact]
    public void Failed_state_cannot_be_reused()
    {
        var (hub, agent, _, _) = Pair();
        var msg1 = hub.WriteMessage(ReadOnlySpan<byte>.Empty);
        var bad = (byte[])msg1.Clone(); bad[40] ^= 0xFF;
        Assert.Throws<NoiseException>(() => agent.ReadMessage(bad));
        Assert.Throws<InvalidOperationException>(() => agent.ReadMessage(msg1));
    }

    [Fact]
    public void Tampered_message_2_is_rejected()
    {
        var (hub, agent, _, _) = Pair();
        agent.ReadMessage(hub.WriteMessage(ReadOnlySpan<byte>.Empty));
        var msg2 = agent.WriteMessage("{\"t\":\"hello\"}"u8);
        msg2[0] ^= 0x01; // the agent's ephemeral key
        Assert.Throws<NoiseException>(() => hub.ReadMessage(msg2));
    }

    [Fact]
    public void Failed_transport_decrypt_does_not_advance_the_counter()
    {
        var (hub, agent, _, _) = Pair();
        agent.ReadMessage(hub.WriteMessage(ReadOnlySpan<byte>.Empty));
        hub.ReadMessage(agent.WriteMessage(ReadOnlySpan<byte>.Empty));
        var (hubSend, _) = hub.Split();
        var (_, agentRecv) = agent.Split();
        var good = hubSend.Encrypt("ready"u8);
        var bad = (byte[])good.Clone(); bad[0] ^= 0x01;
        Assert.Throws<NoiseException>(() => agentRecv.Decrypt(bad));
        Assert.Equal("ready", Encoding.UTF8.GetString(agentRecv.Decrypt(good)));
    }

    [Fact]
    public void Oversized_message_is_refused_on_read_and_in_transport()
    {
        var (hub, agent, _, _) = Pair();
        Assert.Throws<NoiseException>(() => agent.ReadMessage(new byte[HandshakeState.MaxMessageLength + 1]));

        var (hub2, agent2, _, _) = Pair();
        agent2.ReadMessage(hub2.WriteMessage(ReadOnlySpan<byte>.Empty));
        hub2.ReadMessage(agent2.WriteMessage(ReadOnlySpan<byte>.Empty));
        var (send, recv) = hub2.Split();
        Assert.Throws<NoiseException>(() => send.Encrypt(new byte[HandshakeState.MaxMessageLength - 15]));
        Assert.Throws<NoiseException>(() => recv.Decrypt(new byte[HandshakeState.MaxMessageLength + 1]));
        Assert.Equal(HandshakeState.MaxMessageLength, send.Encrypt(new byte[HandshakeState.MaxMessageLength - 16]).Length);
        _ = hub;
    }

    [Fact]
    public void Split_is_allowed_once()
    {
        // A second Split would hand out the same keys with nonces back at 0 (keystream reuse)
        var (hub, agent, _, _) = Pair();
        agent.ReadMessage(hub.WriteMessage(ReadOnlySpan<byte>.Empty));
        hub.ReadMessage(agent.WriteMessage(ReadOnlySpan<byte>.Empty));
        hub.Split();
        Assert.Throws<InvalidOperationException>(() => hub.Split());
    }

    [Fact]
    public void Disposed_key_pair_cannot_be_used()
    {
        var key = KeyPair.Generate();
        key.Dispose();
        Assert.Throws<ObjectDisposedException>(() => key.PrivateKey);
        Assert.Throws<ObjectDisposedException>(() => new HandshakeState(NoisePattern.IK, false, Prologue, key));
    }

    [Fact]
    public void Key_getters_return_copies()
    {
        var key = KeyPair.Generate();
        var pub = key.PublicKey;
        pub[0] ^= 0xFF;
        Assert.NotEqual(pub, key.PublicKey);
    }
}
