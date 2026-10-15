using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using GameTimer.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace GameTimer.Services;

/// <summary>
/// WebSocket push events at <c>/ws</c> (RMT-03). Authentication: a valid
/// <c>Authorization: Bearer</c> header on the upgrade request, or (browsers) a first message
/// <c>{"type":"auth","token":"…"}</c> within <see cref="AuthTimeout"/>.
/// Server messages, all JSON with a <c>type</c>:
/// <c>hello</c> {status, settings} once after auth; <c>started</c> / <c>stopped</c> / <c>expired</c> / <c>tick</c> {status};
/// <c>alert</c> / <c>extended</c> {minutes, status}; <c>settingsChanged</c> {settings}.
/// </summary>
public sealed partial class RemoteServer
{
    private static readonly TimeSpan AuthTimeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan TickInterval = TimeSpan.FromSeconds(5);
    private const int MaxAuthMessageBytes = 1024;

    private readonly WebSocketHub _hub = new();
    private long _lastTickSentMs;
    private string? _lastSettingsJson;

    private sealed record AuthMessage(string? Type, string? Token);

    private void MapEvents(WebApplication app)
    {
        app.Map("/ws", async context =>
        {
            if (!context.WebSockets.IsWebSocketRequest)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                return;
            }
            if (_hub.Count >= _hub.MaxConnections)
            {
                context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                return;
            }

            using var socket = await context.WebSockets.AcceptWebSocketAsync();

            // Set by the auth middleware when the upgrade request had a valid bearer header
            var device = context.Items[DeviceItemKey] as PairedDevice
                         ?? await AuthenticateFirstMessageAsync(socket, context);
            if (device == null) return; // already closed with the reason

            var hello = await _ui.InvokeAsync(() =>
                Serialize(new { type = "hello", status = BuildStatus(), settings = BuildSettings() }));
            await _hub.RunAsync(socket, device.Id, hello, context.RequestAborted);
        });
    }

    /// <summary>Waits for {"type":"auth","token"} and returns its device, or closes the socket and returns null.</summary>
    private async Task<PairedDevice?> AuthenticateFirstMessageAsync(WebSocket socket, HttpContext context)
    {
        // The receive is not cancelled on timeout (that would abort the socket and lose the close reason)
        var receive = ReceiveTextMessageAsync(socket, MaxAuthMessageBytes);
        if (await Task.WhenAny(receive, Task.Delay(AuthTimeout, context.RequestAborted)) != receive)
            return await FailAsync(receive, "auth_timeout");

        var text = await receive;
        PairedDevice? device = null;
        if (text != null)
        {
            try
            {
                var message = JsonSerializer.Deserialize<AuthMessage>(text, Json);
                if (message?.Type == "auth")
                    device = _devices.FindByToken(message.Token);
            }
            catch (JsonException) { }
        }

        if (device == null)
            return await FailAsync(receive, "unauthorized");

        _devices.Touch(device);
        return device;

        async Task<PairedDevice?> FailAsync(Task pendingReceive, string reason)
        {
            _authFailures?.AttemptAcquire(ClientKey(context)).Dispose(); // counts toward the per-IP limit
            await WebSocketHub.CloseAsync(socket, pendingReceive, WebSocketCloseStatus.PolicyViolation, reason);
            return null;
        }
    }

    /// <summary>One text message up to maxBytes, or null (binary, too long, closed, broken).</summary>
    private static async Task<string?> ReceiveTextMessageAsync(WebSocket socket, int maxBytes)
    {
        var buffer = new byte[maxBytes];
        int length = 0;
        try
        {
            ValueWebSocketReceiveResult result;
            do
            {
                if (length == buffer.Length) return null;
                result = await socket.ReceiveAsync(buffer.AsMemory(length), CancellationToken.None);
                length += result.Count;
            } while (!result.EndOfMessage);

            return result.MessageType == WebSocketMessageType.Text
                ? Encoding.UTF8.GetString(buffer, 0, length)
                : null;
        }
        catch (WebSocketException)
        {
            return null;
        }
    }

    #region Event sources (UI thread)

    private void SubscribeEvents()
    {
        _timer.StateChanged += OnTimerStateChanged;
        _timer.Tick += OnTimerTick;
        _timer.AlertTriggered += OnAlert;
        _timer.Extended += OnExtended;
        _settingsService.SettingsChanged += OnSettingsChanged;
        _devices.Changed += OnDevicesChanged;
        _timer.InterruptionChanged += OnInterruptionChanged;
        _sessionLog.Changed += OnHistoryChanged;
        _lastSettingsJson = JsonSerializer.Serialize(BuildSettings(), Json);
    }

    private void UnsubscribeEvents()
    {
        _timer.StateChanged -= OnTimerStateChanged;
        _timer.Tick -= OnTimerTick;
        _timer.AlertTriggered -= OnAlert;
        _timer.Extended -= OnExtended;
        _settingsService.SettingsChanged -= OnSettingsChanged;
        _devices.Changed -= OnDevicesChanged;
        _timer.InterruptionChanged -= OnInterruptionChanged;
        _sessionLog.Changed -= OnHistoryChanged;
    }

    private void OnTimerStateChanged(TimerState state)
    {
        var type = state switch
        {
            TimerState.Running => "started",
            TimerState.Expired => "expired",
            _ => "stopped" // Stopped and NotStarted (reset)
        };
        _lastTickSentMs = Environment.TickCount64; // next tick a full interval after start
        Broadcast(new { type, status = BuildStatus() });
    }

    private void OnTimerTick(TimeSpan remaining)
    {
        var now = Environment.TickCount64;
        // Small tolerance: the 1 s DispatcherTimer drifts a little
        if (now - _lastTickSentMs < TickInterval.TotalMilliseconds - 250) return;
        _lastTickSentMs = now;
        Broadcast(new { type = "tick", status = BuildStatus() });
    }

    private void OnAlert(int minutes) =>
        Broadcast(new { type = "alert", minutes, status = BuildStatus() });

    private void OnExtended(int minutes) =>
        Broadcast(new { type = "extended", minutes, status = BuildStatus() });

    // SettingsChanged also fires for desktop-only fields (last typed times etc.): send only real changes
    private void OnSettingsChanged(object source)
    {
        var settings = BuildSettings();
        var json = JsonSerializer.Serialize(settings, Json);
        if (json == _lastSettingsJson) return;
        _lastSettingsJson = json;
        Broadcast(new { type = "settingsChanged", settings });
    }

    // The "app was closed while a timer ran" note appeared or was dismissed
    private void OnInterruptionChanged() =>
        Broadcast(new { type = "interruption", status = BuildStatus() });

    // A new history event was recorded; the phone refetches the list
    private void OnHistoryChanged() =>
        Broadcast(new { type = "history" });

    // A revoked device loses its open socket immediately
    private void OnDevicesChanged()
    {
        var paired = _devices.Devices.Select(d => d.Id).ToHashSet();
        _hub.Disconnect(id => !paired.Contains(id), WebSocketCloseStatus.PolicyViolation, "revoked");
    }

    #endregion

    private void Broadcast(object message)
    {
        if (_hub.Count == 0) return;
        _hub.Broadcast(Serialize(message));
    }

    private static byte[] Serialize(object message) =>
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(message, Json));
}
