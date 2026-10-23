using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using GameTimer.Localization;
using GameTimer.Models;
using GameTimer.Protocol.Agent;
using GameTimer.Services;
using GameTimer.Services.V2;
using QRCoder;
using Wpf.Ui.Controls;

namespace GameTimer.Windows;

/// <summary>
/// Shows a QR code with a one-time pairing link to the PC's LAN address. The caller has already
/// checked Windows Hello. Closing the window cancels the code.
/// </summary>
public partial class ConnectPhoneDialog : FluentWindow
{
    private readonly PairingService _pairing;
    private readonly AgentHost _agent;
    private readonly WindowsPairingUi _pairingUi;
    private readonly DispatcherTimer _countdown;
    private bool _connected;
    private bool _aborted;

    public ConnectPhoneDialog(PairingService pairing, AgentHost agent, WindowsPairingUi pairingUi, string lanAddress, int port)
    {
        InitializeComponent();
        _pairing = pairing;
        _agent = agent;
        _pairingUi = pairingUi;


        Title = Loc.Get("ConnectPhone");
        DialogTitleBar.Title = Loc.Get("ConnectPhone");
        InstructionsText.Text = Loc.Get("ConnectPhone_Instructions");
        CloseButton.Content = Loc.Get("Close");

        // One QR, two independent secrets (protocol §8.2): the v1 code for the browser app in the query,
        // the v2 pairing secret for the native app in the fragment (browsers never send it to the server)
        var offer = _agent.StartPairing(Environment.MachineName);
        var url = $"http://{lanAddress}:{port}/pair?code={_pairing.Begin()}#{offer.QrFragment}";
        UrlText.Text = url;
        QrImage.Source = RenderQr(url);

        _pairing.Paired += OnPaired;
        _agent.Paired += OnV2Paired;
        _pairingUi.Aborted += OnPairingAborted;
        _countdown = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _countdown.Tick += (_, _) => UpdateCountdown();
        _countdown.Start();
        UpdateCountdown();

        Closed += (_, _) =>
        {
            _countdown.Stop();
            _pairing.Paired -= OnPaired;
            _pairing.Cancel();
            _agent.Paired -= OnV2Paired;
            _pairingUi.Aborted -= OnPairingAborted;
            _agent.CancelPairing();
        };
    }

    private void UpdateCountdown()
    {
        if (_connected || _aborted) return;

        var left = _pairing.Remaining;
        if (left <= TimeSpan.Zero)
        {
            _countdown.Stop();
            QrImage.Visibility = Visibility.Hidden;
            UrlText.Visibility = Visibility.Collapsed;
            StatusText.Text = Loc.Get("ConnectPhone_Expired");
            return;
        }

        StatusText.Text = string.Format(Loc.Get("ConnectPhone_ExpiresIn"), $"{(int)left.TotalMinutes}:{left.Seconds:D2}");
    }

    private void OnPaired(PairedDevice device)
    {
        _connected = true;
        _countdown.Stop();
        QrImage.Visibility = Visibility.Hidden;
        UrlText.Visibility = Visibility.Collapsed;
        ConnectedMark.Visibility = Visibility.Visible;
        StatusText.Text = string.Format(Loc.Get("ConnectPhone_Connected"), device.Name);
    }

    // v2: a parent app finished pairing (any thread)
    private void OnV2Paired(Peer added) => Dispatcher.BeginInvoke(() =>
    {
        if (_connected) return;
        _connected = true;
        _countdown.Stop();
        QrImage.Visibility = Visibility.Hidden;
        UrlText.Visibility = Visibility.Collapsed;
        ConnectedMark.Visibility = Visibility.Visible;
        StatusText.Text = string.Format(Loc.Get("ConnectPhone_Connected"), added.Name);
    });

    // v2: two phones used this QR — it may have been photographed; the pairing is cancelled
    private void OnPairingAborted(PairingAbortReason reason)
    {
        _aborted = true;
        _countdown.Stop();
        _pairing.Cancel();
        QrImage.Visibility = Visibility.Hidden;
        UrlText.Visibility = Visibility.Collapsed;
        StatusText.Text = Loc.Get("ConnectPhone_TwoPhones");
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private static BitmapImage RenderQr(string text)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10);

        var image = new BitmapImage();
        using var stream = new MemoryStream(png);
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }
}
