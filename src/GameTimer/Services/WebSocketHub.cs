using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Threading.Channels;

namespace GameTimer.Services;

/// <summary>
/// Open WebSocket connections of paired devices. Broadcasts never block the caller (the UI thread):
/// each connection has its own small outbox and send loop; a slow phone only drops its oldest messages.
/// Incoming messages after authentication are read and ignored (the socket is push-only).
/// Never cancel a pending ReceiveAsync: that aborts the socket and the close reason is lost.
/// Closing is done by stopping the send loop, sending a close frame and waiting for the echo.
/// </summary>
public sealed class WebSocketHub
{
    private const int OutboxCapacity = 32;
    private static readonly TimeSpan CloseTimeout = TimeSpan.FromSeconds(2);

    private readonly ConcurrentDictionary<Guid, Connection> _connections = new();

    public int MaxConnections { get; init; } = 10;
    public int Count => _connections.Count;

    private sealed class Connection
    {
        public required string DeviceId { get; init; }
        public required WebSocket Socket { get; init; }
        public required Channel<byte[]> Outbox { get; init; }
        public required CancellationTokenSource Cts { get; init; }
        public WebSocketCloseStatus CloseStatus = WebSocketCloseStatus.NormalClosure;
        public string CloseReason = "";
    }

    /// <summary>
    /// Serves an authenticated socket until either side closes it. <paramref name="hello"/> is sent first,
    /// before any broadcast. The caller checks <see cref="MaxConnections"/> before accepting.
    /// </summary>
    public async Task RunAsync(WebSocket socket, string deviceId, byte[] hello, CancellationToken requestAborted)
    {
        var connection = new Connection
        {
            DeviceId = deviceId,
            Socket = socket,
            Outbox = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(OutboxCapacity)
            {
                FullMode = BoundedChannelFullMode.DropOldest,
                SingleReader = true
            }),
            Cts = CancellationTokenSource.CreateLinkedTokenSource(requestAborted)
        };
        connection.Outbox.Writer.TryWrite(hello);

        var id = Guid.NewGuid();
        _connections[id] = connection;
        try
        {
            var receive = ReceiveLoopAsync(connection);
            var send = SendLoopAsync(connection, connection.Cts.Token);
            await Task.WhenAny(send, receive); // client closed, or Disconnect / request abort
            connection.Cts.Cancel();
            await send;
            await CloseAsync(connection.Socket, receive, connection.CloseStatus, connection.CloseReason);
        }
        finally
        {
            _connections.TryRemove(id, out _);
            connection.Cts.Dispose();
        }
    }

    public void Broadcast(byte[] message)
    {
        foreach (var connection in _connections.Values)
            connection.Outbox.Writer.TryWrite(message);
    }

    /// <summary>Closes the connections of devices that match (e.g. revoked ones).</summary>
    public void Disconnect(Func<string, bool> deviceMatches, WebSocketCloseStatus status, string reason)
    {
        foreach (var connection in _connections.Values.Where(c => deviceMatches(c.DeviceId)))
        {
            connection.CloseStatus = status;
            connection.CloseReason = reason;
            connection.Cts.Cancel();
        }
    }

    public void CloseAll() =>
        Disconnect(_ => true, WebSocketCloseStatus.EndpointUnavailable, "server_stopping");

    private static async Task SendLoopAsync(Connection connection, CancellationToken token)
    {
        try
        {
            await foreach (var message in connection.Outbox.Reader.ReadAllAsync(token))
                await connection.Socket.SendAsync(message, WebSocketMessageType.Text, true, token);
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
    }

    // No cancellation token on purpose: ends when the client closes or the socket is aborted
    private static async Task ReceiveLoopAsync(Connection connection)
    {
        var buffer = new byte[1024];
        try
        {
            while (true)
            {
                var result = await connection.Socket.ReceiveAsync(buffer, CancellationToken.None);
                if (result.MessageType == WebSocketMessageType.Close) return;
            }
        }
        catch (WebSocketException) { }
        catch (OperationCanceledException) { }
    }

    /// <summary>
    /// Sends a close frame (while <paramref name="pendingReceive"/> may still be waiting), waits for the
    /// client's echo, and aborts the socket if it doesn't come in time.
    /// </summary>
    public static async Task CloseAsync(WebSocket socket, Task pendingReceive,
        WebSocketCloseStatus status, string reason)
    {
        try
        {
            if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                using var cts = new CancellationTokenSource(CloseTimeout);
                await socket.CloseOutputAsync(status, reason, cts.Token);
            }
        }
        catch
        {
            socket.Abort();
        }

        if (await Task.WhenAny(pendingReceive, Task.Delay(CloseTimeout)) != pendingReceive)
            socket.Abort();
        try { await pendingReceive; } catch { /* aborted */ }
    }
}
