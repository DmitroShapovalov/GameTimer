using System.IO;
using System.Text.Json;
using GameTimer.Protocol.Agent;

namespace GameTimer.Services.V2;

/// <summary>
/// Protocol v2 state (the PC's key pair, paired parent apps, idempotency cache) in a DPAPI-protected
/// file next to the exe (protocol §4.4, decision D6). Losing it (deleted or tampered beyond the .bak)
/// means a new identity: every parent app has to pair again — never a silent downgrade.
/// </summary>
public sealed class AgentFileStore : IAgentStore
{
    private static readonly string StorePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "remote-v2.json");

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new System.Text.Json.Serialization.JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
    };

    public AgentState? Load()
    {
        try
        {
            // Never written in plaintext: a planted plaintext file is tampering, not a migration
            var json = ProtectedStore.Read(StorePath, allowLegacyPlaintext: false);
            return json == null ? null : JsonSerializer.Deserialize<AgentState>(json, Json);
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write("v2 store load failed: " + ex.Message);
            return null;
        }
    }

    public void Save(AgentState state) => ProtectedStore.Write(StorePath, JsonSerializer.Serialize(state, Json));
}
