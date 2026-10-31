using System.Text.Json.Nodes;
using GameTimer.Protocol.Relay;

namespace GameTimer.Services;

/// <summary>
/// "Control from anywhere" (protocol §10): while the server runs, the setting is on and a parent app is
/// paired, <see cref="RelayClient"/> keeps the PC reachable through the relay. Off by default; turned on
/// by the owner behind Windows Hello. Turning it off ends every relayed session with bye remote_off.
/// </summary>
public sealed partial class RemoteServer
{
    private RelayClient? _relay;
    private readonly SemaphoreSlim _relayGate = new(1, 1);

    /// <summary>The relay connection (Off while the feature is off, the server is stopped or nothing is paired).</summary>
    public RelayStatus RelayStatus => _relay?.Status ?? RelayStatus.Off;

    /// <summary>The relay status changed (any thread).</summary>
    public event Action? RelayStatusChanged;

    private Uri RelayUrl =>
        Uri.TryCreate(_settings.RelayUrl, UriKind.Absolute, out var u) && u.Scheme == "wss" ? u : RelayClient.DefaultUrl;

    /// <summary>Called once from InitV2.</summary>
    private void InitRelay()
    {
        Agent.RemoteAccess = _settings.RemoteAccess;
        Agent.PeersChanged += () => _ = UpdateRelayAsync();
    }

    /// <summary>
    /// The owner turned "Control from anywhere" on or off (after Windows Hello; UI thread). Saved, reported
    /// to the parent apps (§7.2 settings, a new rev), and the relay connection follows.
    /// </summary>
    public async Task SetRemoteAccessAsync(bool on)
    {
        if (_settings.RemoteAccess == on) return;
        _settings.RemoteAccess = on;
        _settingsService.Save(_settings);
        Agent.RemoteAccess = on; // off: the agent takes no new relayed stream from here on
        // §7.2: a new rev either way (a parent app may cache settings by rev); ev.settings while running
        if (_app != null) V2SettingsChanged(settings: true, schedule: false);
        else _v2Backend!.SettingsRev++;
        if (!on) await Agent.EndRelaySessionsAsync(); // §10.2: bye remote_off is out before the relay stops; LAN stays
        await UpdateRelayAsync();
    }

    /// <summary>Starts or stops the relay connection to match the server, the setting and the paired apps.</summary>
    private async Task UpdateRelayAsync()
    {
        await _relayGate.WaitAsync();
        try
        {
            bool want = _app != null && _settings.RemoteAccess && Agent.Peers.Count > 0;
            if (want && _relay == null)
            {
                var relay = new RelayClient(Agent, RelayUrl);
                relay.StatusChanged += _ => RelayStatusChanged?.Invoke();
                _relay = relay;
                relay.Start();
                DiagnosticLog.Write("Relay: connecting");
            }
            else if (!want && _relay != null)
            {
                var relay = _relay;
                _relay = null;
                await relay.StopAsync();
                DiagnosticLog.Write("Relay: stopped");
            }
        }
        catch (Exception ex) { DiagnosticLog.Write("Relay: " + ex.Message); }
        finally { _relayGate.Release(); }
        RelayStatusChanged?.Invoke();
    }

    /// <summary>The v2 settings object carries the read-only remoteAccess (§7.2).</summary>
    private void AddRemoteAccess(JsonObject settings) => settings["remoteAccess"] = _settings.RemoteAccess;
}
