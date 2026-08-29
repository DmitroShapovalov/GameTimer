using System.Windows;
using Wpf.Ui.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using GameTimer.Helpers;
using GameTimer.Localization;

namespace GameTimer.Windows;

public partial class PasswordDialog : FluentWindow
{
    private DispatcherTimer? _lockoutTimer;

    public PasswordDialog()
    {
        InitializeComponent();
        ApplyLocalization();
        Loaded += (_, _) => PasswordInput.Focus();
        CheckLockout();
    }

    private void ApplyLocalization()
    {
        Title = Loc.Get("PasswordTitle");
        DialogTitleBar.Title = Loc.Get("PasswordTitle");
        PromptText.Text = Loc.Get("EnterWindowsPassword");
        CancelButton.Content = Loc.CurrentLanguage == "ru" ? "Отмена" : "Cancel";
    }

    private void Ok_Click(object sender, RoutedEventArgs e) => TryValidate();

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void PasswordInput_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
            TryValidate();
    }

    private void TryValidate()
    {
        if (PasswordHelper.IsLockedOut)
        {
            CheckLockout();
            return;
        }

        if (PasswordHelper.Validate(PasswordInput.Password))
        {
            DialogResult = true;
            Close();
        }
        else
        {
            PasswordInput.Clear();

            if (PasswordHelper.IsLockedOut)
            {
                CheckLockout();
                StartLockoutTimer();
            }
            else
            {
                var msg = Loc.CurrentLanguage == "ru" ? "Неверный пароль" : "Wrong password";
                ShowError(msg);
            }
        }
    }

    private void CheckLockout()
    {
        if (PasswordHelper.IsLockedOut)
        {
            var seconds = (int)PasswordHelper.LockoutRemaining.TotalSeconds;
            var msg = Loc.CurrentLanguage == "ru"
                ? $"Слишком много попыток. Подождите {seconds} сек."
                : $"Too many attempts. Wait {seconds} sec.";
            ShowError(msg);
            PasswordInput.IsEnabled = false;
        }
        else
        {
            PasswordInput.IsEnabled = true;
        }
    }

    private void StartLockoutTimer()
    {
        _lockoutTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _lockoutTimer.Tick += (_, _) =>
        {
            if (!PasswordHelper.IsLockedOut)
            {
                _lockoutTimer.Stop();
                ErrorText.Visibility = Visibility.Collapsed;
                PasswordInput.IsEnabled = true;
                PasswordInput.Focus();
            }
            else
            {
                CheckLockout();
            }
        };
        _lockoutTimer.Start();
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }
}
