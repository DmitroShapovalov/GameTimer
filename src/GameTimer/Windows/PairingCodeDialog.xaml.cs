using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using GameTimer.Localization;
using Wpf.Ui.Controls;

namespace GameTimer.Windows;

/// <summary>
/// Protocol v2 pairing (§8.2 step 5–6): the owner types the 6-digit code the phone shows, then
/// confirms with Windows Hello. Typing forces a real comparison, which glancing at two numbers doesn't.
/// The pairing host closes the dialog when its 60-second window ends or the attempt is aborted.
/// </summary>
public partial class PairingCodeDialog : FluentWindow
{
    private readonly Func<Task<bool>> _authenticate;
    private readonly TaskCompletionSource<string?> _result = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private bool _busy;

    public PairingCodeDialog(string hubName, Func<Task<bool>> authenticate)
    {
        InitializeComponent();
        _authenticate = authenticate;
        Title = Loc.Get("PairCode_Title");
        DialogTitleBar.Title = Loc.Get("PairCode_Title");
        PromptText.Text = string.Format(Loc.Get("PairCode_Prompt"), hubName);
        HintText.Text = Loc.Get("PairCode_Hint");
        ConfirmButton.Content = Loc.Get("PairCode_Confirm");
        CancelButton.Content = Loc.Get("Cancel");
        Closed += (_, _) => _result.TrySetResult(null);
        Loaded += (_, _) => CodeBox.Focus();
    }

    /// <summary>The typed code after Windows Hello, or null (cancelled, closed, auth failed).</summary>
    public Task<string?> Result => _result.Task;

    private void CodeBox_PreviewTextInput(object sender, TextCompositionEventArgs e) =>
        e.Handled = !e.Text.All(char.IsAsciiDigit);

    private void CodeBox_TextChanged(object sender, TextChangedEventArgs e) =>
        ConfirmButton.IsEnabled = CodeBox.Text.Length == 6 && CodeBox.Text.All(char.IsAsciiDigit);

    private async void Confirm_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        _busy = true;
        ConfirmButton.IsEnabled = false;
        var code = CodeBox.Text;
        try
        {
            if (!await _authenticate()) { _busy = false; ConfirmButton.IsEnabled = true; return; }
            _result.TrySetResult(code);
            Close();
        }
        catch
        {
            _result.TrySetResult(null);
            Close();
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();
}
