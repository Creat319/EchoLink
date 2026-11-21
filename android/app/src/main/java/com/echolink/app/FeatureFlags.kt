package com.echolink.app

/**
 * 功能开关。
 *
 * 麦克风（上行）在 v0.4.8 重新打开：
 * - 采集改走 VOICE_COMMUNICATION + 平台 AEC，回声问题不再靠半双工压低兜底
 * - AEC / 降噪 / 自动增益 三项可在设置里各自开关
 * - 没有平台 AEC 的机型会自动退回半双工压低（仍可用，只是插话体验差）
 *
 * PC 端的 [EchoLink.Core.FeatureFlags.MicUplink] 需要一起打开。
 */
object FeatureFlags {
    /** 上行麦克风（手机 → PC）。 */
    const val MicUplink = true
}