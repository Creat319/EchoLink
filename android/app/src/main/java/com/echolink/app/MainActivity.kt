package com.echolink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.echolink.app.ui.EchoLinkApp
import com.echolink.app.ui.EchoLinkTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能跑，只是没有通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        EchoLinkStateHolder.updateSettings(settingsStore.load())

        setContent {
            val state by EchoLinkStateHolder.state.collectAsState()

            // 设置项既是 UI 状态也是运行时配置：改了就落盘并同步给共享状态
            val applySettings: (ReceiverSettings) -> Unit = { newSettings ->
                settingsStore.save(newSettings)
                EchoLinkStateHolder.updateSettings(newSettings)
            }

            LaunchedEffect(state.settings.keepScreenOn) {
                if (state.settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            EchoLinkTheme {
                EchoLinkApp(
                    state = state,
                    onStart = {
                        requestNotificationPermissionIfNeeded()
                        AudioReceiverService.start(this)
                    },
                    onStop = { AudioReceiverService.stop(this) },
                    onTestBeep = { AudioReceiverService.testBeep(this) },
                    onConfirmBeep = { heard -> AudioReceiverService.confirm(this, heard) },
                    onReopen = { AudioReceiverService.reopen(this) },
                    onSettingsChange = applySettings,
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
