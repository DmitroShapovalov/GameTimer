using System.Buffers.Binary;
using System.Text;

namespace GameTimer.Protocol.Discovery;

/// <summary>Minimal DNS wire format for mDNS (RFC 1035, RFC 6762): questions in, records out.</summary>
internal static class MdnsMessage
{
    public const ushort TypeA = 1, TypePtr = 12, TypeTxt = 16, TypeAaaa = 28, TypeSrv = 33, TypeAny = 255;
    public const ushort ClassIn = 1;
    public const ushort CacheFlush = 0x8000;   // on records: "this replaces what you have" (unique records)
    public const ushort UnicastResponse = 0x8000; // on questions: QU bit

    public readonly record struct Question(string Name, ushort Type, bool WantsUnicast);

    public readonly record struct Record(string Name, ushort Type, bool Unique, uint Ttl, byte[] Data);

    /// <summary>The questions of a query, or null for a response or a malformed packet.</summary>
    public static (ushort Id, List<Question> Questions)? ParseQuery(ReadOnlySpan<byte> msg)
    {
        try
        {
            if (msg.Length < 12) return null;
            ushort id = BinaryPrimitives.ReadUInt16BigEndian(msg);
            ushort flags = BinaryPrimitives.ReadUInt16BigEndian(msg[2..]);
            if ((flags & 0x8000) != 0) return null;          // a response, not a query
            if (((flags >> 11) & 0xF) != 0) return null;     // only standard queries
            int qd = BinaryPrimitives.ReadUInt16BigEndian(msg[4..]);
            if (qd is 0 or > 64) return null;
            int offset = 12;
            var questions = new List<Question>(qd);
            for (int i = 0; i < qd; i++)
            {
                var name = ReadName(msg, ref offset);
                if (offset + 4 > msg.Length) return null;
                ushort type = BinaryPrimitives.ReadUInt16BigEndian(msg[offset..]);
                ushort cls = BinaryPrimitives.ReadUInt16BigEndian(msg[(offset + 2)..]);
                offset += 4;
                if ((cls & 0x7FFF) is ClassIn or 255)
                    questions.Add(new Question(name, type, (cls & UnicastResponse) != 0));
            }
            return (id, questions);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>Reads a (possibly compressed) name, lower-cased, without the trailing dot.</summary>
    public static string ReadName(ReadOnlySpan<byte> msg, ref int offset)
    {
        var sb = new StringBuilder();
        int pos = offset, jumps = 0;
        bool jumped = false;
        while (true)
        {
            if (pos >= msg.Length) throw new FormatException("name past the end");
            byte len = msg[pos];
            if (len == 0) { pos++; break; }
            if ((len & 0xC0) == 0xC0)
            {
                if (pos + 1 >= msg.Length || ++jumps > 16) throw new FormatException("bad pointer");
                int target = ((len & 0x3F) << 8) | msg[pos + 1];
                if (!jumped) offset = pos + 2;
                jumped = true;
                if (target >= pos) throw new FormatException("forward pointer");
                pos = target;
                continue;
            }
            if ((len & 0xC0) != 0 || pos + 1 + len > msg.Length) throw new FormatException("bad label");
            if (sb.Length > 0) sb.Append('.');
            sb.Append(Encoding.UTF8.GetString(msg.Slice(pos + 1, len)));
            if (sb.Length > 255) throw new FormatException("name too long");
            pos += 1 + len;
        }
        if (!jumped) offset = pos;
        return sb.ToString().ToLowerInvariant();
    }

    /// <summary>A response (QR + AA). Questions are echoed only for legacy unicast replies (RFC 6762 §6.7).</summary>
    public static byte[] Build(ushort id, IReadOnlyList<Question> echo, IReadOnlyList<Record> answers, IReadOnlyList<Record> additional)
    {
        var w = new List<byte>(512);
        void U16(int v) { w.Add((byte)(v >> 8)); w.Add((byte)v); }
        void U32(uint v) { U16((int)(v >> 16)); U16((int)(v & 0xFFFF)); }
        U16(id);
        U16(0x8400);
        U16(echo.Count);
        U16(answers.Count);
        U16(0);
        U16(additional.Count);
        foreach (var q in echo)
        {
            WriteName(w, q.Name);
            U16(q.Type);
            U16(ClassIn);
        }
        foreach (var r in answers.Concat(additional))
        {
            WriteName(w, r.Name);
            U16(r.Type);
            U16(ClassIn | (r.Unique ? CacheFlush : 0));
            U32(r.Ttl);
            U16(r.Data.Length);
            w.AddRange(r.Data);
        }
        return w.ToArray();
    }

    public static void WriteName(List<byte> w, string name)
    {
        foreach (var label in name.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            var bytes = Encoding.UTF8.GetBytes(label);
            if (bytes.Length > 63) throw new ArgumentException("label too long");
            w.Add((byte)bytes.Length);
            w.AddRange(bytes);
        }
        w.Add(0);
    }

    public static byte[] NameData(string name)
    {
        var w = new List<byte>();
        WriteName(w, name);
        return w.ToArray();
    }

    public static byte[] SrvData(int port, string target)
    {
        var w = new List<byte> { 0, 0, 0, 0, (byte)(port >> 8), (byte)port }; // priority 0, weight 0, port
        WriteName(w, target);
        return w.ToArray();
    }

    /// <summary>TXT strings, each at most 255 bytes (longer ones are cut by the caller).</summary>
    public static byte[] TxtData(IEnumerable<string> strings)
    {
        var w = new List<byte>();
        foreach (var s in strings)
        {
            var b = Encoding.UTF8.GetBytes(s);
            if (b.Length > 255) throw new ArgumentException("TXT string too long");
            w.Add((byte)b.Length);
            w.AddRange(b);
        }
        if (w.Count == 0) w.Add(0);
        return w.ToArray();
    }
}
