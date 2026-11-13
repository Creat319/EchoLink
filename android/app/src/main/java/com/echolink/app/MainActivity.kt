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
import androidx.compose.runtime.mutableStateOf
import com.echolink.app.onboarding.OnboardingScreen
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.echolink.app.ui.EchoLinkApp
import com.echolink.app.ui.EchoLinkTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore

    /** 用户点了「开始接收」，正在等麦克风权限的结果。 */
    private var pendingStart = false

    /** 有待弹窗提醒的新版本号；null 表示不弹。 */
    private val updateDialogVersion = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能跑，只是没有通知 */ }

    /**
     * 麦克风权限要在**启动前台服务之前**拿到：Android 14 下服务声明的
     * foregroundServiceType 必须与实际持有的权限一致，否则 startForeground 直接抛异常。
     * 所以这里"先要权限、拿到再启动服务"。
     */
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val shouldStart = pendingStart
            pendingStart = false

            if (!granted) {
                // 拒绝：关掉开关（避免 UI 显示已开启但没有上行），下行照常工作
                val disabled = EchoLinkStateHolder.liveSettings.copy(micEnabled = false)
                settingsStore.save(disabled)
                EchoLinkStateHolder.updateSettings(disabled)
            }

            if (shouldStart) {
                AudioReceiverService.start(this)
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

            // 首次启动先走引导；走完（或跳过）记一笔，之后不再弹
            var showOnboarding by remember { mutableStateOf(!settingsStore.onboardingDone()) }

            if (showOnboarding) {
                EchoLinkTheme {
                    OnboardingScreen(
                        initialLanguage = state.settings.language,
                        onLanguageChange = { language ->
                            // 选语言立即生效并落盘，跳过后也记住
                            applySettings(EchoLinkStateHolder.liveSettings.copy(language = language))
                        },
                        onFinish = {
                            settingsStore.markOnboardingDone()
                            showOnboarding = false
                        },
                    )
                }
                return@setContent
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
                        // 先要权限再起服务：服务声明的类型必须与权限一致（Android 14）
                        if (FeatureFlags.MicUplink && needsMicPermission()) {
                            pendingStart = true
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            AudioReceiverService.start(this)
                        }
                    },
                    onStop = { AudioReceiverService.stop(this) },
                    onTestBeep = { AudioReceiverService.testBeep(this) },
                    onConfirmBeep = { heard -> AudioReceiverService.confirm(this, heard) },
                    onReopen = { AudioReceiverService.reopen(this) },
                    onSettingsChange = applySettings,
                    onCheckUpdate = { checkUpdates(manual = true) },
                    onOpenDownload = { openDownloadPage() },
                    updateDialogVersion = updateDialogVersion.value,
                    onDismissUpdateDialog = { updateDialogVersion.value = null },
                    onSkipUpdateVersion = {
                        updateDialogVersion.value?.let { version ->
                            settingsStore.markUpdateSkipped(version)
                            // 卡片上仍保留下载入口，只是不再自动弹窗
                            EchoLinkStateHolder.setUpdate(
                                "有新版本 $version（已选择不再提示）",
                                version,
                            )
                        }
                        updateDialogVersion.value = null
                    },
                    onReplayGuide = { showOnboarding = true },
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
     *
     * @param quiet 启动时的静默检查：失败不写状态文案（离线是常态）
     * @param manual 用户手点的「检查更新」：即使这个版本被"不再提示"过，也要弹窗
     */
    private fun checkUpdates(quiet: Boolean = false, manual: Boolean = false) {
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

                result.isNewer -> {
                    EchoLinkStateHolder.setUpdate(
                        "有新版本 ${result.latestVersion}（当前 v$local）",
                        result.latestVersion,
                    )

                    // 自动检查时尊重"不再提示此版本"；手点检查则每次都弹
                    if (UpdateChecker.shouldPrompt(
                            latestVersion = result.latestVersion,
                            skippedVersion = settingsStore.skippedUpdateVersion(),
                            manual = manual,
                        )
                    ) {
                        updateDialogVersion.value = result.latestVersion
                    }
                }

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
     * 是否需要（且能够）请求录音权限。
     * 由开关打开 + 尚未授予两个条件决定；已永久拒绝时 requestPermission 会立刻回调，
     * 不会卡住启动流程。
     */
    private fun needsMicPermission(): Boolean {
        if (!EchoLinkStateHolder.liveSettings.micEnabled) {
            return false
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) != PackageManager.PERMISSION_GRANTED
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
