using System.Text.Json;
using GameTimer.Protocol.Noise;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>
/// Runs the published Noise test vectors (cacophony, snow) for the patterns we use: both sides are
/// driven with the vector's fixed keys and must produce exactly the vector's ciphertexts and hash.
/// </summary>
public class NoiseVectorTests
{
    public static IEnumerable<object[]> Vectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "vectors", "noise-vectors.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        foreach (var v in doc.RootElement.GetProperty("vectors").EnumerateArray())
            yield return new object[] { v.GetProperty("source").GetString()!, v.GetProperty("protocol_name").GetString()!, v.GetRawText() };
    }

    [Fact]
    public void Vector_file_covers_every_pattern_we_use()
    {
        // Guards against a vector silently dropped from the file (the theory would just run fewer cases)
        var names = Vectors().Select(v => (string)v[1]).ToList();
        Assert.True(names.Count >= 4);
        Assert.Contains("Noise_IK_25519_ChaChaPoly_BLAKE2b", names);
        Assert.Contains("Noise_IKpsk1_25519_ChaChaPoly_BLAKE2b", names);
    }

    [Theory]
    [MemberData(nameof(Vectors))]
    public void Vector_matches(string source, string protocolName, string json)
    {
        _ = source;
        using var doc = JsonDocument.Parse(json);
        var v = doc.RootElement;
        var pattern = protocolName switch
        {
            "Noise_IK_25519_ChaChaPoly_BLAKE2b" => NoisePattern.IK,
            "Noise_IKpsk1_25519_ChaChaPoly_BLAKE2b" => NoisePattern.IKpsk1,
            "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2b" => NoisePattern.IKpsk2,
            _ => throw new InvalidOperationException(protocolName),
        };

        var initiator = new HandshakeState(pattern, initiator: true, Hex(v, "init_prologue"),
            KeyPair.FromPrivateKey(Hex(v, "init_static")), remoteStatic: Hex(v, "init_remote_static"),
            psk: Psk(v, "init_psks"), ephemeralForTesting: KeyPair.FromPrivateKey(Hex(v, "init_ephemeral")));
        var responder = new HandshakeState(pattern, initiator: false, Hex(v, "resp_prologue"),
            KeyPair.FromPrivateKey(Hex(v, "resp_static")), remoteStatic: null,
            psk: Psk(v, "resp_psks"), ephemeralForTesting: KeyPair.FromPrivateKey(Hex(v, "resp_ephemeral")));

        var messages = v.GetProperty("messages").EnumerateArray().ToArray();
        CipherState? iSend = null, iRecv = null, rSend = null, rRecv = null;

        for (int i = 0; i < messages.Length; i++)
        {
            var payload = Hex(messages[i], "payload");
            var expected = Hex(messages[i], "ciphertext");
            bool fromInitiator = i % 2 == 0;

            if (!initiator.IsHandshakeComplete)
            {
                var (writer, reader) = fromInitiator ? (initiator, responder) : (responder, initiator);
                Assert.Equal(Convert.ToHexString(expected), Convert.ToHexString(writer.WriteMessage(payload)));
                Assert.Equal(Convert.ToHexString(payload), Convert.ToHexString(reader.ReadMessage(expected)));

                if (initiator.IsHandshakeComplete)
                {
                    Assert.True(responder.IsHandshakeComplete);
                    Assert.Equal(Convert.ToHexString(initiator.GetHandshakeHash()), Convert.ToHexString(responder.GetHandshakeHash()));
                    if (v.TryGetProperty("handshake_hash", out _)) // cacophony has it, snow doesn't
                        Assert.Equal(Convert.ToHexString(Hex(v, "handshake_hash")), Convert.ToHexString(initiator.GetHandshakeHash()));
                    // The responder learned the initiator's static key from message 1
                    Assert.Equal(Convert.ToHexString(KeyPair.FromPrivateKey(Hex(v, "init_static")).PublicKey),
                        Convert.ToHexString(responder.RemoteStaticPublicKey!));
                    (iSend, iRecv) = initiator.Split();
                    (rSend, rRecv) = responder.Split();
                }
                continue;
            }

            var (send, recv) = fromInitiator ? (iSend!, rRecv!) : (rSend!, iRecv!);
            Assert.Equal(Convert.ToHexString(expected), Convert.ToHexString(send.Encrypt(payload)));
            Assert.Equal(Convert.ToHexString(payload), Convert.ToHexString(recv.Decrypt(expected)));
        }
    }

    private static byte[] Hex(JsonElement e, string name) =>
        e.TryGetProperty(name, out var p) ? Convert.FromHexString(p.GetString()!) : Array.Empty<byte>();

    private static byte[]? Psk(JsonElement e, string name) =>
        e.TryGetProperty(name, out var p) && p.GetArrayLength() > 0 ? Convert.FromHexString(p[0].GetString()!) : null;
}
