using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace GameTimer.Services;

/// <summary>
/// Decides which remote addresses may talk to the embedded server: this PC and the home LAN only.
/// The server listens on all interfaces (so a new DHCP address doesn't break it) and relies on this filter.
/// Phase 2 (Tailscale) would add 100.64.0.0/10 here.
/// </summary>
public static class NetworkFilter
{
    public static bool IsAllowed(IPAddress? ip)
    {
        if (ip == null) return false;
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        if (IPAddress.IsLoopback(ip)) return true;

        if (ip.AddressFamily == AddressFamily.InterNetwork)
        {
            var b = ip.GetAddressBytes();
            return b[0] == 10                                // 10.0.0.0/8
                || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) // 172.16.0.0/12
                || (b[0] == 192 && b[1] == 168)              // 192.168.0.0/16
                || (b[0] == 169 && b[1] == 254);             // 169.254.0.0/16 link-local
        }

        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
            return ip.IsIPv6LinkLocal || ip.IsIPv6UniqueLocal; // fe80::/10, fc00::/7

        return false;
    }

    /// <summary>
    /// The PC's IPv4 address on the home LAN, for the pairing QR code. Prefers an interface with a
    /// default gateway, which skips virtual adapters (Hyper-V, WSL, VPN). Null if not connected.
    /// </summary>
    public static IPAddress? GetLanAddress()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                            && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                            && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
                .Select(n => n.GetIPProperties())
                .SelectMany(p => p.UnicastAddresses
                    .Select(a => a.Address)
                    .Where(a => a.AddressFamily == AddressFamily.InterNetwork
                                && !IPAddress.IsLoopback(a)
                                && !IsLinkLocalV4(a)
                                && IsAllowed(a))
                    .Select(a => (Address: a, HasGateway: p.GatewayAddresses.Any(g =>
                        g.Address.AddressFamily == AddressFamily.InterNetwork
                        && !g.Address.Equals(IPAddress.Any)))))
                .OrderByDescending(x => x.HasGateway)
                .Select(x => x.Address)
                .FirstOrDefault();
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    private static bool IsLinkLocalV4(IPAddress ip)
    {
        var b = ip.GetAddressBytes();
        return b[0] == 169 && b[1] == 254;
    }

    public static bool IsLoopback(IPAddress? ip)
    {
        if (ip == null) return false;
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        return IPAddress.IsLoopback(ip);
    }
}
