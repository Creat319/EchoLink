package com.echolink.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 检测手机是否已经通过 USB 接到电脑上并开启了网络共享。
 *
 * 判据（两条任一成立即算已连接）：
 * 1. Android 12+ 的网络能力里带 `TRANSPORT_USB`；
 * 2. 活动网络的接口名以 `rndis` / `usb` 开头 —— 这是 USB 网卡在 Android 上的典型命名。
 *
 * 不去碰 `UsbManager`：手机作为**从设备**连到电脑时，UsbManager 里看不到主机，
 * 那条路在设备模式下永远返回空。
 */
object UsbTetherDetector {

    private val UsbInterfacePrefixes = listOf("rndis", "usb")

    /**
     * 接口名是否是 USB 网卡。
     * 抽成纯函数是为了能单测——这类"看着像就行"的判定最容易误伤（比如把 wlan0 判成 USB）。
     */
    fun isUsbInterfaceName(name: String?): Boolean {
        if (name.isNullOrBlank()) {
            return false
        }

        return UsbInterfacePrefixes.any { name.startsWith(it, ignoreCase = true) }
    }

    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        return try {
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                if (capabilities != null
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                ) {
                    return@any true
                }

                isUsbInterfaceName(manager.getLinkProperties(network)?.interfaceName)
            }
        } catch (_: Throwable) {
            // 权限被改、系统接口异常等情况一律当"没连上"，不让引导页崩
            false
        }
    }

    /** 尽量给用户看出是哪块网卡（排查用），拿不到就返回 null。 */
    fun connectedInterfaceName(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        return try {
            manager.allNetworks
                .mapNotNull { manager.getLinkProperties(it)?.interfaceName }
                .firstOrNull { isUsbInterfaceName(it) }
        } catch (_: Throwable) {
            null
        }
    }
}
