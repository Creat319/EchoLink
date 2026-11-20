package com.echolink.app

import com.echolink.app.bridge.EchoLinkBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 宿主 Activity。
 *
 * 只做一件事：把 [EchoLinkBridge] 挂到 Flutter 引擎的两个通道上。
 * 音频、前台服务、协议解析全都在 Kotlin 侧原样运行，这里不碰。
 */
class FlutterUiActivity : FlutterActivity() {

    private var bridge: EchoLinkBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val bridge = EchoLinkBridge(applicationContext)
        this.bridge = bridge

        MethodChannel(messenger, EchoLinkBridge.COMMAND_CHANNEL).setMethodCallHandler(bridge)
        EventChannel(messenger, EchoLinkBridge.STATE_CHANNEL).setStreamHandler(bridge)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        bridge?.dispose()
        bridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
