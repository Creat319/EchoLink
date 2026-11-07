package com.echolink.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 音频路由监视。
 *
 * AAudio 流绑定在具体设备上，路由变化（耳机插拔、蓝牙连断、默认设备切换）会让流 disconnected。
 * 正常情况下 Oboe 会走 onErrorAfterClose；但某些 Android 版本这个断开消息根本不到达 AAudio，
 * 所以这里额外监听 AudioDeviceCallback 和 ACTION_HEADSET_PLUG 作为兜底，触发主动重开。
 *
 * **必须去重**：实测 PHJ110 上一次设备变化会连续回调 12 次（同一个设备集合反复上报），
 * 而每次回调都会触发一次重开流 —— 白白制造 150 ms 的音频空洞。
 * 这里用「输出设备集合签名 + 2 s 限流」把它压成一次。
 */
class AudioRouteMonitor(
    private val context: Context,
    private val onRouteChanged: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var started = false
    private var lastSignature: String = ""
    private var lastNotifyMs: Long = 0

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged("接入")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onDevicesChanged("移除")
        }
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val plugged = intent?.getIntExtra("state", -1) == 1
            // 耳机插拔必然改变输出集合；若 ROM 没触发 AudioDeviceCallback，这里补一刀。
            // 清掉签名，避免紧接着的 device callback 再报一次。
            lastSignature = ""
            notify(if (plugged) "耳机插入" else "耳机拔出")
        }
    }

    fun start() {
        if (started) {
            return
        }

        started = true
        lastSignature = outputSignature()
        lastNotifyMs = SystemClock.elapsedRealtime()

        audioManager?.registerAudioDeviceCallback(deviceCallback, null)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                headsetReceiver,
                IntentFilter(Intent.ACTION_HEADSET_PLUG),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun stop() {
        if (!started) {
            return
        }

        started = false
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { context.unregisterReceiver(headsetReceiver) }
    }

    private fun onDevicesChanged(kind: String) {
        val signature = outputSignature()
        // 设备集合没变 = 同一次变化的重复上报，忽略
        if (signature == lastSignature) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs < DEBOUNCE_MS) {
            // 限流窗口内的变化只更新签名，不再触发重开
            lastSignature = signature
            return
        }

        lastSignature = signature
        notify("音频设备$kind（输出 ${signature.ifEmpty { "空" }}）")
    }

    private fun notify(reason: String) {
        lastNotifyMs = SystemClock.elapsedRealtime()
        onRouteChanged(reason)
    }

    /** 当前所有输出设备的 id 集合（排序后拼串），用来判断"是否真的变了"。 */
    private fun outputSignature(): String = runCatching {
        audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.map { it.id }
            ?.sorted()
            ?.joinToString(",")
            ?: ""
    }.getOrDefault("")

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
