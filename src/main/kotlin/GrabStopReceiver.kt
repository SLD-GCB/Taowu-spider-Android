package com.taowu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏「停止」按钮 → 请求停止当前抓取任务。 */
class GrabStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GrabTasks.stop()
    }
}
