using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace EchoLink.Core.Transport;

public sealed record TargetCandidate(
    string AdapterName,
    string Description,
    IPAddress? LocalAddress,
    IPAddress? Gateway,
    int Score,
    string Reason)
{
    public bool IsUsable => Gateway is not null || LocalAddress is not null;

    public override string ToString()
        => $"{AdapterName} ({Description}) → {Gateway?.ToString() ?? LocalAddress?.ToString() ?? "-"} [{Reason}]";
}

/// <summary>
/// 手机地址自动发现：取 USB 网络共享（RNDIS/NCM）适配器的默认网关，不写死 IP、不区分 RNDIS/NCM。
/// </summary>
/// <remarks>
/// 判断依据来自 <see cref="NetworkInterface.GetIPProperties"/> 的网关地址——安卓 USB 共享时手机就是该网段的网关。
/// Wi-Fi 通道也能被发现，但只是备用通道；评分会给它降权，UI 上也会标注“无线，延迟不承诺”。
/// </remarks>
public static class TargetDiscovery
{
    private static readonly string[] TetherHints =
    {
        "rndis", "ndis", "ncm", "usb ethernet", "usb-ethernet", "gadget", "tether", "android",
        "remote ndis", "mobile device", "手机",
    };

    private static readonly string[] VirtualHints =
    {
        "virtual", "vmware", "virtualbox", "hyper-v", "vethernet", "loopback", "bluetooth", "wsl",
        "tap-", "tun", "wan miniport", "npcap", "vpn", "zerotier", "tailscale", "docker", "default switch",
    };

    public static IReadOnlyList<TargetCandidate> DiscoverCandidates()
    {
        var candidates = new List<TargetCandidate>();

        foreach (var nic in SafeGetInterfaces())
        {
            NetworkInterfaceProperties props;
            try
            {
                props = new NetworkInterfaceProperties(nic);
            }
            catch (NetworkInformationException)
            {
                continue;
            }
            catch (PlatformNotSupportedException)
            {
                continue;
            }

            if (nic.OperationalStatus != OperationalStatus.Up || props.IPv4Address is null)
            {
                continue;
            }

            var (score, reason) = ScoreAdapter(nic.Name, nic.Description, nic.NetworkInterfaceType, props.Gateway is not null);
            if (score < 0)
            {
                continue;
            }

            candidates.Add(new TargetCandidate(
                nic.Name,
                nic.Description,
                props.IPv4Address,
                props.Gateway,
                score,
                reason));
        }

        return candidates
            .OrderByDescending(c => c.Score)
            .ThenBy(c => c.AdapterName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    /// <summary>
    /// 纯函数评分：(得分, 原因)。&lt;0 表示排除。抽成纯函数是为了能在没有真实网卡的机器上做单元测试。
    /// </summary>
    public static (int Score, string Reason) ScoreAdapter(string name, string description, NetworkInterfaceType type, bool hasGateway)
    {
        var haystack = $"{name} {description}".ToLowerInvariant();

        foreach (var hint in VirtualHints)
        {
            if (haystack.Contains(hint, StringComparison.Ordinal))
            {
                return (-1, $"虚拟/无关适配器 ({hint})");
            }
        }

        var tethered = TetherHints.Any(hint => haystack.Contains(hint, StringComparison.Ordinal));
        var score = 0;
        var reasons = new List<string>(3);

        if (tethered)
        {
            score += 60;
            reasons.Add("USB 网络共享");
        }

        if (hasGateway)
        {
            score += 25;
            reasons.Add("有默认网关");
        }
        else
        {
            // 没有网关说明不是“手机做网关”的那种拓扑，只能作为候选（可能是手动静态配置的链路）
            score -= 5;
            reasons.Add("无网关");
        }

        if (type == NetworkInterfaceType.Ethernet)
        {
            score += 10;
            reasons.Add("以太网类");
        }
        else if (type is NetworkInterfaceType.Wireless80211)
        {
            score -= 10;
            reasons.Add("无线（备用通道，延迟不承诺）");
        }

        if (!tethered && !hasGateway)
        {
            score -= 20;
        }

        return (score, string.Join(" + ", reasons));
    }

    private static IEnumerable<NetworkInterface> SafeGetInterfaces()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            return Array.Empty<NetworkInterface>();
        }
    }

    private sealed class NetworkInterfaceProperties
    {
        public NetworkInterfaceProperties(NetworkInterface nic)
        {
            var ipProps = nic.GetIPProperties();

            foreach (var unicast in ipProps.UnicastAddresses)
            {
                if (unicast.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(unicast.Address))
                {
                    IPv4Address = unicast.Address;
                    break;
                }
            }

            foreach (var gateway in ipProps.GatewayAddresses)
            {
                if (gateway.Address.AddressFamily == AddressFamily.InterNetwork && !gateway.Address.Equals(IPAddress.Any))
                {
                    Gateway = gateway.Address;
                    break;
                }
            }
        }

        public IPAddress? IPv4Address { get; }

        public IPAddress? Gateway { get; }
    }
}
