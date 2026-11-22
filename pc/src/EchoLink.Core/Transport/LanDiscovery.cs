using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace EchoLink.Core.Transport;

/// <summary>局域网里发现到的一台手机。</summary>
public sealed record DiscoveredPhone(IPAddress Address, string? Name, string Via);

/// <summary>
/// 局域网（Wi-Fi）手机发现。
///
/// 做法：往本机所有 IPv4 网段的**广播地址**发一个探测包，手机收到后单播应答。
/// 不用 mDNS/SSDP 是因为那些都要额外依赖，而且同样受 Windows 防火墙限制；
/// 我们自己已经有 UDP 端口和包头，加一个 stream 类型最省。
///
/// 注意：这里的"发现"是**尽力而为**的。广播探测的回包在 PC 看来是入站报文，
/// Windows 防火墙默认会拦（除非应用被放行）。所以：
///   - 发现成功 → 直接连上，用户不用填 IP
///   - 发现失败 → 不报错，退回手动填 IP（手机界面会显示自己的地址）
/// </summary>
public static class LanDiscovery
{
    /// <summary>
    /// 枚举要发探测的广播地址：每个在用 IPv4 网卡算一个定向广播地址，
    /// 再加一个 255.255.255.255 兜底（有些网卡/驱动只认后者）。
    /// </summary>
    public static IReadOnlyList<IPEndPoint> BroadcastTargets(int port)
    {
        var targets = new List<IPEndPoint>();

        foreach (var nic in SafeGetInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up ||
                nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            IPAddress? address = null;
            IPAddress? mask = null;
            try
            {
                foreach (var info in nic.GetIPProperties().UnicastAddresses)
                {
                    if (info.Address.AddressFamily == AddressFamily.InterNetwork &&
                        info.IPv4Mask is not null &&
                        !IPAddress.IsLoopback(info.Address))
                    {
                        address = info.Address;
                        mask = info.IPv4Mask;
                        break;
                    }
                }
            }
            catch (NetworkInformationException)
            {
                continue;
            }

            if (address is null || mask is null)
            {
                continue;
            }

            var broadcast = ComputeBroadcast(address, mask);
            if (broadcast is not null)
            {
                targets.Add(new IPEndPoint(broadcast, port));
            }
        }

        // 兜底：有些环境（VPN、虚拟网卡混在一起）定向广播发不出去
        var all = new IPEndPoint(IPAddress.Broadcast, port);
        if (!targets.Any(t => t.Address.Equals(all.Address)))
        {
            targets.Add(all);
        }

        return targets;
    }

    /// <summary>地址 | ~掩码 = 广播地址。返回 null 表示这个网段不值得发（点对点/掩码异常）。</summary>
    public static IPAddress? ComputeBroadcast(IPAddress address, IPAddress mask)
    {
        var a = address.GetAddressBytes();
        var m = mask.GetAddressBytes();
        if (a.Length != 4 || m.Length != 4)
        {
            return null;
        }

        var result = new byte[4];
        var allOnes = true;
        for (var i = 0; i < 4; i++)
        {
            result[i] = (byte)(a[i] | (byte)~m[i]);
            if (result[i] != 0xFF)
            {
                allOnes = false;
            }
        }

        // /32 掩码（点对点、部分 VPN）算出来的"广播地址"就是自己，没有意义
        if (allOnes && mask.GetAddressBytes().All(b => b == 0xFF))
        {
            return null;
        }

        if (result.SequenceEqual(a))
        {
            return null;
        }

        return new IPAddress(result);
    }

    /// <summary>
    /// 跑一轮发现：发探测 → 等 <paramref name="timeoutMs"/> 收集应答。
    /// 同一个 IP 只保留第一次应答（重复应答会重复触发发送探测）。
    /// </summary>
    public static IReadOnlyList<DiscoveredPhone> Discover(
        UdpAudioTransport transport,
        int port,
        int timeoutMs = 900)
    {
        var found = new Dictionary<string, DiscoveredPhone>();
        var nonce = (uint)Random.Shared.Next(1, int.MaxValue);

        void OnReply(IPEndPoint source, string? name)
        {
            var key = source.Address.ToString();
            lock (found)
            {
                found[key] = new DiscoveredPhone(source.Address, name, "局域网广播");
            }
        }

        transport.DiscoveryReplied += OnReply;
        try
        {
            var targets = BroadcastTargets(port);
            // 发两轮：UDP 广播本身不可靠，第一轮经常只是把网卡的 ARP 缓存叫醒
            for (var round = 0; round < 2; round++)
            {
                foreach (var target in targets)
                {
                    transport.SendDiscoverProbe(target, nonce);
                }

                Thread.Sleep(timeoutMs / 2);
            }

            Thread.Sleep(80);
            lock (found)
            {
                return found.Values.OrderBy(p => p.Address.ToString()).ToList();
            }
        }
        finally
        {
            transport.DiscoveryReplied -= OnReply;
        }
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
}
