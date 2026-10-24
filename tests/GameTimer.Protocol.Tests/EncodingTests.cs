using System.Text.Json;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Messages;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>Ids, pairing codes and encodings against tests/vectors/protocol-vectors.json (independent Python).</summary>
public class EncodingTests
{
    private static JsonElement Vectors(string name)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "vectors", "protocol-vectors.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        return doc.RootElement.GetProperty(name).Clone();
    }

    [Fact]
    public void Ids_match_the_vectors()
    {
        foreach (var v in Vectors("ids").EnumerateArray())
        {
            var key = Convert.FromHexString(v.GetProperty("publicKey").GetString()!);
            Assert.Equal(v.GetProperty("agentId").GetString(), Ids.AgentId(key));
            Assert.Equal(v.GetProperty("hubId").GetString(), Ids.HubId(key));
            Assert.Equal(16, Ids.AgentId(key).Length);
        }
    }

    [Fact]
    public void Pairing_codes_match_the_vectors()
    {
        foreach (var v in Vectors("pairingCodes").EnumerateArray())
            Assert.Equal(v.GetProperty("code").GetString(), PairingCode.Derive(Convert.FromHexString(v.GetProperty("handshakeHash").GetString()!)));
    }

    [Fact]
    public void Base32_and_base64url_match_the_vectors()
    {
        foreach (var v in Vectors("base32").EnumerateArray())
            Assert.Equal(v.GetProperty("text").GetString(), Base32.Encode(Convert.FromHexString(v.GetProperty("hex").GetString()!)));
        foreach (var v in Vectors("base64url").EnumerateArray())
        {
            var bytes = Convert.FromHexString(v.GetProperty("hex").GetString()!);
            Assert.Equal(v.GetProperty("text").GetString(), Base64Url.Encode(bytes));
            Assert.Equal(bytes, Base64Url.TryDecode(v.GetProperty("text").GetString()));
        }
    }

    [Theory]
    [InlineData("Zm9vYg==")] // padding
    [InlineData("Zm9vYg+/")] // standard alphabet
    [InlineData("Zm9vYh")]   // non-canonical trailing bits
    [InlineData("Z")]
    public void Base64url_decoding_is_strict(string s) => Assert.Null(Base64Url.TryDecode(s));

    [Theory]
    [InlineData("{\"a\":{\"b\":1,\"b\":2}}")]
    [InlineData("{\"a\":[{\"x\":1,\"x\":1}]}")]
    [InlineData("{\"n\":-9007199254740992}")]
    [InlineData("{\"n\":1e400}")]
    [InlineData("\"text\"")]
    public void Strict_json_rejects(string json) =>
        Assert.Throws<ProtocolViolation>(() => StrictJson.Parse(System.Text.Encoding.UTF8.GetBytes(json)).Dispose());

    [Theory]
    [InlineData("{\"a\":{\"b\":1},\"b\":{\"b\":2}}")] // same name in different objects is fine
    [InlineData("{\"n\":9007199254740991,\"f\":1.5,\"arr\":[{\"x\":1},{\"x\":2}]}")]
    public void Strict_json_accepts(string json) =>
        StrictJson.Parse(System.Text.Encoding.UTF8.GetBytes(json)).Dispose();
}
