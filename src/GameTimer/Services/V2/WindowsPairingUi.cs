using GameTimer.Protocol.Agent;

namespace GameTimer.Services.V2;

/// <summary>
/// The PC side of v2 pairing (protocol §8.2): shows "'{name}' wants to connect — enter the code shown
/// on your phone", then Windows Hello. The dialog and the auth check are supplied by the main window.
/// </summary>
public sealed class WindowsPairingUi : IPairingUi
{
    private readonly UiThread _ui;

    public WindowsPairingUi(UiThread ui) => _ui = ui;

    /// <summary>Set by the main window: shows the code dialog (UI thread) and returns the typed code, or null.</summary>
    public Func<string, CancellationToken, Task<string?>>? AskForCode { get; set; }

    /// <summary>Raised on the UI thread when a pairing was aborted (e.g. two phones used one QR).</summary>
    public event Action<PairingAbortReason>? Aborted;

    public async Task<string?> AskForCodeAsync(string hubName, CancellationToken cancel)
    {
        var ask = AskForCode;
        if (ask == null) return null;
        var task = await _ui.InvokeAsync(() => ask(hubName, cancel)).ConfigureAwait(false);
        return await task.ConfigureAwait(false);
    }

    public void PairingAborted(PairingAbortReason reason) => _ui.Post(() => Aborted?.Invoke(reason));
}
