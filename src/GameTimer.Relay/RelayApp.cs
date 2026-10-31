using System.Net.WebSockets;

namespace GameTimer.Relay;

/// <summary>Wiring and the two WebSocket endpoints of the relay (docs/protocol.md §10.2, §10.3).</summary>
public static class RelayApp
{
    public static void ConfigureServices(WebApplicationBuilder builder)
    {
        builder.WebHost.ConfigureKestrel(o => o.AddServerHeader = false);
        builder.Services.Configure<RelayOptions>(builder.Configuration.GetSection("Relay"));
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddSingleton<RelayState>();
        builder.Services.AddHostedService<Sweeper>();
    }

    public static void Map(WebApplication app)
    {
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
        var key = IpLimits.KeyOf(ctx, s.Options);
        if (!s.Limits.TryStart(key, agent: true)) { ctx.Response.StatusCode = 429; return; }
        if (!s.TryTakeSocket()) { ctx.Response.StatusCode = 503; return; }
        s.Limits.Opened(key, agent: true);
        try
        {
            using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
            await new AgentSession(s, ws, key).RunAsync();
        }
        finally
        {
            s.Limits.Closed(key, agent: true);
            s.ReleaseSocket();
        }
    }

    private static async Task HubAsync(HttpContext ctx)
    {
        var s = ctx.RequestServices.GetRequiredService<RelayState>();
        // §10.3: the first match of 403, 429, 404, 503 answers the upgrade
        if (ctx.Request.Headers.ContainsKey("Origin")) { ctx.Response.StatusCode = 403; return; }
        var key = IpLimits.KeyOf(ctx, s.Options);
        if (s.Limits.NotFoundBlocked(key) || !s.Limits.TryStart(key, agent: false)) { ctx.Response.StatusCode = 429; return; }
        var route = ctx.Request.RouteValues["route"] as string;
        var found = ctx.WebSockets.IsWebSocketRequest && IsCanonicalRoute(route) ? s.Registry.Find(route!) : null;
        if (found is not { } target)
        {
            s.Limits.CountNotFound(key);
            ctx.Response.StatusCode = 404;
            return;
        }
        if (!target.Agent.HasRoom || !s.TryTakeSocket()) { ctx.Response.StatusCode = 503; return; }
        s.Limits.Opened(key, agent: false);
        try
        {
            using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
            var stream = new HubStream(s, target.Agent, ws);
            if (!target.Agent.Open(stream, target.Route))
            {
                // The agent went away or filled up in between
                try { await ws.CloseAsync((WebSocketCloseStatus)1013, null, new CancellationTokenSource(s.Options.CloseEchoWait).Token); }
                catch (Exception) { }
                return;
            }
            await stream.RunAsync();
        }
        finally
        {
            s.Limits.Closed(key, agent: false);
            s.ReleaseSocket();
        }
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

/// <summary>Runs the periodic checks of every agent socket and its streams, and trims the per-IP windows.</summary>
internal sealed class Sweeper : BackgroundService
{
    private readonly RelayState _s;

    public Sweeper(RelayState state) => _s = state;

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        using var timer = new PeriodicTimer(_s.Options.Sweep);
        try
        {
            while (await timer.WaitForNextTickAsync(stop))
            {
                foreach (var agent in _s.Agents.Keys)
                {
                    try { agent.Tick(); }
                    catch (Exception) { /* one broken socket must not stop the others */ }
                }
                _s.Limits.Cleanup();
            }
        }
        catch (OperationCanceledException) { }
    }
}
