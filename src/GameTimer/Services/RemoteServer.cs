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
/// Also serves the phone UI (embedded Remote/ files) at "/" without auth.
///
/// Security layers, in pipeline order:
/// 1. <see cref="NetworkFilter"/>: connections from outside this PC / the home LAN are aborted.
/// 2. Rate limiting: <c>POST /pair</c> per client IP, and failed authentications per client IP.
/// 3. Bearer token auth on everything under <c>/api</c> and <c>/ws</c>.
///    Only <c>/pair</c> and the static phone UI files are reachable without a token.
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
    private readonly SessionLog _sessionLog;

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
        TimerController timer, AppSettings settings, SettingsService settingsService, SessionLog sessionLog,
        GameTimer.Protocol.Agent.IAgentStore agentStore, GameTimer.Protocol.Agent.IPairingUi pairingUi)
    {
        _devices = devices;
        _pairing = pairing;
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

            MapStatic(app);
            MapPairing(app);
            MapDevices(app);
            MapTimer(app);
            MapSettings(app);
            MapEvents(app);
            MapV2(app);

            await app.StartAsync();

            _app = app;
            _authFailures = authFailures;
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
        // The QR link opens the phone app itself; it reads ?code= and shows the pairing form
        app.MapGet("/pair", (HttpContext context) => ServeFile(context, StaticFiles.Value["/index.html"]));

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

    // Phone UI: static files at their own paths, index.html also at "/"
    private void MapStatic(WebApplication app)
    {
        foreach (var (path, file) in StaticFiles.Value)
            app.MapGet(path, (HttpContext context) => ServeFile(context, file));
        app.MapGet("/", (HttpContext context) => ServeFile(context, StaticFiles.Value["/index.html"]));
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

    /// <summary>Phone UI files embedded from Remote/ (LogicalName "Remote/&lt;path&gt;"), keyed by URL path.</summary>
    private static readonly Lazy<Dictionary<string, (byte[] Content, string ContentType)>> StaticFiles = new(() =>
    {
        const string prefix = "Remote/";
        var assembly = typeof(RemoteServer).Assembly;
        var files = new Dictionary<string, (byte[], string)>(StringComparer.Ordinal);
        foreach (var name in assembly.GetManifestResourceNames().Where(n => n.StartsWith(prefix, StringComparison.Ordinal)))
        {
            using var stream = assembly.GetManifestResourceStream(name)!;
            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            var path = "/" + name[prefix.Length..].Replace('\\', '/');
            files[path] = (buffer.ToArray(), ContentTypeFor(path));
        }
        if (!files.ContainsKey("/index.html"))
            throw new InvalidOperationException("Embedded phone UI (Remote/index.html) not found.");
        return files;
    });

    private static string ContentTypeFor(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".html" => "text/html; charset=utf-8",
        ".js" => "text/javascript; charset=utf-8",
        ".css" => "text/css; charset=utf-8",
        ".webmanifest" => "application/manifest+json",
        ".svg" => "image/svg+xml",
        ".png" => "image/png",
        _ => "application/octet-stream"
    };

    private static IResult ServeFile(HttpContext context, (byte[] Content, string ContentType) file)
    {
        var headers = context.Response.Headers;
        headers.CacheControl = "no-cache"; // revalidate, so an app update reaches the phone
        headers.XContentTypeOptions = "nosniff";
        headers["Referrer-Policy"] = "no-referrer";
        if (file.ContentType.StartsWith("text/html", StringComparison.Ordinal))
        {
            // Only our own files; the WebSocket goes to the same host
            headers.ContentSecurityPolicy =
                $"default-src 'self'; connect-src 'self' ws://{context.Request.Host}; img-src 'self' data:; " +
                "style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
        }
        return Results.Bytes(file.Content, file.ContentType);
    }

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
