using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Microsoft.Extensions.Options;

namespace GameTimer.Relay;

/// <summary>Sends a content-free wake-up to a phone (§10.6). Never blocks the caller.</summary>
public interface IPushSender
{
    /// <summary>False when it can't take more right now (or isn't configured); the wake-up is dropped.</summary>
    bool TryWake(string pushToken);
}

/// <summary>
/// Firebase Cloud Messaging (HTTP v1) with the service account from <c>Relay:FcmServiceAccountB64</c>
/// (base64 of the key file; a Fly secret). The message is a data message <c>{ "t": "wake" }</c> with
/// high priority, a 10-minute TTL and one collapse key — nothing about the family, the PC or the event.
/// Tokens are used for the one call and never stored or logged. Not configured → every wake-up is dropped.
/// </summary>
internal sealed class FcmSender : IPushSender, IHostedService, IDisposable
{
    private const int QueueLimit = 1000;
    private const int Parallel = 4;
    private readonly Channel<string> _queue = Channel.CreateBounded<string>(new BoundedChannelOptions(QueueLimit)
    {
        FullMode = BoundedChannelFullMode.DropWrite,
    });
    private readonly HttpClient _http;
    private readonly ILogger<FcmSender> _log;
    private readonly Account? _account;
    private readonly SemaphoreSlim _tokenLock = new(1, 1);
    private string? _accessToken;
    private DateTimeOffset _accessTokenExpires;
    private readonly CancellationTokenSource _stop = new();
    private Task[] _workers = Array.Empty<Task>();
    private long _sent, _failed;

    private sealed record Account(string ProjectId, string ClientEmail, string PrivateKeyPem, string TokenUri);

    public FcmSender(IOptions<RelayOptions> options, ILogger<FcmSender> log) : this(options, log, null) { }

    /// <summary>Tests: a handler instead of the network.</summary>
    internal FcmSender(IOptions<RelayOptions> options, ILogger<FcmSender> log, HttpMessageHandler? handler)
    {
        _log = log;
        _http = handler != null ? new HttpClient(handler)
            : new HttpClient(new SocketsHttpHandler { PooledConnectionLifetime = TimeSpan.FromMinutes(5) });
        _http.Timeout = TimeSpan.FromSeconds(15);
        _account = Parse(options.Value.FcmServiceAccountB64, log);
    }

    public bool Configured => _account != null;

    public bool TryWake(string pushToken) =>
        _account != null && !IsDead(pushToken) && _queue.Writer.TryWrite(pushToken);

    // Tokens FCM said don't exist (any more): never sent again (by hash; bounded, forgotten when full)
    private readonly HashSet<string> _dead = new(StringComparer.Ordinal);
    private const int MaxDead = 50_000;

    private static string Hash(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(token)), 0, 16);

    private bool IsDead(string token)
    {
        lock (_dead) return _dead.Contains(Hash(token));
    }

    private void MarkDead(string token)
    {
        lock (_dead)
        {
            if (_dead.Count >= MaxDead) _dead.Clear();
            _dead.Add(Hash(token));
        }
    }

    private static Account? Parse(string? b64, ILogger log)
    {
        if (string.IsNullOrWhiteSpace(b64)) return null;
        try
        {
            var json = JsonNode.Parse(Convert.FromBase64String(b64.Trim()))!;
            var a = new Account(
                json["project_id"]!.GetValue<string>(),
                json["client_email"]!.GetValue<string>(),
                json["private_key"]!.GetValue<string>(),
                json["token_uri"]?.GetValue<string>() ?? "https://oauth2.googleapis.com/token");
            using var rsa = RSA.Create();
            rsa.ImportFromPem(a.PrivateKeyPem); // fail at start, not at the first wake-up
            return a;
        }
        catch (Exception e)
        {
            log.LogError("Push: the FCM service account can't be read ({Type}); wake-ups are off", e.GetType().Name);
            return null;
        }
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _log.LogInformation(_account != null ? "Push: FCM configured" : "Push: not configured, wake-ups are dropped");
        if (_account != null) _workers = Enumerable.Range(0, Parallel).Select(_ => Task.Run(WorkAsync)).ToArray();
        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        _queue.Writer.TryComplete();
        _stop.Cancel();
        try { await Task.WhenAll(_workers).WaitAsync(TimeSpan.FromSeconds(3), cancellationToken); } catch (Exception) { }
    }

    public void Dispose()
    {
        _http.Dispose();
        _stop.Dispose();
        _tokenLock.Dispose();
    }

    private async Task WorkAsync()
    {
        try
        {
            await foreach (var token in _queue.Reader.ReadAllAsync(_stop.Token))
            {
                try
                {
                    await SendAsync(token, _stop.Token);
                    Interlocked.Increment(ref _sent);
                }
                catch (OperationCanceledException) when (_stop.IsCancellationRequested) { return; }
                catch (Exception e)
                {
                    // Counted, never the token: an expired one (404 UNREGISTERED) is common and harmless
                    if (Interlocked.Increment(ref _failed) % 100 == 1) _log.LogWarning("Push: a wake-up failed ({Message})", e.Message);
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    public (long Sent, long Failed) Counts => (Interlocked.Read(ref _sent), Interlocked.Read(ref _failed));

    private async Task SendAsync(string token, CancellationToken cancel, bool retry = true)
    {
        var access = await AccessTokenAsync(cancel);
        var body = new JsonObject
        {
            ["message"] = new JsonObject
            {
                ["token"] = token,
                ["data"] = new JsonObject { ["t"] = "wake" },
                ["android"] = new JsonObject { ["priority"] = "HIGH", ["ttl"] = "600s", ["collapse_key"] = "wake" },
            },
        };
        using var req = new HttpRequestMessage(HttpMethod.Post,
            $"https://fcm.googleapis.com/v1/projects/{Uri.EscapeDataString(_account!.ProjectId)}/messages:send")
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
        using var resp = await _http.SendAsync(req, cancel);
        if (resp.IsSuccessStatusCode) return;
        var status = (int)resp.StatusCode;
        if (status == 401 && retry)
        {
            // The access token was revoked (e.g. a rotated key): get a new one, once
            await _tokenLock.WaitAsync(cancel);
            try { if (_accessToken == access) _accessToken = null; }
            finally { _tokenLock.Release(); }
            await SendAsync(token, cancel, retry: false);
            return;
        }
        // 404 UNREGISTERED: the token is gone; don't try it again (a 400 may be our own message's fault)
        if (status == 404) MarkDead(token);
        throw new HttpRequestException($"FCM answered {status}");
    }

    /// <summary>OAuth 2.0 for a service account: a signed JWT (RS256) exchanged for an hour-long access token.</summary>
    private async Task<string> AccessTokenAsync(CancellationToken cancel)
    {
        await _tokenLock.WaitAsync(cancel);
        try
        {
            if (_accessToken != null && DateTimeOffset.UtcNow < _accessTokenExpires) return _accessToken;
            var a = _account!;
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var header = B64(Encoding.UTF8.GetBytes("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"));
            var claims = B64(Encoding.UTF8.GetBytes(new JsonObject
            {
                ["iss"] = a.ClientEmail,
                ["scope"] = "https://www.googleapis.com/auth/firebase.messaging",
                ["aud"] = a.TokenUri,
                ["iat"] = now,
                ["exp"] = now + 3600,
            }.ToJsonString()));
            using var rsa = RSA.Create();
            rsa.ImportFromPem(a.PrivateKeyPem);
            var sig = B64(rsa.SignData(Encoding.ASCII.GetBytes(header + "." + claims), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
            using var resp = await _http.PostAsync(a.TokenUri, new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "urn:ietf:params:oauth:grant-type:jwt-bearer",
                ["assertion"] = header + "." + claims + "." + sig,
            }), cancel);
            if (!resp.IsSuccessStatusCode) throw new HttpRequestException($"OAuth answered {(int)resp.StatusCode}");
            using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(cancel));
            _accessToken = doc.RootElement.GetProperty("access_token").GetString();
            var seconds = doc.RootElement.TryGetProperty("expires_in", out var e) ? e.GetInt32() : 3600;
            _accessTokenExpires = DateTimeOffset.UtcNow.AddSeconds(seconds - 300);
            return _accessToken!;
        }
        finally { _tokenLock.Release(); }
    }
}
