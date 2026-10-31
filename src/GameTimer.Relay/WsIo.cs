using System.Buffers;
using System.Net.WebSockets;

namespace GameTimer.Relay;

internal enum Received { Binary, Text, Close, TooBig, Overloaded }

/// <summary>An item of a socket's outbox: a binary message, or the close to send (then the outbox ends).</summary>
internal sealed record Out(byte[]? Data, int CloseCode, HubStream? Owner);

internal static class WsIo
{
    private const int Chunk = 4096;

    /// <summary>
    /// Reads one whole message of at most <paramref name="max"/> bytes. A waiting socket holds only a pooled
    /// 4 KiB chunk; the buffer of a message that arrives in pieces counts (at its full pooled size) against
    /// the relay's byte budget until it is complete. When the budget is spent, the rest of the message is
    /// read and dropped and <see cref="Received.Overloaded"/> returns its first bytes (up to 5: the frame
    /// header), so only that stream needs to end.
    /// </summary>
    public static async Task<(Received Kind, byte[]? Data)> ReceiveAsync(WebSocket ws, int max, RelayState s)
    {
        var chunk = ArrayPool<byte>.Shared.Rent(Chunk);
        byte[]? msg = null;
        int len = 0, accounted = 0;
        byte[]? head = null; // set once the message is being dropped
        try
        {
            while (true)
            {
                var r = await ws.ReceiveAsync(new ArraySegment<byte>(chunk, 0, Chunk), CancellationToken.None).ConfigureAwait(false);
                if (r.MessageType == WebSocketMessageType.Close) return (Received.Close, null);
                var kind = r.MessageType == WebSocketMessageType.Text ? Received.Text : Received.Binary;
                if (len + r.Count > max) return (Received.TooBig, null);
                if (head != null)
                {
                    len += r.Count;
                    if (r.EndOfMessage) return (Received.Overloaded, head);
                    continue;
                }
                if (r.EndOfMessage && msg == null) return (kind, chunk.AsSpan(0, r.Count).ToArray()); // one piece
                if (msg == null || len + r.Count > msg.Length)
                {
                    int want = Math.Min(max, Math.Max(2 * (len + r.Count), 16 * 1024));
                    var bigger = s.TryBuffer(want) ? ArrayPool<byte>.Shared.Rent(want) : null;
                    if (bigger == null)
                    {
                        // Over budget: keep the header, drop the rest of this message
                        head = (msg != null ? msg.AsSpan(0, len) : chunk.AsSpan(0, r.Count))[..Math.Min(5, msg != null ? len : r.Count)].ToArray();
                        len += r.Count;
                        if (r.EndOfMessage) return (Received.Overloaded, head);
                        continue;
                    }
                    accounted += want;
                    if (msg != null)
                    {
                        msg.AsSpan(0, len).CopyTo(bigger);
                        ArrayPool<byte>.Shared.Return(msg);
                    }
                    msg = bigger;
                }
                chunk.AsSpan(0, r.Count).CopyTo(msg.AsSpan(len));
                len += r.Count;
                if (r.EndOfMessage) return (kind, msg.AsSpan(0, len).ToArray());
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(chunk);
            if (msg != null) ArrayPool<byte>.Shared.Return(msg);
            s.Unbuffer(accounted);
        }
    }

    /// <summary>
    /// Sends the outbox in order until a close item or the end; returns when the socket can't take more.
    /// <paramref name="sent"/> runs once for every item taken out (accounting, timestamps). Items still queued,
    /// or written after this returns, are released by the owner's final drain.
    /// </summary>
    public static async Task SendLoopAsync(WebSocket ws, System.Threading.Channels.ChannelReader<Out> outbox, Action<Out> sent)
    {
        await foreach (var o in outbox.ReadAllAsync().ConfigureAwait(false))
        {
            if (o.Data == null)
            {
                sent(o);
                try
                {
                    if (ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
                        await ws.CloseOutputAsync((WebSocketCloseStatus)o.CloseCode, null, CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception) { }
                return;
            }
            try
            {
                await ws.SendAsync(o.Data, WebSocketMessageType.Binary, true, CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception)
            {
                sent(o);
                return;
            }
            sent(o);
        }
    }

    /// <summary>Completes the outbox and releases whatever is left in it (after the send loop has ended).</summary>
    public static void Drain(System.Threading.Channels.Channel<Out> outbox, Action<Out> release)
    {
        outbox.Writer.TryComplete();
        while (outbox.Reader.TryRead(out var o)) release(o);
    }
}
