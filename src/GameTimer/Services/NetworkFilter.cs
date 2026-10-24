using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace GameTimer.Services;

/// <summary>
/// Decides which remote addresses may talk to the embedded server: this PC and the home LAN only.
/// The server listens on all interfaces (so a new DHCP address doesn't break it) and relies on this filter.
/// A VPN option (e.g. Tailscale, 100.64.0.0/10) would be added here if ever supported.
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
    public static IPAddress? GetLanAddress() => GetLanInterface()?.Address;

    /// <summary>Like <see cref="GetLanAddress"/>, with that interface's subnet mask (for <see cref="IsInSubnet"/>).</summary>
    public static (IPAddress Address, IPAddress Mask)? GetLanInterface() =>
        BestLan() is { } b ? (b.Address, b.Mask) : null;

    private static (IPAddress Address, IPAddress Mask, IPAddress? Gateway)? BestLan()
    {
        try
        {
            var best = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                            && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                            && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
                .Select(n => n.GetIPProperties())
                .SelectMany(p => p.UnicastAddresses
                    .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork
                                && !IPAddress.IsLoopback(a.Address)
                                && !IsLinkLocalV4(a.Address)
                                && IsAllowed(a.Address))
                    .Select(a => (Info: a, Gateway: p.GatewayAddresses
                        .Select(g => g.Address)
                        .FirstOrDefault(g => g.AddressFamily == AddressFamily.InterNetwork && !g.Equals(IPAddress.Any)))))
                .OrderByDescending(x => x.Gateway != null)
                .FirstOrDefault();
            if (best.Info == null) return null;
            var mask = best.Info.IPv4Mask is { } m && !m.Equals(IPAddress.Any) ? m : IPAddress.Parse("255.255.255.0");
            return (best.Info.Address, mask, best.Gateway);
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    /// <summary>
    /// An opaque id of the network the PC is on now, for "announce discovery only where a parent app
    /// already connected" (protocol §9): a hash of the default gateway's MAC address and the subnet, so
    /// another network with the same 192.168.1.x numbers is still a different network. Null when not
    /// connected or the gateway's MAC can't be read (no gateway, ARP failed): then the network counts
    /// as unknown — never a weaker id that other networks could share. Not cached: Windows answers
    /// from its ARP cache, so a network change is seen at once.
    /// </summary>
    public static string? GetNetworkId()
    {
        var lan = BestLan();
        if (lan == null) return null;
        var (address, mask, gateway) = lan.Value;
        var net = new byte[4];
        var a = address.GetAddressBytes();
        var m = mask.GetAddressBytes();
        for (int i = 0; i < 4; i++) net[i] = (byte)(a[i] & m[i]);
        var subnet = $"{new IPAddress(net)}/{mask}";

        var mac = gateway == null ? null : GatewayMac(gateway);
        if (mac == null) return null;
        var input = $"GameTimer network v1|{subnet}|{mac}";
        var hash = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(input));
        return Convert.ToHexString(hash, 0, 16);
    }

    private static string? GatewayMac(IPAddress gateway)
    {
        try
        {
            var mac = new byte[6];
            uint len = (uint)mac.Length;
            uint dest = BitConverter.ToUInt32(gateway.GetAddressBytes(), 0);
            if (SendARP(dest, 0, mac, ref len) != 0 || len != 6 || mac.All(b => b == 0)) return null;
            return Convert.ToHexString(mac);
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }

    [System.Runtime.InteropServices.DllImport("iphlpapi.dll", ExactSpelling = true)]
    private static extern int SendARP(uint destIp, uint srcIp, byte[] macAddr, ref uint physicalAddrLength);

    /// <summary>True if <paramref name="ip"/> (IPv4) is in the subnet of <paramref name="lan"/>.</summary>
    public static bool IsInSubnet(IPAddress? ip, (IPAddress Address, IPAddress Mask)? lan)
    {
        if (ip == null || lan == null) return false;
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var a = ip.GetAddressBytes();
        var b = lan.Value.Address.GetAddressBytes();
        var m = lan.Value.Mask.GetAddressBytes();
        for (int i = 0; i < 4; i++)
            if ((a[i] & m[i]) != (b[i] & m[i])) return false;
        return true;
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
