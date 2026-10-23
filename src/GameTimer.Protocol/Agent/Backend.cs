using System.Text.Json;
using System.Text.Json.Nodes;

namespace GameTimer.Protocol.Agent;

/// <summary>What the agent says about itself in <c>hello.agent</c> and <c>pair.result.agent</c> (§6).</summary>
public sealed record AgentInfo(string Name, string Platform, string Version);

/// <summary>
/// Who sent a request: the authenticated hub and its role (§8.1), and <see cref="Origin"/> to pass to
/// <see cref="AgentHost.Publish"/> for events this request causes (§5 ordering).
/// </summary>
public sealed record RequestContext(string HubId, string HubName, PeerRole Role, RequestOrigin Origin);

/// <summary>A request's outcome: a <c>result</c> with fields, or an <c>error</c> with a code (§5).</summary>
public sealed class Reply
{
    public bool IsError { get; }
    public string? Code { get; }
    public JsonObject Fields { get; }

    private Reply(bool isError, string? code, JsonObject? fields)
    {
        IsError = isError;
        Code = code;
        Fields = fields ?? new JsonObject();
    }

    /// <summary><c>{ "t": "result", "re": id, ...fields }</c></summary>
    public static Reply Ok(JsonObject fields) => new(false, null, fields);

    /// <summary><c>{ "t": "error", "re": id, "code": code, ...extra }</c> (e.g. <c>value</c> for <c>conflict</c>)</summary>
    public static Reply Error(string code, JsonObject? extra = null) => new(true, code, extra);
}

/// <summary>
/// The platform side of an agent (Windows: TimerController / settings / schedule / history).
/// The protocol host owns sessions, pairing, peers and idempotency; the backend only executes
/// requests of its own capabilities and publishes events through <see cref="AgentHost.Publish"/>.
/// An event that is the direct effect of a request is published with <see cref="RequestContext.Origin"/>
/// (the Windows backend keeps the origin in a field while it calls the controller on the UI thread);
/// everything else (ticks, expiry, changes made on the PC) is published without one.
/// <see cref="Info"/> and <see cref="Capabilities"/> are read from any thread and must not change.
/// <see cref="HandleAsync"/> must not throw after it changed state: a thrown request is answered
/// <c>internal</c>, which is not cached, so the hub's retry would apply the change twice.
/// </summary>
public interface IAgentBackend
{
    AgentInfo Info { get; }

    /// <summary>Capabilities this backend implements (§6), e.g. timer, schedule, settings, history. The host adds <c>peers</c>.</summary>
    IReadOnlyCollection<string> Capabilities { get; }

    /// <summary>The current §7.1 state object, for <c>hello</c>.</summary>
    Task<JsonObject> GetStateAsync();

    /// <summary>
    /// Executes one request of a capability this backend announced. <paramref name="message"/> is the
    /// whole request object (with <c>t</c>, <c>id</c>, <c>rid</c>). Validation errors are replies, not exceptions.
    /// </summary>
    Task<Reply> HandleAsync(string type, JsonElement message, RequestContext context);
}

/// <summary>The PC owner's side of pairing (§8.2): asks for the code shown on the phone.</summary>
public interface IPairingUi
{
    /// <summary>
    /// Shows "'<paramref name="hubName"/>' wants to connect. Enter the code shown on your phone", and
    /// returns the code the owner typed after Windows Hello, or null if the owner cancelled.
    /// <paramref name="cancel"/> fires on the 60-second timeout or when the attempt is aborted.
    /// </summary>
    Task<string?> AskForCodeAsync(string hubName, CancellationToken cancel);

    /// <summary>The pairing was aborted without the owner's doing (e.g. two phones used one QR).</summary>
    void PairingAborted(PairingAbortReason reason);
}

public enum PairingAbortReason
{
    /// <summary>Two different phones reached pair.hello with the same secret: the QR may have been photographed.</summary>
    TwoPhones,
}

/// <summary>
/// One WebSocket connection as the host sees it (§3): one binary message = one Noise message.
/// Implementations map a text message to <see cref="Messages.ProtocolViolation"/>.
/// </summary>
public interface IFrameTransport
{
    /// <summary>
    /// The next binary message, or null once the peer closed the connection. Cancelling a pending
    /// receive must leave the connection usable for sending (a WebSocket adapter reads on one loop into
    /// a queue, because cancelling <c>WebSocket.ReceiveAsync</c> itself aborts the socket).
    /// </summary>
    ValueTask<byte[]?> ReceiveAsync(CancellationToken cancel);

    /// <summary>Sends one binary message. Cancelling it may abort the connection.</summary>
    ValueTask SendAsync(byte[] frame, CancellationToken cancel);

    /// <summary>
    /// Closes the connection; idempotent, callable alongside a pending send or receive, and must make
    /// them return or throw promptly (close with a short timeout, then abort).
    /// </summary>
    ValueTask CloseAsync();
}
