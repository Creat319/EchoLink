package com.echolink.app

/**
 * 功能开关。
 *
 * 麦克风（上行）目前问题较多：虚拟声卡依赖驱动、AEC 没做、半双工压低体验差。
 * 先整体关闭——UI 不显示、引擎不启动、权限不申请，代码保留以便后面修好了再打开。
 * 置 true 之前请确认 PC 端 [EchoLink.Core.Engine.EchoLinkOptions.EnableMic] 也一起打开。
 */
object FeatureFlags {
    /** 上行麦克风（手机 → PC）。暂时关闭。 */
    const val MicUplink = false
}
