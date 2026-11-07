package com.echolink.app.audio

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本机地址展示（USB 共享时通常是 rndis0 / usb0，Wi-Fi 时是 wlan0）。
 * PC 端可以自动发现网关，也可以在这里照着手动填。
 */
object NetworkInfo {
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses
                    .toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { "${nic.name}  ${it.hostAddress}" }
            }
            .sorted()
    }.getOrDefault(emptyList())
}
