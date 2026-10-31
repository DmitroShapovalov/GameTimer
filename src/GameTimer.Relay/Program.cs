using GameTimer.Relay;

// Request paths contain route tokens: nothing below Warning is logged for ASP.NET Core
// (no "Request starting …" lines), see appsettings.json and docs/protocol.md §10.5.
var builder = WebApplication.CreateBuilder(args);
RelayApp.ConfigureServices(builder);
var app = builder.Build();
RelayApp.Map(app);
app.Run();
