package com.echolink.app

import android.content.Context
import com.echolink.app.audio.Protocol
import com.echolink.app.onboarding.AppLanguage

/** 极简设置存储（SharedPreferences）。只有几项，不需要 DataStore 的复杂度。 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("echolink_settings", Context.MODE_PRIVATE)

    fun load(): ReceiverSettings = ReceiverSettings(
        port = prefs.getInt(KEY_PORT, Protocol.DEFAULT_AUDIO_PORT),
        requestExclusive = prefs.getBoolean(KEY_EXCLUSIVE, true),
        autoTargetWater = prefs.getBoolean(KEY_AUTO_TARGET, true),
        mediaUsage = prefs.getBoolean(KEY_MEDIA_USAGE, true),
        manualTargetWaterMs = prefs.getInt(KEY_MANUAL_TARGET, 30),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        micEnabled = prefs.getBoolean(KEY_MIC_ENABLED, true),
        micVoiceMode = prefs.getBoolean(KEY_MIC_VOICE, true),
        micAec = prefs.getBoolean(KEY_MIC_AEC, true),
        micNs = prefs.getBoolean(KEY_MIC_NS, true),
        micAgc = prefs.getBoolean(KEY_MIC_AGC, false),
        language = AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null)),
    )

    fun save(settings: ReceiverSettings) {
        prefs.edit()
            .putInt(KEY_PORT, settings.port)
            .putBoolean(KEY_EXCLUSIVE, settings.requestExclusive)
            .putBoolean(KEY_AUTO_TARGET, settings.autoTargetWater)
            .putBoolean(KEY_MEDIA_USAGE, settings.mediaUsage)
            .putInt(KEY_MANUAL_TARGET, settings.manualTargetWaterMs)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_MIC_ENABLED, settings.micEnabled)
            .putBoolean(KEY_MIC_VOICE, settings.micVoiceMode)
            .putBoolean(KEY_MIC_AEC, settings.micAec)
            .putBoolean(KEY_MIC_NS, settings.micNs)
            .putBoolean(KEY_MIC_AGC, settings.micAgc)
            .putString(KEY_LANGUAGE, settings.language.tag)
            .apply()
    }

    /** 引导是否已经走完（含主动跳过）。false 时启动直接进引导。 */
    fun onboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun markOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /**
     * 用户点过「不再提示此版本」的那个版本号。
     * 存的是**版本号本身**而不是布尔值：下次发新版（比如 0.3.10）时会重新弹窗，
     * 不会出现"永久静音后再也收不到更新提示"。
     */
    fun skippedUpdateVersion(): String? = prefs.getString(KEY_SKIPPED_UPDATE, null)

    fun markUpdateSkipped(version: String) {
        prefs.edit().putString(KEY_SKIPPED_UPDATE, version).apply()
    }

    private companion object {
        const val KEY_PORT = "port"
        const val KEY_EXCLUSIVE = "exclusive"
        const val KEY_AUTO_TARGET = "auto_target"
        const val KEY_MEDIA_USAGE = "media_usage"
        const val KEY_MANUAL_TARGET = "manual_target_ms"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_MIC_ENABLED = "mic_enabled"
        const val KEY_MIC_VOICE = "mic_voice_mode"
        const val KEY_MIC_AEC = "mic_aec"
        const val KEY_MIC_NS = "mic_ns"
        const val KEY_MIC_AGC = "mic_agc"
        const val KEY_LANGUAGE = "language"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_SKIPPED_UPDATE = "skipped_update_version"
    }
}
