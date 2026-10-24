using System.Net;
using System.Net.Sockets;
using GameTimer.Protocol.Encodings;

namespace GameTimer.Protocol.Discovery;

/// <summary>What is announced (protocol §9): random instance and host labels, the port, the PC's LAN addresses, TXT.</summary>
public sealed record MdnsService(string InstanceLabel, string HostLabel, int Port, IReadOnlyList<IPAddress> Addresses, IReadOnlyList<string> Txt)
{
    public const string ServiceType = "_gametimer._tcp.local";

    /// <summary>Tags per TXT string: <c>tags=</c> + 17 × 13 characters + 16 commas = 242 ≤ 255 bytes.</summary>
    public const int TagsPerString = 17;

    public string InstanceName => $"{InstanceLabel}.{ServiceType}".ToLowerInvariant();
    public string HostName => $"{HostLabel}.local".ToLowerInvariant();

    /// <summary>A random label <c>gt-&lt;8 base32&gt;</c>: never the Windows computer name.</summary>
    public static string RandomLabel() =>
        "gt-" + Base32.Encode(System.Security.Cryptography.RandomNumberGenerator.GetBytes(5)).ToLowerInvariant();

    /// <summary>
    /// TXT for protocol v2: <c>v=2</c>, then the tags in groups of <see cref="TagsPerString"/>:
    /// <c>tags=…</c>, <c>tags2=…</c>, <c>tags3=…</c> (a DNS-SD key may appear only once).
    /// </summary>
    public static IReadOnlyList<string> TxtFor(IEnumerable<string> tags)
    {
        var txt = new List<string> { "v=2" };
        var chunks = tags.Chunk(TagsPerString).ToList();
        if (chunks.Count == 0) txt.Add("tags=");
        for (int i = 0; i < chunks.Count; i++)
            txt.Add((i == 0 ? "tags=" : $"tags{i + 1}=") + string.Join(",", chunks[i]));
        return txt;
    }
}

/// <summary>
/// Answers mDNS / DNS-SD queries for one <c>_gametimer._tcp</c> service (RFC 6762, RFC 6763). Pure logic,
/// testable without a network; <see cref="MdnsAdvertiser"/> puts it on the wire.
/// </summary>
public static class MdnsResponder
{
    internal const uint SharedTtl = 4500; // PTR / TXT (RFC 6762 §10)
    internal const uint HostTtl = 120;    // SRV / A
    internal const uint LegacyTtl = 10;   // RFC 6762 §6.7
    internal const string ServicesEnumeration = "_services._dns-sd._udp.local";

    /// <summary>The reply to a query, or null when nothing in it is about this service.</summary>
    public static byte[]? Respond(MdnsService s, ReadOnlySpan<byte> query, bool legacyUnicast) =>
        Answer(s, query, legacyUnicast)?.Packet;

    /// <summary>Like <see cref="Respond"/>, plus whether every question asked for a unicast reply (QU).</summary>
    internal static (byte[] Packet, bool AllUnicast)? Answer(MdnsService s, ReadOnlySpan<byte> query, bool legacyUnicast)
    {
        var parsed = MdnsMessage.ParseQuery(query);
        if (parsed == null) return null;
        var (id, questions) = parsed.Value;
        var answers = new List<MdnsMessage.Record>();
        var additional = new List<MdnsMessage.Record>();
        var asked = new List<MdnsMessage.Question>();
        foreach (var q in questions)
        {
            int before = answers.Count;
            bool any = q.Type == MdnsMessage.TypeAny;
            if (q.Name == MdnsService.ServiceType && (any || q.Type == MdnsMessage.TypePtr))
            {
                Add(answers, Ptr(s, SharedTtl));
                Add(additional, Srv(s, HostTtl));
                Add(additional, Txt(s, SharedTtl));
                foreach (var a in Addresses(s, HostTtl)) Add(additional, a);
            }
            else if (q.Name == s.InstanceName)
            {
                if (any || q.Type == MdnsMessage.TypeSrv) Add(answers, Srv(s, HostTtl));
                if (any || q.Type == MdnsMessage.TypeTxt) Add(answers, Txt(s, SharedTtl));
                if (any || q.Type == MdnsMessage.TypeSrv)
                    foreach (var a in Addresses(s, HostTtl)) Add(additional, a);
            }
            else if (q.Name == s.HostName && (any || q.Type == MdnsMessage.TypeA))
            {
                foreach (var a in Addresses(s, HostTtl)) Add(answers, a);
            }
            else if (q.Name == ServicesEnumeration && (any || q.Type == MdnsMessage.TypePtr))
            {
                Add(answers, new MdnsMessage.Record(ServicesEnumeration, MdnsMessage.TypePtr, false, SharedTtl,
                    MdnsMessage.NameData(MdnsService.ServiceType)));
            }
            if (answers.Count > before) asked.Add(q);
        }
        if (answers.Count == 0) return null;
        additional.RemoveAll(r => answers.Any(a => Same(a, r)));
        bool allUnicast = asked.All(q => q.WantsUnicast);
        // Legacy unicast (a plain resolver, not on port 5353): echo the id and questions, short TTLs,
        // no cache-flush bit (RFC 6762 §6.7). Otherwise id 0 and no questions (§18.1, §6).
        var packet = legacyUnicast
            ? MdnsMessage.Build(id, asked, answers.Select(Legacy).ToList(), additional.Select(Legacy).ToList())
            : MdnsMessage.Build(0, Array.Empty<MdnsMessage.Question>(), answers, additional);
        return (packet, allUnicast);
    }

    /// <summary>An unsolicited announcement of every record; with <paramref name="goodbye"/> every TTL is 0 (RFC 6762 §10.1).</summary>
    public static byte[] Announcement(MdnsService s, bool goodbye = false)
    {
        uint shared = goodbye ? 0 : SharedTtl, host = goodbye ? 0 : HostTtl;
        var records = new List<MdnsMessage.Record> { Ptr(s, shared), Srv(s, host), Txt(s, shared) };
        records.AddRange(Addresses(s, host));
        return MdnsMessage.Build(0, Array.Empty<MdnsMessage.Question>(), records, Array.Empty<MdnsMessage.Record>());
    }

    private static void Add(List<MdnsMessage.Record> list, MdnsMessage.Record r)
    {
        if (!list.Any(x => Same(x, r))) list.Add(r);
    }

    private static bool Same(MdnsMessage.Record a, MdnsMessage.Record b) =>
        a.Name == b.Name && a.Type == b.Type && a.Data.AsSpan().SequenceEqual(b.Data);

    private static MdnsMessage.Record Legacy(MdnsMessage.Record r) => r with { Ttl = Math.Min(r.Ttl, LegacyTtl), Unique = false };

    private static MdnsMessage.Record Ptr(MdnsService s, uint ttl) =>
        new(MdnsService.ServiceType, MdnsMessage.TypePtr, false, ttl, MdnsMessage.NameData(s.InstanceName));

    private static MdnsMessage.Record Srv(MdnsService s, uint ttl) =>
        new(s.InstanceName, MdnsMessage.TypeSrv, true, ttl, MdnsMessage.SrvData(s.Port, s.HostName));

    private static MdnsMessage.Record Txt(MdnsService s, uint ttl) =>
        new(s.InstanceName, MdnsMessage.TypeTxt, true, ttl, MdnsMessage.TxtData(s.Txt));

    private static IEnumerable<MdnsMessage.Record> Addresses(MdnsService s, uint ttl) =>
        s.Addresses.Where(a => a.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => new MdnsMessage.Record(s.HostName, MdnsMessage.TypeA, true, ttl, a.GetAddressBytes()));
}
