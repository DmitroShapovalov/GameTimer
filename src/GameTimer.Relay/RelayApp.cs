using System.Net.WebSockets;
using GameTimer.Protocol.Identity;

namespace GameTimer.Relay;

/// <summary>Wiring and the two WebSocket endpoints of the relay (docs/protocol.md §10.2, §10.3).</summary>
public static class RelayApp
{
    public static void ConfigureServices(WebApplicationBuilder builder)
    {
        builder.WebHost.ConfigureKestrel(o => o.AddServerHeader = false);
        builder.Services.Configure<HostOptions>(o => o.ShutdownTimeout = TimeSpan.FromSeconds(8));
        builder.Services.Configure<RelayOptions>(builder.Configuration.GetSection("Relay"));
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddSingleton<FcmSender>();
        builder.Services.AddSingleton<IPushSender>(sp => sp.GetRequiredService<FcmSender>());
        builder.Services.AddHostedService(sp => sp.GetRequiredService<FcmSender>());
        builder.Services.AddSingleton<RelayState>();
        builder.Services.AddHostedService<Sweeper>();
    }

    public static void Map(WebApplication app)
    {
        // Fail at start, not at the first register, if the native crypto library can't be loaded
        RouteToken.Route(new byte[RouteToken.Size]);
        app.UseWebSockets();
        app.MapGet("/healthz", () => Results.Text("ok"));
        app.Map("/v2/agent", AgentAsync);
        app.Map("/v2/hub/{route}", HubAsync);
    }

    private static async Task AgentAsync(HttpContext ctx)
    {
        var s = ctx.RequestServices.GetRequiredService<RelayState>();
        if (!ctx.WebSockets.IsWebSocketRequest) { ctx.Response.StatusCode = 400; return; }
        if (ctx.Request.Headers.ContainsKey("Origin")) { ctx.Response.StatusCode = 403; return; }
        if (IpLimits.ClientOf(ctx, s.Options) is not { } client) { ctx.Response.StatusCode = 400; return; }
        if (!s.Limits.TryStart(client, agent: true)) { ctx.Response.StatusCode = 429; return; }
        try
        {
            if (s.Stopping || !s.TryTakeSocket(agent: true)) { ctx.Response.StatusCode = 503; return; }
            try
            {
                using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
                await new AgentSession(s, ws, client.Key).RunAsync();
            }
            finally { s.ReleaseSocket(agent: true); }
        }
        finally { s.Limits.Closed(client, agent: true); }
    }

    private static async Task HubAsync(HttpContext ctx)
    {
        var s = ctx.RequestServices.GetRequiredService<RelayState>();
        // §10.3: the first match of 403, 429, 404, 503 answers the upgrade
        if (ctx.Request.Headers.ContainsKey("Origin")) { ctx.Response.StatusCode = 403; return; }
        if (IpLimits.ClientOf(ctx, s.Options) is not { } client) { ctx.Response.StatusCode = 400; return; }
        if (s.Limits.NotFoundBlocked(client) || !s.Limits.TryStart(client, agent: false)) { ctx.Response.StatusCode = 429; return; }
        try
        {
            var route = ctx.Request.RouteValues["route"] as string;
            var found = ctx.WebSockets.IsWebSocketRequest && IsCanonicalRoute(route) ? s.Registry.Find(route!) : null;
            if (found is not { } target)
            {
                s.Limits.CountNotFound(client);
                ctx.Response.StatusCode = 404;
                return;
            }
            if (s.Stopping || !target.Agent.HasRoom || s.NearlyFull || !s.TryTakeSocket(agent: false)) { ctx.Response.StatusCode = 503; return; }
            try
            {
                using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
                var stream = new HubStream(s, target.Agent, ws);
                if (!target.Agent.Open(stream, target.Route))
                {
                    // The agent went away or filled up in between
                    using var cts = new CancellationTokenSource(s.Options.CloseEchoWait);
                    try { await ws.CloseAsync((WebSocketCloseStatus)1013, null, cts.Token); }
                    catch (Exception) { }
                    return;
                }
                await stream.RunAsync();
            }
            finally { s.ReleaseSocket(agent: false); }
        }
        finally { s.Limits.Closed(client, agent: false); }
    }

    /// <summary>26 characters of the uppercase base32 alphabet whose last 2 bits are zero (§10.1).</summary>
    internal static bool IsCanonicalRoute(string? route)
    {
        if (route is not { Length: 26 }) return false;
        foreach (var c in route)
            if (!(c is >= 'A' and <= 'Z' or >= '2' and <= '7')) return false;
        int last = route[25] >= 'A' ? route[25] - 'A' : route[25] - '2' + 26;
        return (last & 3) == 0;
    }
}

/// <summary>
/// Runs the periodic checks of every agent socket and its streams, trims the per-IP table, logs counts
/// (never addresses or routes), and closes agents with 1012 when the relay stops (a deploy).
/// </summary>
internal sealed class Sweeper : BackgroundService
{
    private readonly RelayState _s;

    public Sweeper(RelayState state) => _s = state;

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        using var timer = new PeriodicTimer(_s.Options.Sweep);
        var lastStats = _s.Time.GetTimestamp();
        try
        {
            while (await timer.WaitForNextTickAsync(stop))
            {
                foreach (var agent in _s.Agents.Keys)
                {
                    try { agent.Tick(); }
                    catch (Exception e) { _s.Unexpected(e); } // one broken socket must not stop the others
                }
                _s.Limits.Cleanup();
                if (_s.Time.GetElapsedTime(lastStats) >= _s.Options.StatsEvery)
                {
                    lastStats = _s.Time.GetTimestamp();
                    _s.Log.LogInformation("Relay: {Agents} agent sockets, {Hubs} hub sockets, {Routes} routes, {Buffered} bytes queued",
                        _s.AgentSockets, _s.HubSockets, _s.Registry.Count, _s.Buffered);
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        _s.Stopping = true;
        foreach (var agent in _s.Agents.Keys) agent.Close(1012); // service restart: agents reconnect
        for (int i = 0; i < 30 && !_s.Agents.IsEmpty; i++) await Task.Delay(100, CancellationToken.None);
        await base.StopAsync(cancellationToken);
    }
}
