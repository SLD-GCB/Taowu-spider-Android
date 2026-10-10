package com.taowu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 抓取前台服务：只要有抓取任务在跑，就以前台服务保活进程并常驻通知显示实时进度；
 * 任务结束（[GrabTasks.state].active=false）自动退到后台并停止。
 */
class GrabService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        job = scope.launch {
            GrabTasks.state.collectLatest { st ->
                if (st.active) {
                    startForegroundCompat(st)
                } else {
                    ServiceCompat.stopForeground(this@GrabService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5s 内切前台
        startForegroundCompat(GrabTasks.state.value)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(st: GrabTasks.State) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                buildNotification(st),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // 某些权限/机型限制下忽略（不崩）
        }
    }

    private val largeIcon by lazy {
        runCatching { BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher) }.getOrNull()
    }

    private fun buildNotification(st: GrabTasks.State): Notification {
        val stopIntent = android.app.PendingIntent.getBroadcast(
            this, 0,
            Intent(this, GrabStopReceiver::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)          // 自己的图标（单色，状态栏用）
            .setContentTitle("梼杌 · 抓取中")
            .setContentText(st.title.ifEmpty { "正在抓取" })
            .setSubText(st.text.ifEmpty { null })
            .setColor(0xFFE5BB74.toInt())                // 通知强调色 = 主题金
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)            // 通知栏直接停
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        largeIcon?.let { b.setLargeIcon(it) }            // 展开后右侧显示彩色应用图标
        if (st.total > 0) {
            b.setProgress(st.total.toInt(), st.done.toInt(), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    companion object {
        const val NOTI_ID = 1001
        const val CHANNEL_ID = "taowu_grab"
        const val ACTION_STOP = "com.taowu.action.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, GrabService::class.java)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // 后台启动受限时忽略；任务仍在应用级协程里跑
            }
        }

        /** 建通知渠道（幂等）。 */
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "抓取进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示番剧抓取/下载的实时进度"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
