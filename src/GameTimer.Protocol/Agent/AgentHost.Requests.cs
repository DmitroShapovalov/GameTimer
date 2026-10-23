using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Messages;

namespace GameTimer.Protocol.Agent;

public sealed partial class AgentHost
{
    /// <summary>§7.2: capability (null = always available), whether a <c>rid</c> is required, who executes it.</summary>
    private sealed record Command(string? Capability, bool NeedsRid, bool Host);

    private static readonly Dictionary<string, Command> Commands = new(StringComparer.Ordinal)
    {
        ["state.get"] = new(null, false, false),
        ["timer.start"] = new("timer", true, false),
        ["timer.stop"] = new("timer", true, false),
        ["timer.extend"] = new("timer", true, false),
        ["interruption.ack"] = new("timer", true, false),
        ["settings.get"] = new("settings", false, false),
        ["settings.put"] = new("settings", true, false),
        ["schedule.get"] = new("schedule", false, false),
        ["schedule.put"] = new("schedule", true, false),
        ["history.get"] = new("history", false, false),
        ["peers.list"] = new("peers", false, true),
        ["peers.revoke"] = new("peers", true, true),
        ["peers.setRole"] = new("peers", true, true),
    };

    /// <summary>Serialises rid-carrying requests per hub, so two sessions of one hub can't run one rid twice.</summary>
    private readonly Dictionary<string, SemaphoreSlim> _ridLocks = new();

    private async Task HandleMessageAsync(Session session, byte[] plain)
    {
        using var doc = StrictJson.Parse(plain);
        var root = doc.RootElement;
        var type = Str(root, "t") ?? throw new ProtocolViolation("message without t");

        switch (type)
        {
            case "ping":
                session.Enqueue(new JsonObject { ["t"] = "pong" });
                return;
            case "pong":
                return;
            case "bye":
                session.Abort();
                return;
        }

        if (!root.TryGetProperty("id", out var idEl) || idEl.ValueKind != JsonValueKind.Number
            || !idEl.TryGetInt64(out var id) || id <= 0)
            throw new ProtocolViolation("request without a valid id");
        if (id <= session.LastId) throw new ProtocolViolation("request id not strictly increasing");
        session.LastId = id;

        await RunRequestAsync(session, type, root, id).ConfigureAwait(false);
    }

    /// <summary>Runs one request, sends its reply, then delivers the events it caused (§5 ordering).</summary>
    private async Task RunRequestAsync(Session session, string type, JsonElement root, long id)
    {
        var origin = new RequestOrigin(session);
        JsonObject reply;
        try
        {
            reply = await ProcessAsync(session, type, root, id, origin).ConfigureAwait(false);
        }
        catch (Exception)
        {
            reply = ErrorMessage(id, "internal"); // the host's own failure (e.g. the store); the session survives
        }

        session.Enqueue(reply);
        var byeAfter = origin.Close();
        if (byeAfter != null) session.Bye(byeAfter);
    }

    private async Task<JsonObject> ProcessAsync(Session session, string type, JsonElement root, long id, RequestOrigin origin)
    {
        // The hub's current role, and whether it is still paired at all: a revoke or role change may have
        // happened since this session started (the session is closing then, but a frame may be in flight)
        lock (_lock)
        {
            var peer = _state.Peers.FirstOrDefault(p => p.Id == session.HubId);
            if (peer == null || session.IsClosing) return ErrorMessage(id, "forbidden");
            session.Role = peer.Role;
        }
        if (!Commands.TryGetValue(type, out var command)) return ErrorMessage(id, "unknown_command");
        if (command.Capability != null && !Capabilities.Contains(command.Capability)) return ErrorMessage(id, "unsupported");
        if (!command.NeedsRid) return ToMessage(id, await ExecuteAsync(session, command, type, root, origin).ConfigureAwait(false));

        var rid = Str(root, "rid");
        if (Base64Url.TryDecode(rid) is not { Length: 16 }) return ErrorMessage(id, "bad_request");
        var hash = BodyHash(root);

        SemaphoreSlim gate;
        lock (_lock)
        {
            if (!_ridLocks.TryGetValue(session.HubId, out gate!)) _ridLocks[session.HubId] = gate = new SemaphoreSlim(1, 1);
        }
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (_lock)
            {
                var entries = RidEntries(session.HubId);
                var known = entries.FirstOrDefault(e => e.Rid == rid);
                if (known != null)
                {
                    if (known.BodyHash != hash) return ErrorMessage(id, "bad_request");
                    var stored = (JsonObject)JsonNode.Parse(known.Reply)!;
                    return WithRe(stored, id);
                }
            }

            var reply = await ExecuteAsync(session, command, type, root, origin).ConfigureAwait(false);
            var message = ToMessage(id, reply);
            if (!(reply.IsError && reply.Code is "busy" or "internal"))
            {
                var withoutRe = (JsonObject)message.DeepClone();
                withoutRe.Remove("re");
                bool stored = false;
                lock (_lock)
                {
                    // The hub may have been revoked meanwhile (e.g. it revoked itself): keep nothing for it
                    if (_state.Peers.Any(p => p.Id == session.HubId))
                    {
                        var entries = RidEntries(session.HubId);
                        entries.Add(new RidEntry { Rid = rid!, BodyHash = hash, Reply = withoutRe.ToJsonString(), At = _time.GetUtcNow() });
                        if (entries.Count > MaxRidsPerHub) entries.RemoveRange(0, entries.Count - MaxRidsPerHub);
                        stored = true;
                    }
                }
                if (stored) Persist();
            }
            return message;
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<Reply> ExecuteAsync(Session session, Command command, string type, JsonElement root, RequestOrigin origin)
    {
        try
        {
            if (command.Host) return HostCommand(session, type, root, origin);
            return await _backend.HandleAsync(type, root, new RequestContext(session.HubId, session.HubName, session.Role, origin)).ConfigureAwait(false);
        }
        catch (Exception)
        {
            return Reply.Error("internal");
        }
    }

    /// <summary>The live entries for a hub, oldest first, expired ones dropped. Under the lock.</summary>
    private List<RidEntry> RidEntries(string hubId)
    {
        if (!_state.Rids.TryGetValue(hubId, out var list)) _state.Rids[hubId] = list = new List<RidEntry>();
        var cutoff = _time.GetUtcNow() - RidLifetime;
        list.RemoveAll(e => e.At < cutoff);
        return list;
    }

    /// <summary>BLAKE2b-256 of the request without its per-session <c>id</c>: a resend in a new session matches.</summary>
    private static string BodyHash(JsonElement root)
    {
        var copy = (JsonObject)JsonNode.Parse(root.GetRawText())!;
        copy.Remove("id");
        var bytes = System.Text.Encoding.UTF8.GetBytes(copy.ToJsonString());
        return Base64Url.Encode(Sodium.GenericHash.Hash(bytes, (byte[]?)null, 32));
    }

    // ---------------- peers.* (§7.2, §8.1) ----------------

    private Reply HostCommand(Session session, string type, JsonElement root, RequestOrigin origin)
    {
        switch (type)
        {
            case "peers.list":
                return Reply.Ok(new JsonObject { ["peers"] = PeersJson(session.HubId) });

            case "peers.revoke":
            {
                var target = Str(root, "peer") is { } tid ? FindPeer(tid) : null;
                if (target == null) return Reply.Error("bad_request");
                if (session.Role != PeerRole.Admin && target.Id != session.HubId) return Reply.Error("forbidden");
                return Revoke(target.Id, origin, protectLastAdmin: true) switch
                {
                    PeerChange.Done => Reply.Ok(new JsonObject { ["peers"] = PeersJson(session.HubId) }),
                    PeerChange.LastAdmin => Reply.Error("last_admin"),
                    _ => Reply.Error("bad_request"),
                };
            }

            case "peers.setRole":
            {
                if (session.Role != PeerRole.Admin) return Reply.Error("forbidden");
                var target = Str(root, "peer") is { } tid ? FindPeer(tid) : null;
                PeerRole? role = Str(root, "role") switch { "admin" => PeerRole.Admin, "parent" => PeerRole.Parent, _ => null };
                if (target == null || role == null) return Reply.Error("bad_request");
                return SetRole(target.Id, role.Value, origin, protectLastAdmin: true) switch
                {
                    PeerChange.Done => Reply.Ok(new JsonObject { ["peers"] = PeersJson(session.HubId) }),
                    PeerChange.LastAdmin => Reply.Error("last_admin"),
                    _ => Reply.Error("bad_request"),
                };
            }
        }
        return Reply.Error("unknown_command");
    }

    // ---------------- reply shapes ----------------

    private static JsonObject ToMessage(long id, Reply reply)
    {
        var m = reply.IsError
            ? new JsonObject { ["t"] = "error", ["re"] = id, ["code"] = reply.Code }
            : new JsonObject { ["t"] = "result", ["re"] = id };
        foreach (var (k, v) in reply.Fields)
            if (k is not ("t" or "re" or "code")) m[k] = v?.DeepClone();
        return m;
    }

    private static JsonObject ErrorMessage(long id, string code) => ToMessage(id, Reply.Error(code));

    private static JsonObject WithRe(JsonObject stored, long id)
    {
        var m = new JsonObject { ["t"] = stored["t"]?.DeepClone(), ["re"] = id };
        foreach (var (k, v) in stored)
            if (k != "t") m[k] = v?.DeepClone();
        return m;
    }

    internal static bool CodesEqual(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(System.Text.Encoding.ASCII.GetBytes(a), System.Text.Encoding.ASCII.GetBytes(b));
}
