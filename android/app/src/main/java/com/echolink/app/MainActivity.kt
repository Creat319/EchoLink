package com.echolink.app

import com.echolink.app.update.UpdateChecker
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.content.Intent
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

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // 拒绝时把开关关掉，避免 UI 显示"已开启"但实际没有上行
                EchoLinkStateHolder.updateSettings(
                    EchoLinkStateHolder.liveSettings.copy(micEnabled = false),
                )
            }
        }

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
                        requestMicPermissionIfNeeded()
                        AudioReceiverService.start(this)
                    },
                    onStop = { AudioReceiverService.stop(this) },
                    onTestBeep = { AudioReceiverService.testBeep(this) },
                    onConfirmBeep = { heard -> AudioReceiverService.confirm(this, heard) },
                    onReopen = { AudioReceiverService.reopen(this) },
                    onSettingsChange = applySettings,
                    onCheckUpdate = { checkUpdates() },
                    onOpenDownload = { openDownloadPage() },
                )
            }
        }

        // 启动时静默查一次；失败也不打扰用户（离线是常态）
        checkUpdates(quiet = true)
    }

    /** 当前版本名（versionName），用于和接口返回值比较。 */
    private fun currentVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    /**
     * 版本自检。**只提示、不自动下载**：APK 交给浏览器，用户能看到域名再决定装不装。
     */
    private fun checkUpdates(quiet: Boolean = false) {
        if (!quiet) {
            EchoLinkStateHolder.setUpdate("正在检查更新…", null)
        }

        lifecycleScope.launch {
            val local = currentVersion()
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(local) }

            when {
                result == null -> {
                    if (!quiet) {
                        EchoLinkStateHolder.setUpdate("检查失败（离线或服务不可用）", null)
                    }
                }

                result.isNewer -> EchoLinkStateHolder.setUpdate(
                    "有新版本 ${result.latestVersion}（当前 v$local）",
                    result.latestVersion,
                )

                else -> EchoLinkStateHolder.setUpdate("已是最新（v$local）", null)
            }
        }
    }

    private fun openDownloadPage() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.ANDROID_DOWNLOAD_URL)))
        }
    }

    /**
     * 上行麦克风需要 RECORD_AUDIO。必须在 Activity 可见时请求：
     * 前台服务在后台自行申请麦克风会被直接拒绝。
     */
    private fun requestMicPermissionIfNeeded() {
        if (!EchoLinkStateHolder.liveSettings.micEnabled) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
