package com.taowu

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 抓取任务托管（应用级）。
 *
 * 任务跑在**应用级协程**（不绑 Activity/Compose），并由 [GrabService] 以**前台服务**保活，
 * 因此退到后台、关掉界面也不会被系统杀掉；进度经 [state] 暴露给 UI 与通知。
 * 同一时刻只允许一个抓取任务；支持多集并行时按集汇报进度/速度。
 */
object GrabTasks {

    /** 单集进度。 */
    data class EpStat(
        val label: String = "",
        val done: Long = 0,
        val total: Long = 0,
        val bytes: Long = 0,
        val speed: Double = 0.0,      // 字节/秒
        val finished: Boolean = false,
    )

    data class State(
        val active: Boolean = false,
        /** 任务身份键（通常是被抓的 URL）：用于让不同任务的"每集进度"互不串台。 */
        val key: String = "",
        val title: String = "",
        val text: String = "",
        val done: Long = 0,           // 已完成的集数
        val total: Long = 0,          // 总集数
        val eps: Map<Int, EpStat> = emptyMap(),
    )

    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var gen = 0
    private val lock = Any()
    private val epLast = HashMap<Int, Pair<Long, Long>>()   // index -> (bytes, nanoTime)

    /** 有任务在跑（含"取消中"——旧任务没真正结束前，不允许开新任务，避免状态串台）。 */
    val busy: Boolean get() = job?.let { !it.isCompleted } ?: false

    /**
     * 启动一个后台抓取任务。返回 false 表示已有任务在跑（含正在停止的）。
     * block 内用 report(done,total,text) 报总进度、log(text) 报当前步骤；返回一句完成摘要。
     */
    fun start(
        context: Context,
        key: String,
        title: String,
        block: suspend (report: (Long, Long, String) -> Unit, log: (String) -> Unit) -> String,
    ): Boolean {
        if (busy) return false
        val app = context.applicationContext
        val myGen = ++gen
        synchronized(lock) { epLast.clear() }
        state.value = State(active = true, key = key, title = title, text = "准备中…")
        GrabService.start(app)
        job = scope.launch {
            val report: (Long, Long, String) -> Unit = { d, t, s ->
                if (gen == myGen) state.value = state.value.copy(done = d, total = t, text = s)
            }
            val log: (String) -> Unit = { s -> if (gen == myGen) state.value = state.value.copy(text = s) }
            val summary = try {
                block(report, log)
            } catch (e: CancellationException) {
                "已停止"
            } catch (e: Exception) {
                "出错：${e.message ?: "未知异常"}"
            }
            // 只有"仍是最新一次任务"才写收尾状态，避免被停止的旧任务覆盖新任务
            if (gen == myGen) state.value = state.value.copy(active = false, text = summary)
        }
        return true
    }

    /**
     * 请求停止当前任务（协作式取消）：立即置为"正在停止…"，实际在**下一个挂起点**生效
     * （正在进行的网络请求不可打断，会等它返回后停下）。
     */
    fun stop() {
        val j = job
        if (j != null && j.isActive) {
            j.cancel()
            state.value = state.value.copy(text = "正在停止…")
        }
    }

    /** 每集进度/速度上报（多集并行时由各集协程并发调用）。 */
    fun reportEpisode(index: Int, label: String, done: Long, total: Long, bytes: Long, finished: Boolean) {
        val now = System.nanoTime()
        synchronized(lock) {
            val cur = state.value
            var speed = cur.eps[index]?.speed ?: 0.0
            if (finished) {
                speed = 0.0
                epLast.remove(index)
            } else {
                val prev = epLast[index]
                if (prev == null) {
                    epLast[index] = bytes to now
                } else {
                    val dt = (now - prev.second) / 1e9
                    if (dt >= 0.4) {
                        val db = bytes - prev.first
                        if (db >= 0) speed = db / dt
                        epLast[index] = bytes to now
                    }
                }
            }
            state.value = cur.copy(eps = cur.eps + (index to EpStat(label, done, total, bytes, speed, finished)))
        }
    }

    /** 清掉已结束任务的残留提示。 */
    fun clearIfIdle() {
        if (!busy && !state.value.active) {
            synchronized(lock) { epLast.clear() }
            state.value = State()
        }
    }
}
