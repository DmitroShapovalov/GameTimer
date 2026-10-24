using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using GameTimer.Protocol.Agent;
using GameTimer.Protocol.Discovery;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Tests.Agent;
using Xunit;

namespace GameTimer.Protocol.Tests;

/// <summary>Discovery (§9): daily tags against the Python vectors, and the mDNS answers parsed back.</summary>
public class DiscoveryTests
{
    private static readonly MdnsService Service = new(
        "pc-abcdefgh", "pc-ijklmnop", 5123,
        new[] { IPAddress.Parse("192.168.1.10") },
        MdnsService.TxtFor(new[] { "LUUJIIXZF3BDE", "SFFYZQOCUF7IU" }));

    [Fact]
    public void Tags_match_the_vectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "vectors", "protocol-vectors.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        int n = 0;
        foreach (var v in doc.RootElement.GetProperty("discoveryTags").EnumerateArray())
        {
            var tag = DiscoveryTag.Derive(Convert.FromHexString(v.GetProperty("ak").GetString()!),
                DateOnly.ParseExact(v.GetProperty("date").GetString()!, "yyyy-MM-dd"));
            Assert.Equal(v.GetProperty("tag").GetString(), tag);
            Assert.Equal(13, tag.Length);
            n++;
        }
        Assert.True(n >= 4);
    }

    [Fact]
    public async Task Host_tags_are_derived_from_each_hubs_ak()
    {
        using var f = new AgentFixture();
        Assert.Empty(f.Host.DiscoveryTags(new DateOnly(2026, 10, 24)));
        var (_, r1) = await f.PairAsync("A");
        var (_, r2) = await f.PairAsync("B");
        var day = new DateOnly(2026, 10, 24);
        var expected = new[] { r1, r2 }.Select(r => DiscoveryTag.Derive(Base64Url.TryDecode(r.GetProperty("ak").GetString())!, day));
        Assert.Equal(expected, f.Host.DiscoveryTags(day));
        Assert.NotEqual(f.Host.DiscoveryTags(day), f.Host.DiscoveryTags(day.AddDays(1)));
    }

    [Fact]
    public void Known_networks_are_kept_most_recent_last_capped_and_persisted()
    {
        using var f = new AgentFixture();
        Assert.False(f.Host.IsKnownNetwork("A"));
        Assert.True(f.Host.RememberNetwork("A"));
        Assert.False(f.Host.RememberNetwork("A")); // already known
        Assert.True(f.Host.IsKnownNetwork("A"));
        for (int i = 0; i < AgentHost.MaxKnownNetworks; i++) f.Host.RememberNetwork("N" + i);
        Assert.False(f.Host.IsKnownNetwork("A")); // the oldest is dropped
        f.Host.RememberNetwork("N0"); // used again: now the most recent
        f.Host.RememberNetwork("X");
        Assert.True(f.Host.IsKnownNetwork("N0"));
        Assert.False(f.Host.IsKnownNetwork("N1"));
        Assert.Equal(AgentHost.MaxKnownNetworks, f.Store.State!.KnownNetworks.Count);
        Assert.Equal("X", f.Store.State.KnownNetworks[^1]);
        Assert.False(f.Host.RememberNetwork(""));
    }

    [Fact]
    public async Task Session_start_is_reported_with_the_remote_address()
    {
        using var f = new AgentFixture();
        var (key, _) = await f.PairAsync();
        var started = new TaskCompletionSource<string>();
        f.Host.SessionStarted += ip => started.TrySetResult(ip);
        await TestHub.ConnectAsync(f.Host, key, ip: "192.168.1.33");
        Assert.Equal("192.168.1.33", await started.Task.WaitAsync(TimeSpan.FromSeconds(10)));
    }

    [Fact]
    public void Txt_splits_many_tags_into_strings_that_fit()
    {
        var tags = Enumerable.Range(0, 40).Select(i => $"TAG{i:D10}").ToList();
        var txt = MdnsService.TxtFor(tags);
        Assert.Equal("v=2", txt[0]);
        Assert.StartsWith("tags=", txt[1]);
        Assert.StartsWith("tags2=", txt[2]);
        Assert.StartsWith("tags3=", txt[3]);
        Assert.All(txt, s => Assert.True(Encoding.UTF8.GetByteCount(s) <= 255));
        Assert.Equal(tags, txt.Skip(1).SelectMany(s => s[(s.IndexOf('=') + 1)..].Split(',')));
        Assert.Equal(new[] { "v=2", "tags=" }, MdnsService.TxtFor(Array.Empty<string>()));
    }

    [Fact]
    public void Random_labels_are_random_and_not_the_machine_name()
    {
        var a = MdnsService.RandomLabel();
        Assert.Matches("^pc-[a-z2-7]{8}$", a);
        Assert.NotEqual(a, MdnsService.RandomLabel());
    }

    [Fact]
    public void Ptr_query_gets_ptr_with_srv_txt_and_address()
    {
        var reply = Parse(MdnsResponder.Respond(Service, Query(1234, ("_sysmon._tcp.local", 12, false)), legacyUnicast: false)!);
        Assert.Equal(0, reply.Id);
        Assert.Empty(reply.Questions);
        var ptr = Assert.Single(reply.Answers);
        Assert.Equal(("_sysmon._tcp.local", (ushort)12), (ptr.Name, ptr.Type));
        Assert.Equal("pc-abcdefgh._sysmon._tcp.local", ptr.Target);
        Assert.False(ptr.CacheFlush);
        var srv = reply.Additional.Single(r => r.Type == 33);
        Assert.Equal(5123, srv.Port);
        Assert.Equal("pc-ijklmnop.local", srv.Target);
        Assert.True(srv.CacheFlush);
        var txt = reply.Additional.Single(r => r.Type == 16);
        Assert.Equal(new[] { "v=2", "tags=LUUJIIXZF3BDE,SFFYZQOCUF7IU" }, txt.Strings);
        var a = reply.Additional.Single(r => r.Type == 1);
        Assert.Equal("pc-ijklmnop.local", a.Name);
        Assert.Equal("192.168.1.10", a.Address);
        Assert.All(reply.Answers.Concat(reply.Additional), r => Assert.True(r.Ttl > 0));
    }

    [Fact]
    public void Srv_txt_and_a_queries_are_answered_names_case_insensitively()
    {
        var reply = Parse(MdnsResponder.Respond(Service, Query(1,
            ("PC-ABCDEFGH._sysmon._tcp.local", 33, false),
            ("pc-abcdefgh._sysmon._tcp.local", 16, false),
            ("pc-ijklmnop.local", 1, false)), false)!);
        Assert.Equal(new ushort[] { 33, 16, 1 }, reply.Answers.Select(r => r.Type));
        Assert.Empty(reply.Additional); // the A record is already an answer
    }

    [Theory]
    [InlineData("_other._tcp.local", 12)]
    [InlineData("_sysmon._tcp.local", 1)]
    [InlineData("pc-zzzzzzzz._sysmon._tcp.local", 33)]
    [InlineData("pc-ijklmnop.local", 28)]
    public void Unrelated_queries_get_no_reply(string name, ushort type) =>
        Assert.Null(MdnsResponder.Respond(Service, Query(1, (name, type, false)), false));

    [Fact]
    public void Responses_and_garbage_are_ignored()
    {
        var response = MdnsResponder.Announcement(Service);
        Assert.Null(MdnsResponder.Respond(Service, response, false));
        Assert.Null(MdnsResponder.Respond(Service, new byte[5], false));
        var q = Query(1, ("_sysmon._tcp.local", 12, false));
        Assert.Null(MdnsResponder.Respond(Service, q.AsSpan(0, q.Length - 3), false));
        // A name that points at itself (forward / self pointers are refused)
        var loop = new byte[] { 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 12, 0, 12, 0, 1 };
        Assert.Null(MdnsResponder.Respond(Service, loop, false));
    }

    [Fact]
    public void Invalid_utf8_labels_never_throw_even_in_legacy_replies()
    {
        // PTR for our type + a question whose 63-byte label is not UTF-8 (U+FFFD would make it 189 bytes)
        var w = new List<byte> { 0, 9, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0 };
        w.AddRange(Name("_sysmon._tcp.local")); w.AddRange(new byte[] { 0, 12, 0, 1 });
        w.Add(63); w.AddRange(Enumerable.Repeat((byte)0xFF, 63)); w.Add(0); w.AddRange(new byte[] { 0, 12, 0, 1 });
        var packet = w.ToArray();
        Assert.Null(MdnsResponder.Respond(Service, packet, legacyUnicast: true)); // the whole query is malformed
        Assert.Null(MdnsResponder.Respond(Service, packet, legacyUnicast: false));
    }

    [Fact]
    public void Pointer_chains_longer_than_16_are_refused()
    {
        // Question k (k = 1..n) is "a" + a pointer to question k-1; question 0 is "_sysmon._tcp.local".
        // The last name then needs n jumps to resolve.
        byte[] Chain(int n)
        {
            var w = new List<byte> { 0, 0, 0, 0, 0, (byte)(n + 1), 0, 0, 0, 0, 0, 0 };
            var starts = new List<int> { w.Count };
            w.AddRange(Name("_sysmon._tcp.local")); w.AddRange(new byte[] { 0, 12, 0, 1 });
            for (int k = 1; k <= n; k++)
            {
                starts.Add(w.Count);
                w.Add(1); w.Add((byte)'a');
                w.Add((byte)(0xC0 | (starts[k - 1] >> 8))); w.Add((byte)starts[k - 1]);
                w.AddRange(new byte[] { 0, 12, 0, 1 });
            }
            return w.ToArray();
        }
        Assert.NotNull(MdnsResponder.Respond(Service, Chain(16), false));
        Assert.Null(MdnsResponder.Respond(Service, Chain(17), false));
    }

    [Fact]
    public void Compressed_question_names_are_understood()
    {
        // Q1: _sysmon._tcp.local PTR; Q2: pc-abcdefgh + pointer to Q1's name, SRV
        var w = new List<byte> { 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0 };
        w.AddRange(Name("_sysmon._tcp.local")); w.AddRange(new byte[] { 0, 12, 0, 1 });
        w.Add(11); w.AddRange(Encoding.ASCII.GetBytes("pc-abcdefgh")); w.AddRange(new byte[] { 0xC0, 12 });
        w.AddRange(new byte[] { 0, 33, 0, 1 });
        var reply = Parse(MdnsResponder.Respond(Service, w.ToArray(), false)!);
        Assert.Equal(new ushort[] { 12, 33 }, reply.Answers.Select(r => r.Type));
    }

    [Fact]
    public void Legacy_unicast_echoes_id_and_question_with_short_ttls()
    {
        var reply = Parse(MdnsResponder.Respond(Service, Query(4321, ("_sysmon._tcp.local", 12, false)), legacyUnicast: true)!);
        Assert.Equal(4321, reply.Id);
        Assert.Equal("_sysmon._tcp.local", Assert.Single(reply.Questions));
        Assert.All(reply.Answers.Concat(reply.Additional), r => { Assert.True(r.Ttl <= 10); Assert.False(r.CacheFlush); });
    }

    [Fact]
    public void Qu_questions_are_reported_as_unicast()
    {
        Assert.True(MdnsResponder.Answer(Service, Query(0, ("_sysmon._tcp.local", 12, true)), false)!.Value.AllUnicast);
        Assert.False(MdnsResponder.Answer(Service, Query(0, ("_sysmon._tcp.local", 12, false)), false)!.Value.AllUnicast);
    }

    [Fact]
    public void Services_enumeration_lists_the_type()
    {
        var reply = Parse(MdnsResponder.Respond(Service, Query(0, ("_services._dns-sd._udp.local", 12, false)), false)!);
        Assert.Equal("_sysmon._tcp.local", Assert.Single(reply.Answers).Target);
    }

    [Fact]
    public void Announcement_and_goodbye()
    {
        var hello = Parse(MdnsResponder.Announcement(Service));
        Assert.Equal(new ushort[] { 12, 33, 16, 1 }, hello.Answers.Select(r => r.Type));
        Assert.All(hello.Answers, r => Assert.True(r.Ttl > 0));
        var bye = Parse(MdnsResponder.Announcement(Service, goodbye: true));
        Assert.Equal(4, bye.Answers.Count);
        Assert.All(bye.Answers, r => Assert.Equal(0u, r.Ttl));
    }

    [Fact]
    public void Service_comparison_sees_new_tags_and_addresses()
    {
        Assert.True(MdnsAdvertiser.SameService(Service, Service with { }));
        Assert.False(MdnsAdvertiser.SameService(Service, Service with { Txt = MdnsService.TxtFor(new[] { "X" }) }));
        Assert.False(MdnsAdvertiser.SameService(Service, Service with { Addresses = new[] { IPAddress.Parse("192.168.1.11") } }));
        Assert.False(MdnsAdvertiser.SameService(Service, null));
        Assert.True(MdnsAdvertiser.SameService(null, null));
    }

    /// <summary>
    /// End to end on this machine: a plain resolver (not port 5353) asks the advertiser by unicast. Skipped
    /// quietly where the environment has no multicast-capable interface or port 5353 is unavailable.
    /// </summary>
    [Fact]
    public async Task Advertiser_answers_a_legacy_unicast_query()
    {
        var local = LocalIPv4();
        if (local == null) return;
        var log = new List<string>();
        var service = Service with { Addresses = new[] { local } };
        await using var adv = new MdnsAdvertiser(() => service, _ => true, m => { lock (log) log.Add(m); });
        adv.Start();
        using var client = new UdpClient(new IPEndPoint(IPAddress.Any, 0));
        // To the group, not to local:5353: with a shared port, unicast reaches only one of the sockets
        // (on Windows maybe the system's own responder); multicast reaches all of them
        client.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, local.GetAddressBytes());
        client.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, true);
        var query = Query(777, ("_sysmon._tcp.local", 12, false));
        for (int attempt = 0; attempt < 20; attempt++)
        {
            lock (log) if (log.Any(l => l.Contains("cannot"))) return; // no socket here: nothing to test
            await client.SendAsync(query, new IPEndPoint(MdnsAdvertiser.Group, MdnsAdvertiser.Port));
            using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(250));
            try
            {
                while (true)
                {
                    var got = await client.ReceiveAsync(cts.Token);
                    var reply = Parse(got.Buffer);
                    if (reply.Id != 777) continue;
                    Assert.Equal("pc-abcdefgh._sysmon._tcp.local", reply.Answers.Single().Target);
                    return;
                }
            }
            catch (OperationCanceledException) { }
        }
        lock (log) Assert.Fail("no reply; log: " + string.Join("; ", log));
    }

    private static IPAddress? LocalIPv4()
    {
        try
        {
            return System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up
                            && n.SupportsMulticast
                            && n.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
        }
        catch (System.Net.NetworkInformation.NetworkInformationException) { return null; }
    }

    // ---------------- a tiny independent DNS reader ----------------

    private static byte[] Name(string name)
    {
        var w = new List<byte>();
        foreach (var l in name.Split('.')) { w.Add((byte)l.Length); w.AddRange(Encoding.ASCII.GetBytes(l)); }
        w.Add(0);
        return w.ToArray();
    }

    private static byte[] Query(ushort id, params (string Name, ushort Type, bool Qu)[] questions)
    {
        var w = new List<byte> { (byte)(id >> 8), (byte)id, 0, 0, 0, (byte)questions.Length, 0, 0, 0, 0, 0, 0 };
        foreach (var q in questions)
        {
            w.AddRange(Name(q.Name));
            w.AddRange(new[] { (byte)(q.Type >> 8), (byte)q.Type, (byte)(q.Qu ? 0x80 : 0), (byte)1 });
        }
        return w.ToArray();
    }

    private sealed record Rr(string Name, ushort Type, bool CacheFlush, uint Ttl, string? Target, int Port, string[] Strings, string? Address);

    private sealed record Reply(int Id, List<string> Questions, List<Rr> Answers, List<Rr> Additional);

    private static string ReadName(byte[] m, ref int off)
    {
        var parts = new List<string>();
        int pos = off; bool jumped = false;
        while (true)
        {
            byte len = m[pos];
            if (len == 0) { pos++; break; }
            if ((len & 0xC0) == 0xC0) { if (!jumped) off = pos + 2; jumped = true; pos = ((len & 0x3F) << 8) | m[pos + 1]; continue; }
            parts.Add(Encoding.UTF8.GetString(m, pos + 1, len));
            pos += 1 + len;
        }
        if (!jumped) off = pos;
        return string.Join('.', parts);
    }

    private static Reply Parse(byte[] m)
    {
        ushort U16(int o) => BinaryPrimitives.ReadUInt16BigEndian(m.AsSpan(o));
        Assert.Equal(0x8400, U16(2));
        int qd = U16(4), an = U16(6), ns = U16(8), ar = U16(10);
        Assert.Equal(0, ns);
        int off = 12;
        var questions = new List<string>();
        for (int i = 0; i < qd; i++) { questions.Add(ReadName(m, ref off)); off += 4; }
        Rr Read()
        {
            var name = ReadName(m, ref off);
            ushort type = U16(off), cls = U16(off + 2);
            uint ttl = BinaryPrimitives.ReadUInt32BigEndian(m.AsSpan(off + 4));
            int len = U16(off + 8);
            off += 10;
            int data = off;
            off += len;
            Assert.Equal(1, cls & 0x7FFF);
            string? target = null, address = null; int port = 0; var strings = Array.Empty<string>();
            if (type == 12) { int p = data; target = ReadName(m, ref p); }
            if (type == 33) { port = U16(data + 4); int p = data + 6; target = ReadName(m, ref p); }
            if (type == 1) address = new IPAddress(m.AsSpan(data, 4)).ToString();
            if (type == 16)
            {
                var list = new List<string>();
                for (int p = data; p < data + len; p += 1 + m[p]) list.Add(Encoding.UTF8.GetString(m, p + 1, m[p]));
                strings = list.ToArray();
            }
            return new Rr(name, type, (cls & 0x8000) != 0, ttl, target, port, strings, address);
        }
        var answers = Enumerable.Range(0, an).Select(_ => Read()).ToList();
        var additional = Enumerable.Range(0, ar).Select(_ => Read()).ToList();
        Assert.Equal(m.Length, off);
        return new Reply(U16(0), questions, answers, additional);
    }
}
