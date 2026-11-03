using System.Text.Json;
using GameTimer.Models;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace GameTimer.Services;

/// <summary>
/// Embedded Kestrel host for phone control: the protocol v2 WebSocket endpoints <c>/v2</c> and
/// <c>/v2/pair</c> (RemoteServer.V2.cs), nothing else. Off by default; started and stopped from the
/// Phones page. Connections from outside this PC / the home LAN are aborted (<see cref="NetworkFilter"/>);
/// everything after that is end-to-end encrypted and authenticated by the protocol itself.
/// (The older phone web page and its v1 REST / WebSocket API were removed in v1.9.)
/// </summary>
public sealed partial class RemoteServer
{
    private static readonly TimeSpan StopTimeout = TimeSpan.FromSeconds(3);
    private readonly UiThread _ui;
    private readonly TimerController _timer;
    private readonly AppSettings _settings;
    private readonly SettingsService _settingsService;
    private readonly SessionLog _sessionLog;

    private WebApplication? _app;

    /// <summary>JSON shape of the v2 bodies (enums as camelCase strings).</summary>
    private static readonly JsonSerializerOptions Json = CreateJsonOptions();

    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase, allowIntegerValues: false));
        return options;
    }

    public RemoteServer(UiThread ui,
        TimerController timer, AppSettings settings, SettingsService settingsService, SessionLog sessionLog,
        GameTimer.Protocol.Agent.IAgentStore agentStore, GameTimer.Protocol.Agent.IPairingUi pairingUi)
    {
        _ui = ui;
        _timer = timer;
        _settings = settings;
        _settingsService = settingsService;
        _sessionLog = sessionLog;
        InitV2(agentStore, pairingUi);
    }

    public bool IsRunning => _app != null;
    public int? Port { get; private set; }

    /// <summary>Error message from the last failed start (e.g. port already in use), or null.</summary>
    public string? LastError { get; private set; }

    /// <summary>Starts listening on the given port. Returns false and sets <see cref="LastError"/> on failure.</summary>
    public async Task<bool> StartAsync(int port)
    {
        if (_app != null) return true;

        WebApplication? app = null;
        try
        {
            var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions
            {
                Args = Array.Empty<string>(),
                ContentRootPath = AppContext.BaseDirectory
            });
            builder.Logging.ClearProviders();
            builder.WebHost.ConfigureKestrel(options =>
            {
                options.ListenAnyIP(port);
                options.AddServerHeader = false;             // don't advertise the server software
                options.Limits.MaxRequestBodySize = 64 * 1024; // every real payload is tiny; cap LAN abuse
            });

            app = builder.Build();

            // 1. LAN-only: drop anything from outside the home network without an answer
            app.Use(async (context, next) =>
            {
                if (!NetworkFilter.IsAllowed(context.Connection.RemoteIpAddress))
                {
                    context.Abort();
                    return;
                }
                await next(context);
            });

            app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(30) });

            MapV2(app);

            await app.StartAsync();

            _app = app;
            SubscribeEvents();
            Port = port;
            StartDiscovery(port);
            LastError = null;
            _ = UpdateRelayAsync();
            return true;
        }
        catch (Exception ex)
        {
            if (app != null)
                await app.DisposeAsync();
            LastError = ex.Message;
            return false;
        }
    }

    public async Task StopAsync()
    {
        var app = _app;
        if (app == null) return;

        try { await StopDiscoveryAsync(); } catch { /* best effort: the goodbye */ }
        if (_relay != null) _relay.AcceptStreams = false; // no relayed session may start after the bye
        try { await Agent.ShutdownAsync(); } catch { /* best effort: bye shutdown to the parent apps */ }
        Agent.CancelPairing();
        _app = null;
        await UpdateRelayAsync(); // after the bye: relayed sessions got it too; also stops one started meanwhile
        Port = null;
        UnsubscribeEvents();
        try
        {
            using var cts = new CancellationTokenSource(StopTimeout);
            await app.StopAsync(cts.Token);
        }
        catch
        {
            // Shutting down anyway
        }
        finally
        {
            await app.DisposeAsync();
        }
    }

    #region Helpers

    private static string ClientKey(HttpContext context) =>
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

    #endregion
}
