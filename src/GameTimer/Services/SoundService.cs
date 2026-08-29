using System.IO;
using System.Media;

namespace GameTimer.Services;

public class SoundService
{
    private string? _customSoundPath;

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
                using var player = new SoundPlayer(_customSoundPath);
                player.Play();
            }
            else
            {
                PlayBuiltInSound();
            }
        }
        catch
        {
            // Fallback to system beep
            SystemSounds.Exclamation.Play();
        }
    }

    private void PlayBuiltInSound()
    {
        // Generate a gentle notification sound programmatically
        var wavData = GenerateNotificationWav();
        using var ms = new MemoryStream(wavData);
        using var player = new SoundPlayer(ms);
        player.Play();
    }

    public void PlayPreview()
    {
        PlayAlert();
    }

    /// <summary>
    /// Generates a short, gentle two-tone notification WAV.
    /// Two sine waves: 600Hz then 800Hz, 150ms each, with fade.
    /// </summary>
    private static byte[] GenerateNotificationWav()
    {
        const int sampleRate = 44100;
        const int durationMs = 300; // total duration
        const int totalSamples = sampleRate * durationMs / 1000;
        const short volume = 8000; // gentle volume

        var samples = new short[totalSamples];
        int halfPoint = totalSamples / 2;

        for (int i = 0; i < totalSamples; i++)
        {
            double t = (double)i / sampleRate;

            // Two tones: 600Hz first half, 800Hz second half
            double freq = i < halfPoint ? 600.0 : 800.0;
            double sample = Math.Sin(2.0 * Math.PI * freq * t);

            // Fade in/out envelope
            double envelope;
            double progress = i < halfPoint
                ? (double)i / halfPoint
                : (double)(i - halfPoint) / (totalSamples - halfPoint);

            if (progress < 0.1)
                envelope = progress / 0.1; // fade in
            else if (progress > 0.8)
                envelope = (1.0 - progress) / 0.2; // fade out
            else
                envelope = 1.0;

            samples[i] = (short)(sample * volume * envelope);
        }

        return CreateWav(samples, sampleRate);
    }

    private static byte[] CreateWav(short[] samples, int sampleRate)
    {
        const int channels = 1;
        const int bitsPerSample = 16;
        int dataSize = samples.Length * 2;

        using var ms = new MemoryStream();
        using var writer = new BinaryWriter(ms);

        // RIFF header
        writer.Write("RIFF"u8);
        writer.Write(36 + dataSize);
        writer.Write("WAVE"u8);

        // fmt chunk
        writer.Write("fmt "u8);
        writer.Write(16); // chunk size
        writer.Write((short)1); // PCM
        writer.Write((short)channels);
        writer.Write(sampleRate);
        writer.Write(sampleRate * channels * bitsPerSample / 8); // byte rate
        writer.Write((short)(channels * bitsPerSample / 8)); // block align
        writer.Write((short)bitsPerSample);

        // data chunk
        writer.Write("data"u8);
        writer.Write(dataSize);
        foreach (var sample in samples)
            writer.Write(sample);

        return ms.ToArray();
    }
}
