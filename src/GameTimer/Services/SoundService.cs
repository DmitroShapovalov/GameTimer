using System.IO;
using System.Media;
using System.Reflection;
using System.Windows.Media;

namespace GameTimer.Services;

public class SoundService
{
    private string? _customSoundPath;
    private string? _builtInSoundTempPath;

    public void SetCustomSound(string? path)
    {
        _customSoundPath = path;
    }

    public void PlayAlert()
    {
        try
        {
            if (!string.IsNullOrEmpty(_customSoundPath) && File.Exists(_customSoundPath))
            {
                PlayMediaFile(_customSoundPath);
            }
            else
            {
                PlayBuiltInSound();
            }
        }
        catch
        {
            SystemSounds.Exclamation.Play();
        }
    }

    private void PlayBuiltInSound()
    {
        // Extract embedded MP3 to temp file (MediaPlayer needs a file path)
        if (_builtInSoundTempPath == null || !File.Exists(_builtInSoundTempPath))
        {
            var assembly = Assembly.GetExecutingAssembly();
            var resourceName = "GameTimer.Resources.notification.mp3";
            using var stream = assembly.GetManifestResourceStream(resourceName);
            if (stream == null)
            {
                SystemSounds.Exclamation.Play();
                return;
            }

            _builtInSoundTempPath = Path.Combine(Path.GetTempPath(), "GameTimer_notification.mp3");
            using var fs = File.Create(_builtInSoundTempPath);
            stream.CopyTo(fs);
        }

        PlayMediaFile(_builtInSoundTempPath);
    }

    private void PlayMediaFile(string path)
    {
        var player = new MediaPlayer();
        player.Open(new Uri(path, UriKind.Absolute));
        player.MediaFailed += (_, _) => SystemSounds.Exclamation.Play();
        player.Play();
    }

    public void PlayPreview()
    {
        PlayAlert();
    }
}
