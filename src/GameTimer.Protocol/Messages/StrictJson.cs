using System.Buffers;
using System.Text.Json;

namespace GameTimer.Protocol.Messages;

/// <summary>
/// Parses a message plaintext as I-JSON (RFC 7493, protocol §4.5): valid UTF-8, a JSON object at the
/// top, no duplicate member names, integers within ±(2^53 − 1), bounded depth. Anything else is a
/// protocol error. System.Text.Json alone accepts duplicate names, hence the extra pass.
/// </summary>
public static class StrictJson
{
    public const int MaxPlaintext = 65519; // 65535 Noise limit − 16-byte tag
    private const int MaxDepth = 16;
    private const long MaxSafeInteger = 9007199254740991; // 2^53 − 1

    public static JsonDocument Parse(byte[] utf8)
    {
        if (utf8.Length > MaxPlaintext) throw new ProtocolViolation("message too large");
        try
        {
            Validate(utf8);
            var doc = JsonDocument.Parse(utf8, new JsonDocumentOptions { MaxDepth = MaxDepth });
            if (doc.RootElement.ValueKind != JsonValueKind.Object)
            {
                doc.Dispose();
                throw new ProtocolViolation("message is not a JSON object");
            }
            return doc;
        }
        catch (JsonException ex)
        {
            throw new ProtocolViolation("invalid JSON: " + ex.Message);
        }
    }

    private static void Validate(byte[] utf8)
    {
        var reader = new Utf8JsonReader(utf8, new JsonReaderOptions { MaxDepth = MaxDepth });
        var names = new Stack<HashSet<string>?>(); // null entry = inside an array
        while (reader.Read())
        {
            switch (reader.TokenType)
            {
                case JsonTokenType.StartObject:
                    names.Push(new HashSet<string>(StringComparer.Ordinal));
                    break;
                case JsonTokenType.StartArray:
                    names.Push(null);
                    break;
                case JsonTokenType.EndObject:
                case JsonTokenType.EndArray:
                    names.Pop();
                    break;
                case JsonTokenType.PropertyName:
                    if (!names.Peek()!.Add(reader.GetString()!))
                        throw new ProtocolViolation("duplicate member name");
                    break;
                case JsonTokenType.Number:
                    CheckNumber(ref reader);
                    break;
            }
        }
    }

    private static void CheckNumber(ref Utf8JsonReader reader)
    {
        var raw = reader.HasValueSequence ? reader.ValueSequence.ToArray() : reader.ValueSpan.ToArray();
        bool isInteger = Array.IndexOf(raw, (byte)'.') < 0 && Array.IndexOf(raw, (byte)'e') < 0 && Array.IndexOf(raw, (byte)'E') < 0;
        if (isInteger)
        {
            if (!reader.TryGetInt64(out var v) || v > MaxSafeInteger || v < -MaxSafeInteger)
                throw new ProtocolViolation("integer out of the I-JSON range");
        }
        else if (!reader.TryGetDouble(out var d) || double.IsInfinity(d))
        {
            throw new ProtocolViolation("number out of range");
        }
    }
}

/// <summary>A protocol violation after the handshake: the agent sends <c>bye protocol_error</c> and closes.</summary>
public sealed class ProtocolViolation : Exception
{
    public ProtocolViolation(string message) : base(message) { }
}
