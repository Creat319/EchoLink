package com.echolink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.echolink.app.audio.AudioEngineController
import com.echolink.app.i18n.Tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 前台服务：mediaPlayback 类型 + 唤醒锁，保证息屏/切后台时 Oboe 流不被挂起。
 */
class AudioReceiverService : Service() {

    private lateinit var controller: AudioEngineController
    private lateinit var settingsStore: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        createNotificationChannel()
        controller = AudioEngineController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground()
                acquireWakeLock()
                controller.start(settingsStore.load())
                watchForNotifications()
            }

            ACTION_STOP -> {
                controller.stop()
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TEST_BEEP -> controller.playTestBeep()

            ACTION_CONFIRM_HEARD -> controller.confirmBeep(true)

            ACTION_CONFIRM_NOT_HEARD -> controller.confirmBeep(false)

            ACTION_REOPEN -> controller.requestReopen(Tr.actionReopen())
        }

        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        controller.close()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification(EchoLinkStateHolder.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 开麦克风时必须声明 microphone 类型。但**权限没授予时不能声明**：
            // Android 14 会因为"声明了类型却没有对应权限"直接抛 SecurityException。
            // 所以这里以"权限已授予"为条件，而不是以"开关打开"为条件。
            val micGranted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            val types = if (EchoLinkStateHolder.liveSettings.micEnabled && micGranted) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }

            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun watchForNotifications() {
        if (notificationJob != null) {
            return
        }

        notificationJob = scope.launch {
            var lastUpdate = 0L
            EchoLinkStateHolder.state.collect { state ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate < 1000) {
                    return@collect
                }

                lastUpdate = now
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun buildNotification(state: ReceiverState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val detail = when (state.phase) {
            LinkPhase.STREAMING -> Tr.notifDetail("%.1f".format(state.engine.waterMs), "%.1f".format(state.targetWaterMs), state.engine.underrunEvents)
            LinkPhase.LISTENING -> Tr.notifWaiting(state.settings.port)
            LinkPhase.REOPENING -> Tr.notifReopening()
            LinkPhase.FAULT -> Tr.notifFault(state.lastReopenReason)
            LinkPhase.STOPPED -> Tr.t("已停止")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EchoLink · ${state.statusText}")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, Tr.actionStop(), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            Tr.notifChannelPlayback(),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = Tr.notifChannelDescription()
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) {
            return
        }

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EchoLink::audio").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.echolink.app.action.START"
        const val ACTION_STOP = "com.echolink.app.action.STOP"
        const val ACTION_TEST_BEEP = "com.echolink.app.action.TEST_BEEP"
        const val ACTION_CONFIRM_HEARD = "com.echolink.app.action.CONFIRM_HEARD"
        const val ACTION_CONFIRM_NOT_HEARD = "com.echolink.app.action.CONFIRM_NOT_HEARD"
        const val ACTION_REOPEN = "com.echolink.app.action.REOPEN"

        private const val CHANNEL_ID = "echolink_playback"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) = send(context, ACTION_START, foreground = true)

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun testBeep(context: Context) = send(context, ACTION_TEST_BEEP)

        fun confirm(context: Context, heard: Boolean) =
            send(context, if (heard) ACTION_CONFIRM_HEARD else ACTION_CONFIRM_NOT_HEARD)

        fun reopen(context: Context) = send(context, ACTION_REOPEN)

        private fun send(context: Context, action: String, foreground: Boolean = false) {
            val intent = Intent(context, AudioReceiverService::class.java).setAction(action)
            if (foreground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching { context.startService(intent) }
            }
        }
    }
}
