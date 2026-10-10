package com.taowu.backend.download

import com.taowu.backend.registry.netlocOf
import com.taowu.backend.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

// ================================================================
// 节流与自适应并发（移植 video_grab._Gate / _host_gate / _health_mark / _adapt_decide / _ctrl_loop）。
// 全局 + 单 host 双层闸门；host 连续失败降级、成功恢复；周期采样按吞吐试探调 cap。
// ================================================================

/** 可调并发闸门（协程版）：同一时刻最多 cap 个在途，cap 可在线调；带窗口统计。 */
class AdaptiveGate(val name: String, initialCap: Int, val maxCap: Int = 20) {

    @Volatile
    var cap: Int = initialCap.coerceIn(1, maxCap.coerceAtLeast(1))

    private val active = AtomicInteger(0)
    private var nOk = 0L
    private var nFail = 0L
    private var nBytes = 0L
    private var nDt = 0.0
    private var nWait = 0L
    private var nPass = 0L
    private val lock = Any()

    suspend fun <T> use(block: suspend () -> T): T {
        val t0 = System.nanoTime()
        var waited = false
        while (active.get() >= cap) {
            waited = true
            delay(3)
        }
        active.incrementAndGet()
        synchronized(lock) { if (waited) nWait++ else nPass++ }
        var ok = false
        try {
            val r = block()
            ok = true
            return r
        } finally {
            active.decrementAndGet()
            synchronized(lock) { if (ok) nOk++ else nFail++; nDt += (System.nanoTime() - t0) / 1e9 }
        }
    }

    fun recordBytes(n: Long) {
        if (n > 0) synchronized(lock) { nBytes += n }
    }

    data class Stats(val ok: Long, val fail: Long, val bytes: Long, val dt: Double, val wait: Long, val pass: Long)

    fun takeStats(): Stats = synchronized(lock) {
        val s = Stats(nOk, nFail, nBytes, nDt, nWait, nPass)
        nOk = 0; nFail = 0; nBytes = 0; nDt = 0.0; nWait = 0; nPass = 0
        s
    }
}

object Throttle {
    const val CAP_MIN = 2

    private const val ADAPT_TICK = 1200L
    private const val PROBE_EVERY = 3
    private const val CPT_FLOOR = 0.90
    private const val OK_RATE_DROP = 0.90

    /** 开关与上限改由 AppSettings 提供（运行时可调）；此处仅留内部下限。 */
    fun enabled(): Boolean = AppSettings.throttleEnabled

    private val global = AdaptiveGate("global", 24, 64)
    private val hostGates = HashMap<String, AdaptiveGate>()
    private val health = HashMap<String, IntArray>()   // [failStreak, okStreak, down]
    private val lock = Any()
    private var controllerStarted = false

    private fun hostBaseCap(): Int = AppSettings.hostConcurrency
    private fun hostMaxCap(): Int = AppSettings.hostConcurrency.coerceAtLeast(CAP_MIN)

    fun hostGate(netloc: String): AdaptiveGate =
        synchronized(lock) {
            val g = hostGates.getOrPut(netloc) { AdaptiveGate("host:$netloc", hostBaseCap(), hostMaxCap()) }
            g
        }

    /** 主机健康：连续失败 ≥3 → cap 压到 MIN；连续成功 ≥5 → 解除并复位。 */
    fun markHealth(netloc: String, ok: Boolean) {
        if (netloc.isEmpty()) return
        synchronized(lock) {
            val h = health.getOrPut(netloc) { intArrayOf(0, 0, 0) }
            if (ok) {
                h[0] = 0; h[1]++
                if (h[2] == 1 && h[1] >= 5) {
                    h[2] = 0; h[1] = 0
                    hostGates[netloc]?.cap = hostBaseCap()
                }
            } else {
                h[0]++; h[1] = 0
                if (h[0] >= 3) {
                    h[2] = 1
                    hostGates[netloc]?.cap = CAP_MIN
                }
            }
        }
    }

    fun isDown(netloc: String): Boolean = synchronized(lock) { (health[netloc]?.get(2) ?: 0) == 1 }

    /** 全局 + host 双层闸门内跑一段（关闭时直接执行）。 */
    suspend fun <T> fetch(netloc: String, block: suspend () -> T): T {
        if (!enabled()) return block()
        global.cap = AppSettings.globalConcurrency
        val g = hostGate(netloc)
        if (g.cap > hostMaxCap()) g.cap = hostBaseCap()
        return global.use { g.use { block() } }
    }

    /** 纯决策函数（逐字移植 _adapt_decide）。返回 (newCap, newProbing)。 */
    fun adaptDecide(
        cap: Int, okRate: Double, saturated: Boolean, cpt: Double, prevCpt: Double,
        probing: Boolean, probeDue: Boolean, capMin: Int, capMax: Int,
    ): Pair<Int, Boolean> {
        val cmin = capMin.coerceAtLeast(1)
        val cmax = capMax.coerceAtLeast(1)
        if (okRate < OK_RATE_DROP) return (cap - 3).coerceAtLeast(cmin) to false
        if (!saturated) return cap to false
        if (probing) {
            if (prevCpt > 0 && cpt > 0 && cpt < prevCpt * CPT_FLOOR) return (cap - 2).coerceAtLeast(cmin) to false
            return cap to false
        }
        if (probeDue && cap < cmax) return (cap + 2).coerceAtMost(cmax) to true
        return cap to false
    }

    /** 自适应 daemon：周期采样每个 host 闸门 → adaptDecide → setCap。 */
    fun startController(scope: CoroutineScope) {
        synchronized(lock) {
            if (controllerStarted) return
            controllerStarted = true
        }
        scope.launch {
            val state = HashMap<String, DoubleArray>()   // [prevCpt, probing, since]
            while (true) {
                delay(ADAPT_TICK)
                if (!enabled() || !AppSettings.adaptiveEnabled) continue
                val snapshot = synchronized(lock) { hostGates.mapValues { it.value } }
                for ((netloc, gate) in snapshot) {
                    val st = gate.takeStats()
                    val total = st.ok + st.fail
                    if (total <= 0) continue
                    val okRate = st.ok.toDouble() / total
                    val tp = if (st.dt > 0) (if (st.bytes > 0) st.bytes.toDouble() else st.ok.toDouble()) / st.dt else 0.0
                    val aq = st.wait + st.pass
                    val saturated = aq > 0 && st.wait >= aq * 0.5
                    val cpt = if (gate.cap > 0 && tp > 0) tp / gate.cap else 0.0
                    val s0 = state.getOrPut(netloc) { doubleArrayOf(0.0, 0.0, 0.0) }
                    val probing = s0[1] > 0
                    val since = s0[2].toInt()
                    val capMax = hostMaxCap().coerceAtLeast(CAP_MIN)
                    val (newCap, newProb) = adaptDecide(
                        gate.cap, okRate, saturated, cpt, s0[0], probing,
                        !probing && since >= PROBE_EVERY, CAP_MIN, capMax,
                    )
                    if (newCap != gate.cap) gate.cap = newCap
                    if (newProb) {
                        s0[0] = if (cpt > 0) cpt else s0[0]; s0[1] = 1.0; s0[2] = 0.0
                    } else {
                        s0[1] = 0.0
                        if (cpt > 0) s0[0] = cpt
                        s0[2] = if (okRate < OK_RATE_DROP || !saturated) 0.0 else since + 1.0
                    }
                }
            }
        }
    }
}
