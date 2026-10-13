using System.Text.Json.Serialization;
using GameTimer.Localization;

namespace GameTimer.Models;

/// <summary>A phone paired with this PC. Only a hash of its token is stored, never the token itself.</summary>
public class PairedDevice
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";

    /// <summary>Base64 SHA-256 of the device token.</summary>
    public string TokenHash { get; set; } = "";

    public DateTime Created { get; set; }   // UTC
    public DateTime LastSeen { get; set; }  // UTC

    [JsonIgnore]
    public string LastSeenText => string.Format(Loc.Get("Device_LastSeen"), LastSeen.ToLocalTime().ToString("g"));
}
