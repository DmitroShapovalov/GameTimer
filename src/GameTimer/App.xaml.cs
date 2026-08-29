using System.Windows.Media;
using Wpf.Ui.Appearance;

namespace GameTimer;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        base.OnStartup(e);
        // Set Windows blue accent instead of system red
        ApplicationAccentColorManager.Apply(Color.FromRgb(0x00, 0x78, 0xD4));
    }
}
