using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.RateLimiting;
using GameTimer.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace GameTimer.Services;

/// <summary>
/// Embedded Kestrel host for the phone remote (Phase 1). Off by default; started and stopped
/// from the Settings tab.
///
/// Security layers, in pipeline order:
/// 1. <see cref="NetworkFilter"/>: connections from outside this PC / the home LAN are aborted.
/// 2. Rate limiting: <c>POST /pair</c> per client IP, and failed authentications per client IP.
/// 3. Bearer token auth on everything under <c>/api</c> and <c>/ws</c>.
///    Only <c>/pair</c> (and, from step 6, static files) are reachable without a token.
/// </summary>
public sealed partial class RemoteServer
{
    private static readonly TimeSpan StopTimeout = TimeSpan.FromSeconds(3);
    private const string PairPolicy = "pair";
    private const string DeviceItemKey = "GameTimer.Device";
    private const int MaxDeviceNameLength = 40;

    private readonly DeviceStore _devices;
    private readonly PairingService _pairing;
    private readonly UiThread _ui;
    private readonly TimerController _timer;
    private readonly AppSettings _settings;
    private readonly SettingsService _settingsService;

    private WebApplication? _app;
    private PartitionedRateLimiter<string>? _authFailures;

    /// <summary>JSON shape shared by HTTP responses and WebSocket messages.</summary>
    private static readonly JsonSerializerOptions Json = CreateJsonOptions();

    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase, allowIntegerValues: false));
        return options;
    }

    public RemoteServer(DeviceStore devices, PairingService pairing, UiThread ui,
        TimerController timer, AppSettings settings, SettingsService settingsService)
    {
        _devices = devices;
        _pairing = pairing;
        _ui = ui;
        _timer = timer;
        _settings = settings;
        _settingsService = settingsService;
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
            builder.WebHost.ConfigureKestrel(options => options.ListenAnyIP(port));

            // Enums as camelCase strings ("running", "interval", "lock"); numbers are rejected
            builder.Services.ConfigureHttpJsonOptions(options =>
            {
                foreach (var converter in Json.Converters)
                    options.SerializerOptions.Converters.Add(converter);
            });

            builder.Services.AddRateLimiter(options =>
            {
                options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
                options.AddPolicy(PairPolicy, context => RateLimitPartition.GetFixedWindowLimiter(
                    ClientKey(context),
                    _ => new FixedWindowRateLimiterOptions
                    {
                        PermitLimit = 5,
                        Window = TimeSpan.FromMinutes(1),
                        QueueLimit = 0
                    }));
            });

            var authFailures = PartitionedRateLimiter.Create<string, string>(key =>
                RateLimitPartition.GetFixedWindowLimiter(key, _ => new FixedWindowRateLimiterOptions
                {
                    PermitLimit = 10,
                    Window = TimeSpan.FromMinutes(1),
                    QueueLimit = 0
                }));

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

            // 2. Endpoint rate-limit policies (/pair)
            app.UseRateLimiter();

            app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(30) });

            // 3. Bearer auth for the API and the WebSocket
            app.Use(async (context, next) =>
            {
                var path = context.Request.Path;
                if (!path.StartsWithSegments("/api") && !path.StartsWithSegments("/ws"))
                {
                    await next(context);
                    return;
                }

                var bearer = GetBearerToken(context.Request);

                // Browsers can't set headers on a WebSocket: /ws without a header authenticates
                // with its first message instead (see RemoteServer.Events.cs)
                if (bearer == null && path.StartsWithSegments("/ws") && context.WebSockets.IsWebSocketRequest)
                {
                    await next(context);
                    return;
                }

                var device = _devices.FindByToken(bearer);
                if (device == null)
                {
                    using var lease = authFailures.AttemptAcquire(ClientKey(context));
                    context.Response.StatusCode = lease.IsAcquired
                        ? StatusCodes.Status401Unauthorized
                        : StatusCodes.Status429TooManyRequests;
                    return;
                }

                _devices.Touch(device);
                context.Items[DeviceItemKey] = device;
                await next(context);
            });

            MapPairing(app);
            MapDevices(app);
            MapTimer(app);
            MapSettings(app);
            MapEvents(app);

            await app.StartAsync();

            _app = app;
            _authFailures = authFailures;
            SubscribeEvents();
            Port = port;
            LastError = null;
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

        _app = null;
        Port = null;
        _pairing.Cancel();
        UnsubscribeEvents();
        _hub.CloseAll(); // otherwise open sockets keep StopAsync waiting
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
            _authFailures?.Dispose();
            _authFailures = null;
        }
    }

    #region Endpoints

    private void MapPairing(WebApplication app)
    {
        // Page the QR code opens on the phone. Temporary until the full mobile UI (step 6).
        app.MapGet("/pair", (HttpContext context) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            return Results.Content(PairPage.Value, "text/html; charset=utf-8");
        });

        // Exchange a one-time code for a long-lived device token
        app.MapPost("/pair", async (PairRequest? request) =>
        {
            if (request == null || !_pairing.TryConsume(request.Code))
                return Results.Json(new { error = "invalid_code" }, statusCode: StatusCodes.Status400BadRequest);

            var name = SanitizeName(request.Name);
            var (device, token) = await _ui.InvokeAsync(() =>
            {
                var added = _devices.Add(name);
                _pairing.NotifyPaired(added.Device);
                return added;
            });

            return Results.Json(new { deviceId = device.Id, name = device.Name, token });
        }).RequireRateLimiting(PairPolicy);
    }

    private void MapDevices(WebApplication app)
    {
        app.MapGet("/api/devices", (HttpContext context) =>
        {
            var current = CurrentDevice(context);
            return Results.Json(_devices.Devices.Select(d => new
            {
                id = d.Id,
                name = d.Name,
                created = d.Created,
                lastSeen = d.LastSeen,
                current = d.Id == current.Id
            }));
        });

        app.MapDelete("/api/devices/{id}", async (string id) =>
            await _ui.InvokeAsync(() => _devices.Revoke(id))
                ? Results.NoContent()
                : Results.NotFound());
    }

    #endregion

    #region Helpers

    public sealed record PairRequest(string? Code, string? Name);

    private static readonly Lazy<string> PairPage = new(() =>
    {
        using var stream = typeof(RemoteServer).Assembly.GetManifestResourceStream("Remote/pair.html")
            ?? throw new InvalidOperationException("Embedded resource Remote/pair.html not found.");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    });

    private static PairedDevice CurrentDevice(HttpContext context) =>
        (PairedDevice)context.Items[DeviceItemKey]!;

    private static string ClientKey(HttpContext context) =>
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

    private static string? GetBearerToken(HttpRequest request)
    {
        var header = request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        return header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            ? header[prefix.Length..].Trim()
            : null;
    }

    private static string SanitizeName(string? name)
    {
        var clean = new string((name ?? "").Where(c => !char.IsControl(c)).ToArray()).Trim();
        if (clean.Length > MaxDeviceNameLength) clean = clean[..MaxDeviceNameLength].Trim();
        return clean.Length > 0 ? clean : "Phone";
    }

    #endregion
}
