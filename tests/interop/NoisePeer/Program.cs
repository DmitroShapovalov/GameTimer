using GameTimer.Protocol.Noise;

// A scriptable C# Noise peer for the Kotlin <-> C# interop test (shared/src/jvmTest/.../NoiseInteropTests.kt).
// One command per stdin line, one reply per stdout line. All binary values are lowercase hex, "-" = none/empty.
//
//   key                              -> ok <static public key>       (one static key per process)
//   new <init|resp> <IK|IKpsk1> <remote static|-> <psk|->
//   write <payload>                  -> ok <handshake message>
//   read <message>                   -> ok <payload>
//   hash | rs                        -> ok <handshake hash | remote static key>
//   split                            -> ok
//   enc <plaintext> | dec <ciphertext> -> ok <result>
//
// Any failure replies "fail <exception type>"; the handshake state is then dropped, as a real peer would.

using var staticKey = KeyPair.Generate();
var prologue = "GameTimer/2"u8.ToArray();
HandshakeState? hs = null;
CipherState? send = null, recv = null;

string? line;
while ((line = Console.ReadLine()) != null)
{
    var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
    if (parts.Length == 0) continue;
    try
    {
        string reply = parts[0] switch
        {
            "key" => Ok(staticKey.PublicKey),
            "new" => New(parts),
            "write" => Ok(hs!.WriteMessage(Hex(parts[1]))),
            "read" => Ok(hs!.ReadMessage(Hex(parts[1]))),
            "hash" => Ok(hs!.GetHandshakeHash()),
            "rs" => Ok(hs!.RemoteStaticPublicKey!),
            "split" => Split(),
            "enc" => Ok(send!.Encrypt(Hex(parts[1]))),
            "dec" => Ok(recv!.Decrypt(Hex(parts[1]))),
            "quit" => "bye",
            _ => throw new ArgumentException(parts[0]),
        };
        Console.WriteLine(reply);
        if (reply == "bye") break;
    }
    catch (Exception ex)
    {
        if (parts[0] is "write" or "read") hs = null;
        Console.WriteLine("fail " + ex.GetType().Name);
    }
    Console.Out.Flush();
}
return 0;

string New(string[] p)
{
    var pattern = p[2] switch { "IK" => NoisePattern.IK, "IKpsk1" => NoisePattern.IKpsk1, _ => throw new ArgumentException(p[2]) };
    hs = new HandshakeState(pattern, p[1] == "init", prologue, staticKey, Opt(p[3]), Opt(p[4]));
    send = recv = null;
    return "ok";
}

string Split()
{
    (send, recv) = hs!.Split();
    return "ok";
}

static string Ok(byte[] b) => "ok " + (b.Length == 0 ? "-" : Convert.ToHexString(b).ToLowerInvariant());
static byte[] Hex(string s) => s == "-" ? Array.Empty<byte>() : Convert.FromHexString(s);
static byte[]? Opt(string s) => s == "-" ? null : Convert.FromHexString(s);
