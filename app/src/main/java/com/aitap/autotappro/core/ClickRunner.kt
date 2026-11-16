package com.aitap.autotappro.core

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.aitap.autotappro.channel.ChannelResolver
import com.aitap.autotappro.channel.GestureChannel
import com.aitap.autotappro.channel.GestureOutcome
import com.aitap.autotappro.model.ClickPoint
import com.aitap.autotappro.model.ClickProfile
import com.aitap.autotappro.model.PointActionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 进程级编排器：不再内嵌无障碍服务，按解析出的 [GestureChannel]（无障碍 / Root）执行配置。
 *
 * - 主线程协程调度：start / pause / resume / stop；ONCE 按 cycleCount 轮数，LOOP_UNTIL_STOP 循环到停止。
 * - 通道解析在开始运行时完成，全程走同一通道；探测不可用回写「未就绪」并给出原因。
 * - 第一步不套 delayMs；暂停可中断等待；停止回 Idle。
 */
object ClickRunner {

    private const val TAG = "TapRunner"

    /** 进入运行态后到第一轮手势之间的固定缓冲（对齐参考 START_TRIGGER_DELAY_MS=100ms）。 */
    private const val START_TRIGGER_DELAY_MS = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private var runnerJob: Job? = null
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile var lastError: String? = null
        private set

    private var appContext: Context? = null
    private var realW = 1
    private var realH = 1

    fun initialize(context: Context) {
        appContext = context.applicationContext
        readRealMetrics()
    }

    private fun readRealMetrics() {
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        realW = metrics.widthPixels.coerceAtLeast(1)
        realH = metrics.heightPixels.coerceAtLeast(1)
    }

    // ---- 运行控制 ----

    fun start(profile: ClickProfile): Boolean {
        synchronized(lock) {
            if (runnerJob?.isActive == true) return false
            paused = false
            stopRequested = false
        }
        runnerJob = scope.launch {
            val resolution = ChannelResolver.resolve(profile.channelMode)
            ClickerCoordinator.setActiveChannel(resolution.kind)

            val channel = resolution.channel
            if (channel == null) {
                lastError = resolution.reason
                Log.w(TAG, "通道未就绪: ${resolution.reason}")
                ClickerCoordinator.setRunState(RunState.Failed)
                synchronized(lock) { runnerJob = null }
                return@launch
            }
            lastError = null
            // 回放前刷新当前旋转下的真实宽高，避免启动后再横竖屏切换导致夹取边界过期。
            readRealMetrics()
            Log.i(TAG, "START runMode=${profile.runMode} cycleCount=${profile.cycleCount} " +
                "loopInterval=${profile.loopIntervalDelayMs}ms channel=${resolution.kind}")
            profile.points.forEach {
                Log.i(TAG, "  point#${it.id} type=${it.type} (${it.x1.toInt()},${it.y1.toInt()}) " +
                    "delayMs=${it.delayMs} repeat=${it.repeatCount} touch=${it.touchDurationMs}ms " +
                    "randDelay=${it.randomDelayMs}ms")
            }
            try {
                ClickerCoordinator.setRunState(RunState.Running)
                executeProfile(profile, channel)
                ClickerCoordinator.setRunState(RunState.Completed)
            } catch (c: CancellationException) {
                ClickerCoordinator.setRunState(RunState.Idle)
                throw c
            } catch (error: Throwable) {
                lastError = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "执行失败: ${error.message}", error)
                ClickerCoordinator.setRunState(RunState.Failed)
            } finally {
                synchronized(lock) {
                    runnerJob = null
                    paused = false
                    stopRequested = false
                }
            }
        }
        return true
    }

    fun pause(): Boolean = synchronized(lock) {
        val active = runnerJob?.isActive == true
        if (!active || paused) return false
        paused = true
        ClickerCoordinator.setRunState(RunState.Paused)
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        val active = runnerJob?.isActive == true
        if (!active || !paused) return false
        paused = false
        ClickerCoordinator.setRunState(RunState.Running)
        true
    }

    fun stop(): Boolean = synchronized(lock) {
        val job = runnerJob ?: return false
        runnerJob = null
        paused = false
        stopRequested = true
        job.cancel()
        ClickerCoordinator.setRunState(RunState.Idle)
        true
    }

    fun hasActiveRunner(): Boolean = synchronized(lock) { runnerJob?.isActive == true }

    // ---- 编排 ----

    private suspend fun executeProfile(profile: ClickProfile, channel: GestureChannel) {
        if (profile.points.isEmpty()) throw IllegalStateException("没有可执行的点位")

        // 对齐参考实现：进入运行态后先短暂等待，让覆盖层触摸态切换/目标界面稳定，
        // 再发出第一轮手势（同时作为 flags 提交的兜底缓冲）。
        ensureActive(); waitIfPaused()
        delay(START_TRIGGER_DELAY_MS)
        ensureActive(); waitIfPaused()

        // 「整个运行的第一次派发不套 delayMs」跨重复次数 / 点位 / 轮次 / 循环持续保留，
        // 不能在每次序列或循环重启时重置——否则循环模式下新一轮首击跳过间隔，表现为狂点。
        var firstDispatch = true
        when (profile.runMode) {
            com.aitap.autotappro.model.RunMode.ONCE -> {
                repeat(profile.cycleCount.coerceAtLeast(1)) {
                    ensureActive(); waitIfPaused()
                    firstDispatch = executeSequence(profile, channel, firstDispatch)
                }
            }
            com.aitap.autotappro.model.RunMode.LOOP_UNTIL_STOP -> {
                while (currentCoroutineContext().isActive) {
                    firstDispatch = executeSequence(profile, channel, firstDispatch)
                    ensureActive(); waitIfPaused()
                    // 循环边界不再叠加独立轮次缓冲：相邻两次派发的间隔严格由点位 delayMs
                    // （+随机延迟）决定，避免单点位循环时在用户设定间隔之外再垫一段 200ms。
                }
            }
        }
    }

    /**
     * 执行一遍点位序列。
     * @param firstDispatchIn 进入本序列前是否仍是整个运行的首击；
     * @return 执行完后是否仍是首击（正常派发过一次后恒为 false）。
     */
    private suspend fun executeSequence(
        profile: ClickProfile,
        channel: GestureChannel,
        firstDispatchIn: Boolean
    ): Boolean {
        val points = profile.points
        var firstDispatch = firstDispatchIn
        var dispatchSeq = 0
        points.forEachIndexed { _, point ->
            val repeat = point.repeatCount.coerceAtLeast(1)
            repeat(repeat) {
                ensureActive(); waitIfPaused()

                if (!firstDispatch) {
                    val extra = if (point.randomDelayMs > 0L)
                        Random.nextLong(0, point.randomDelayMs + 1) else 0L
                    val wait = (point.delayMs + extra).coerceAtLeast(0L)
                    if (wait > 0L) {
                        Log.i(TAG, "wait ${wait}ms before dispatch#${dispatchSeq + 1} uptime=${SystemClock.uptimeMillis()}")
                        // 分片等待：暂停/停止在 ≤60ms 内响应，避免睡完整段 delay 才悬停。
                        var remaining = wait
                        while (remaining > 0L && currentCoroutineContext().isActive) {
                            val slice = remaining.coerceAtMost(60L)
                            delay(slice)
                            remaining -= slice
                            ensureActive(); waitIfPaused()
                        }
                        Log.i(TAG, "wait resumed uptime=${SystemClock.uptimeMillis()}")
                    }
                }
                ensureActive(); waitIfPaused()

                dispatchSeq++
                Log.i(TAG, "dispatch#$dispatchSeq point#${point.id} uptime=${SystemClock.uptimeMillis()}")
                val outcome = dispatchPoint(channel, point, profile.clickRandomOffsetPx)
                firstDispatch = false
                when (outcome) {
                    GestureOutcome.Completed -> Unit
                    GestureOutcome.Cancelled -> {
                        if (stopRequested || !currentCoroutineContext().isActive)
                            throw CancellationException("手势被停止")
                    }
                    is GestureOutcome.Failed -> {
                        if (stopRequested || !currentCoroutineContext().isActive)
                            throw CancellationException("手势被停止")
                        throw IllegalStateException(outcome.message)
                    }
                }
            }
        }
        return firstDispatch
    }

    private suspend fun dispatchPoint(
        channel: GestureChannel, point: ClickPoint, offsetPx: Int
    ): GestureOutcome {
        return when (point.type) {
        PointActionType.CLICK -> {
            val (x, y) = applyOffset(point.x1, point.y1, offsetPx)
            channel.tap(x, y, point.touchDurationMs.coerceAtLeast(30L))
        }
        PointActionType.LONG_PRESS -> {
            val (x, y) = applyOffset(point.x1, point.y1, offsetPx)
            channel.tap(x, y, point.touchDurationMs.coerceAtLeast(400L))
        }
        PointActionType.DOUBLE_CLICK -> {
            val (x, y) = applyOffset(point.x1, point.y1, offsetPx)
            val first = channel.tap(x, y, point.touchDurationMs.coerceAtLeast(30L))
            if (first != GestureOutcome.Completed) return first
            delay(120L)
            channel.tap(x, y, point.touchDurationMs.coerceAtLeast(30L))
        }
        PointActionType.SWIPE -> {
            val (sx, sy) = applyOffset(point.x1, point.y1, offsetPx)
            val (ex, ey) = applyOffset(point.x2, point.y2, offsetPx)
            channel.swipe(sx, sy, ex, ey, point.touchDurationMs.coerceAtLeast(1L))
        }
        PointActionType.HOME -> channel.goHome()
        PointActionType.BACK -> channel.goBack()
        }
    }

    private fun applyOffset(x: Float, y: Float, offsetPx: Int): Pair<Int, Int> {
        val safe = offsetPx.coerceAtLeast(0)
        if (safe <= 0) return x.toInt() to y.toInt()
        val dx = Random.nextInt(-safe, safe + 1)
        val dy = Random.nextInt(-safe, safe + 1)
        return (x.toInt() + dx).coerceIn(0, realW - 1) to
            (y.toInt() + dy).coerceIn(0, realH - 1)
    }

    private suspend fun waitIfPaused() {
        while (paused && currentCoroutineContext().isActive) delay(60L)
    }

    private suspend fun ensureActive() {
        if (!currentCoroutineContext().isActive) throw CancellationException("任务已取消")
    }
}
