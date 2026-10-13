using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace GameTimer.Services;

/// <summary>
/// Embedded Kestrel host for the phone remote (Phase 1). Off by default; started and stopped
/// from the Settings tab. Listens on all interfaces, but <see cref="NetworkFilter"/> drops every
/// connection that does not come from this PC or the home LAN.
/// </summary>
public sealed class RemoteServer
{
    private static readonly TimeSpan StopTimeout = TimeSpan.FromSeconds(3);

    private WebApplication? _app;

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

            app = builder.Build();

            // LAN-only: drop anything from outside the home network without an answer
            app.Use(async (context, next) =>
            {
                if (!NetworkFilter.IsAllowed(context.Connection.RemoteIpAddress))
                {
                    context.Abort();
                    return;
                }
                await next(context);
            });

            // TEMPORARY (Phase 1, step 2 only): removed in step 3 together with the pairing / auth work.
            // Answers only to requests from this PC.
            app.MapGet("/health", (HttpContext context) =>
                NetworkFilter.IsLoopback(context.Connection.RemoteIpAddress)
                    ? Results.Text("ok")
                    : Results.NotFound());

            await app.StartAsync();

            _app = app;
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
}
