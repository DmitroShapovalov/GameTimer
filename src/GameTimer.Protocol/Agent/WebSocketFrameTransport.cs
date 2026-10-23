using System.Net.WebSockets;
using System.Threading.Channels;
using GameTimer.Protocol.Messages;

namespace GameTimer.Protocol.Agent;

/// <summary>
/// <see cref="IFrameTransport"/> over an ASP.NET Core WebSocket (protocol §3: one binary message =
/// one Noise message, at most 65 535 bytes; a text message is a protocol violation).
/// One loop reads the socket into a queue, so cancelling <see cref="ReceiveAsync"/> never touches the
/// socket (cancelling WebSocket.ReceiveAsync would abort it, which the pairing flow must avoid).
/// </summary>
public sealed class WebSocketFrameTransport : IFrameTransport
{
    private const int MaxMessage = 65535;
    private static readonly TimeSpan CloseTimeout = TimeSpan.FromSeconds(1);

    private readonly WebSocket _socket;
    private readonly Channel<Frame> _frames = Channel.CreateBounded<Frame>(new BoundedChannelOptions(16) { SingleWriter = true });
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly CancellationTokenSource _stop = new();
    private int _closed;

    private readonly record struct Frame(byte[]? Data, bool IsViolation);

    public WebSocketFrameTransport(WebSocket socket)
    {
        _socket = socket;
        ReadLoop = Task.Run(ReadLoopAsync);
    }

    /// <summary>Completes when the socket is closed or broken.</summary>
    public Task ReadLoop { get; }

    public async ValueTask<byte[]?> ReceiveAsync(CancellationToken cancel)
    {
        Frame frame;
        try { frame = await _frames.Reader.ReadAsync(cancel).ConfigureAwait(false); }
        catch (ChannelClosedException) { return null; }
        if (frame.IsViolation) throw new ProtocolViolation("text or oversized WebSocket message");
        return frame.Data;
    }

    public async ValueTask SendAsync(byte[] frame, CancellationToken cancel)
    {
        await _sendLock.WaitAsync(cancel).ConfigureAwait(false);
        try
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancel, _stop.Token);
            await _socket.SendAsync(frame, WebSocketMessageType.Binary, endOfMessage: true, linked.Token).ConfigureAwait(false);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public async ValueTask CloseAsync()
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        try
        {
            if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                using var timeout = new CancellationTokenSource(CloseTimeout);
                await _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, timeout.Token).ConfigureAwait(false);
                // Let the peer read what was sent and answer the close: aborting right away resets the
                // TCP connection, and a reset can discard the last messages (e.g. pair.result) unread
                await Task.WhenAny(ReadLoop, Task.Delay(CloseTimeout)).ConfigureAwait(false);
            }
        }
        catch
        {
            // a stuck or broken socket: abort below
        }
        finally
        {
            if (_socket.State != WebSocketState.Closed) _socket.Abort(); // unblocks a pending send and the read loop
            try { _stop.Cancel(); } catch (ObjectDisposedException) { }
        }
    }

    private async Task ReadLoopAsync()
    {
        var buffer = new byte[MaxMessage + 1];
        bool violated = false;
        try
        {
            while (true)
            {
                int length = 0;
                bool tooLong = false;
                ValueWebSocketReceiveResult result;
                do
                {
                    if (length == buffer.Length) { tooLong = true; length = 0; } // keep reading, discard
                    result = await _socket.ReceiveAsync(buffer.AsMemory(length), _stop.Token).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    length += result.Count;
                } while (!result.EndOfMessage);

                // After a violation keep draining until the peer's close, so our bye and close can reach it
                // (stopping to read would make CloseAsync abort the connection and lose them)
                if (violated) continue;
                if (tooLong || result.MessageType != WebSocketMessageType.Binary || length > MaxMessage)
                {
                    violated = true;
                    await _frames.Writer.WriteAsync(new Frame(null, true)).ConfigureAwait(false);
                    continue;
                }
                await _frames.Writer.WriteAsync(new Frame(buffer.AsSpan(0, length).ToArray(), false)).ConfigureAwait(false);
            }
        }
        catch
        {
            // closed, aborted or broken: the reader sees the end of the queue
        }
        finally
        {
            _frames.Writer.TryComplete();
        }
    }
}
