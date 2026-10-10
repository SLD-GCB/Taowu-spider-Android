package com.taowu

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.taowu.backend.BackendContainer
import com.taowu.ui.AppEntry
import com.taowu.ui.theme.TaowuTheme

/**
 * 软件入口 main。
 *
 * 两个入口：
 *   · 进程入口 TaoApplication —— 应用启动时最先执行，装配后端、载入配方；
 *   · 界面入口 MainActivity   —— 启动页（在 AndroidManifest 里声明为 LAUNCHER），承载整个 UI。
 */
class TaowuApplication : Application() {
    lateinit var backend: BackendContainer
        private set

    override fun onCreate() {
        super.onCreate()
        backend = BackendContainer(this)
        backend.initRecipes()
        GrabService.ensureChannel(this)   // 抓取进度通知渠道
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotifPermissionIfNeeded()
        val backend = (application as TaowuApplication).backend
        setContent {
            TaowuTheme {
                AppEntry(backend)
            }
        }
    }

    /** Android 13+ 需要运行时授予通知权限，前台服务的进度通知才能显示。 */
    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
