using System.Net.WebSockets;

namespace GameTimer.Relay;

internal enum Received { Binary, Text, Close, TooBig }

/// <summary>An item of a socket's outbox: a binary message, or the close to send (then the outbox ends).</summary>
internal sealed record Out(byte[]? Data, int CloseCode, HubStream? Owner);

internal static class WsIo
{
    /// <summary>Reads one whole message of at most <paramref name="max"/> bytes.</summary>
    public static async Task<(Received Kind, byte[]? Data)> ReceiveAsync(WebSocket ws, int max)
    {
        var buf = new byte[Math.Min(max + 1, 16 * 1024)];
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await ws.ReceiveAsync(new ArraySegment<byte>(buf), CancellationToken.None).ConfigureAwait(false);
            if (r.MessageType == WebSocketMessageType.Close) return (Received.Close, null);
            if (ms.Length + r.Count > max) return (Received.TooBig, null);
            ms.Write(buf, 0, r.Count);
            if (r.EndOfMessage)
                return (r.MessageType == WebSocketMessageType.Text ? Received.Text : Received.Binary, ms.ToArray());
        }
    }

    /// <summary>
    /// Sends the outbox in order until a close item or the end; returns when the socket can't take more.
    /// <paramref name="sent"/> runs after each message (accounting, timestamps).
    /// </summary>
    public static async Task SendLoopAsync(WebSocket ws, System.Threading.Channels.ChannelReader<Out> outbox, Action<Out> sent)
    {
        try
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
                    break;
                }
                try
                {
                    await ws.SendAsync(o.Data, WebSocketMessageType.Binary, true, CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception)
                {
                    sent(o);
                    break;
                }
                sent(o);
            }
        }
        finally
        {
            // Release the accounting of whatever is still queued
            while (outbox.TryRead(out var rest)) sent(rest);
        }
    }
}
