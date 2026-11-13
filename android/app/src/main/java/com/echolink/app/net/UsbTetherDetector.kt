package com.echolink.app.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 检测手机是否已经通过 USB 接到电脑并开启了网络共享。
 *
 * **不要用 ConnectivityManager.allNetworks**：那里只有手机的**上行**网络（WiFi / 蜂窝）。
 * USB 网络共享时，`rndis0` / `usb0` 是手机**给电脑**建的那条局域网，它属于 Tethering
 * 子系统，不在 allNetworks 里。用错这一点会让引导页永远等不到"已连接"。
 *
 * 正确做法是枚举系统网卡（`NetworkInterface`）。
 *
 * 判据优先级：
 * 1. 网卡名以 `rndis` / `usb` 开头 —— 绝大多数机型的 USB 网卡命名；
 * 2. 兜底：**不属于任何已知上行类型**（wlan / rmnet / ccmni…）、处于启用状态、
 *    且拿到私有 IPv4 地址的网卡 —— 那些 OEM 自创命名的 tether 网卡靠这条兜住。
 */
object UsbTetherDetector {

    private val UsbPrefixes = listOf("rndis", "usb")

    /**
     * 已知的"非 USB"网卡名前缀：WiFi、蜂窝、热点、隧道、回环等。
     * 兜底判据要靠它排除掉正常的手机上网网卡。
     */
    private val KnownNonUsbPrefixes = listOf(
        "wlan", "ap", "p2p", "rmnet", "ccmni", "clat", "dummy", "sit", "ip6tnl",
        "tun", "tap", "ppp", "wwan", "v4-", "bridge", "lo",
    )

    /** 一段网卡信息，用于界面显示与排查。 */
    data class InterfaceInfo(
        val name: String,
        val ipv4: String?,
        val isUsb: Boolean,
    )

    /** 纯函数：按名字判断是不是 USB 网卡。抽出来是为了能单测。 */
    fun isUsbInterfaceName(name: String?): Boolean {
        if (name.isNullOrBlank()) {
            return false
        }

        return UsbPrefixes.any { name.startsWith(it, ignoreCase = true) }
    }

    /**
     * 纯函数：兜底判据——名字不认识、但看着就是一条"给电脑用的"网卡。
     * 只看 IPv4 私有地址 + 不是已知的 WiFi/蜂窝接口名。
     */
    fun isLikelyTetherInterface(name: String?, isLoopback: Boolean, isUp: Boolean, ipv4: String?): Boolean {
        if (name.isNullOrBlank() || isLoopback || !isUp || ipv4.isNullOrBlank()) {
            return false
        }

        val lower = name.lowercase()
        if (KnownNonUsbPrefixes.any { lower.startsWith(it) }) {
            return false
        }

        return isPrivateIpv4(ipv4)
    }

    private fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) {
            return false
        }

        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false

        return when (first) {
            10 -> true
            172 -> second in 16..31
            192 -> second == 168
            else -> false
        }
    }

    /** 枚举所有启用的网卡（含名字与 IPv4），供界面显示与排查。 */
    fun listInterfaces(): List<InterfaceInfo> {
        val result = mutableListOf<InterfaceInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nic in interfaces) {
                val up = try {
                    nic.isUp
                } catch (_: Throwable) {
                    false
                }

                if (!up) {
                    continue
                }

                val ipv4 = try {
                    nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress }
                        ?.hostAddress
                } catch (_: Throwable) {
                    null
                }

                result.add(
                    InterfaceInfo(
                        name = nic.name,
                        ipv4 = ipv4,
                        isUsb = isUsbInterfaceName(nic.name),
                    ),
                )
            }
        } catch (_: Throwable) {
            // 枚举失败就当没找到，交给 UI 提示
        }

        return result
    }

    fun isConnected(): Boolean {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        try {
            for (nic in interfaces) {
                val up = try {
                    nic.isUp
                } catch (_: Throwable) {
                    false
                }

                if (!up) {
                    continue
                }

                if (isUsbInterfaceName(nic.name)) {
                    return true
                }

                val ipv4 = try {
                    nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress }
                        ?.hostAddress
                } catch (_: Throwable) {
                    null
                }

                val loopback = try {
                    nic.isLoopback
                } catch (_: Throwable) {
                    false
                }

                if (isLikelyTetherInterface(nic.name, loopback, up, ipv4)) {
                    return true
                }
            }
        } catch (_: Throwable) {
            return false
        }

        return false
    }

    /** 命中的那个网卡名（排查用）。 */
    fun matchedInterfaceName(): String? {
        val interfaces = listInterfaces()
        return interfaces.firstOrNull { it.isUsb }?.name
            ?: interfaces.firstOrNull {
                isLikelyTetherInterface(it.name, false, true, it.ipv4)
            }?.name
    }
}
