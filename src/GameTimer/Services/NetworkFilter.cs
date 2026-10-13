using System.Net;
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

    public static bool IsLoopback(IPAddress? ip)
    {
        if (ip == null) return false;
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        return IPAddress.IsLoopback(ip);
    }
}
