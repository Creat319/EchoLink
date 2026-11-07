package com.echolink.app

import android.content.Context
import com.echolink.app.audio.Protocol

/** 极简设置存储（SharedPreferences）。只有几项，不需要 DataStore 的复杂度。 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("echolink_settings", Context.MODE_PRIVATE)

    fun load(): ReceiverSettings = ReceiverSettings(
        port = prefs.getInt(KEY_PORT, Protocol.DEFAULT_AUDIO_PORT),
        requestExclusive = prefs.getBoolean(KEY_EXCLUSIVE, true),
        autoTargetWater = prefs.getBoolean(KEY_AUTO_TARGET, true),
        manualTargetWaterMs = prefs.getInt(KEY_MANUAL_TARGET, 30),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
    )

    fun save(settings: ReceiverSettings) {
        prefs.edit()
            .putInt(KEY_PORT, settings.port)
            .putBoolean(KEY_EXCLUSIVE, settings.requestExclusive)
            .putBoolean(KEY_AUTO_TARGET, settings.autoTargetWater)
            .putInt(KEY_MANUAL_TARGET, settings.manualTargetWaterMs)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .apply()
    }

    private companion object {
        const val KEY_PORT = "port"
        const val KEY_EXCLUSIVE = "exclusive"
        const val KEY_AUTO_TARGET = "auto_target"
        const val KEY_MANUAL_TARGET = "manual_target_ms"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
