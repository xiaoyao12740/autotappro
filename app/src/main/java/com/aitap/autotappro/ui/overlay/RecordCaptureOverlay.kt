package com.aitap.autotappro.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import com.aitap.autotappro.model.PointActionType
import kotlin.math.hypot

/**
 * 录制用全屏透明捕获层。
 *
 * - 仅在录制态由服务置为可触摸；其余态为 NOT_TOUCHABLE，不干扰正常操作。
 * - 触摸落下→抬起：位移小于阈值判为 [PointActionType.CLICK]，否则判为 [PointActionType.SWIPE]，
 *   把起点/终点、按住时长、发生时间回传给协调器存点。
 * - [ignoreUntilMillis] 时间戳内的事件直接放行（返回 false），用于屏蔽本层合成回放出来的手势，
 *   避免把自己刚回放的动作再次录进去（对齐参考实现的穿透忽略窗口）。
 *
 * 多点触控：必须按 [MotionEvent.getPointerId] 分别跟踪每一根手指的 DOWN/UP。若只用一组共享坐标，
 * 双指同时按下时第二根 DOWN 会覆盖第一根的起点，随后第一根 UP 配到第二根坐标，被误判成跨屏滑动。
 */
class RecordCaptureOverlay(
    context: Context,
    private val listener: Listener
) : View(context) {

    fun interface Listener {
        fun onRecord(
            type: PointActionType,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            touchDurationMs: Long,
            startedAtMillis: Long
        )
    }

    /** 忽略截止时间（uptimeMillis）；此前事件不截留、不记录。 */
    @Volatile var ignoreUntilMillis: Long = 0L

    /** 单根手指的触摸序列状态，key 为 pointerId。 */
    private class TouchState(
        val downX: Float,
        val downY: Float,
        var upX: Float,
        var upY: Float,
        val downUptime: Long,
        val downWallClock: Long,
        var moved: Boolean
    )

    private val touches = SparseArray<TouchState>()

    companion object {
        /** 距离阈值以 dp 为基准，构造时按当前 density 换算成 px，保证不同分辨率/密度下手感一致。 */
        private const val SWIPE_MIN_DISTANCE_DP = 30f
        private const val MOVE_TRACK_DP = 10f
        /** 按住超过此时长且位移很小判为长按。 */
        private const val LONG_PRESS_MS = 400L
    }

    private val density = resources.displayMetrics.density
    private val swipeMinDistance = SWIPE_MIN_DISTANCE_DP * density
    private val moveTrack = MOVE_TRACK_DP * density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 回放忽略窗口内：不消费事件，让其穿透到下层目标 App。
        if (SystemClock.uptimeMillis() <= ignoreUntilMillis) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 兜底：上一轮若有未正常抬起的残留序列，先清空避免脏坐标污染新序列。
                touches.clear()
                val id = e.getPointerId(0)
                touches.put(id, newTouch(e.rawX, e.rawY))
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = e.actionIndex
                val id = e.getPointerId(index)
                touches.put(id, newTouch(e.getRawX(index), e.getRawY(index)))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val state = touches[id] ?: continue
                    state.upX = e.getRawX(i)
                    state.upY = e.getRawY(i)
                    if (hypot((state.upX - state.downX).toDouble(),
                            (state.upY - state.downY).toDouble()) >= moveTrack) {
                        state.moved = true
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = e.actionIndex
                val id = e.getPointerId(index)
                touches[id]?.let { recordTouch(it) }
                touches.remove(id)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val id = e.getPointerId(0)
                touches[id]?.let { recordTouch(it) }
                touches.remove(id)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 序列被系统取消：直接清空，不产生无效点位。
                touches.clear()
                return true
            }
        }
        return true
    }

    private fun newTouch(x: Float, y: Float) = TouchState(
        downX = x, downY = y,
        upX = x, upY = y,
        downUptime = SystemClock.uptimeMillis(),
        downWallClock = System.currentTimeMillis(),
        moved = false
    )

    private fun recordTouch(state: TouchState) {
        val duration = (SystemClock.uptimeMillis() - state.downUptime).coerceAtLeast(1L)
        val dist = hypot((state.upX - state.downX).toDouble(), (state.upY - state.downY).toDouble())
        val type = when {
            state.moved && dist >= swipeMinDistance -> PointActionType.SWIPE
            duration >= LONG_PRESS_MS -> PointActionType.LONG_PRESS
            else -> PointActionType.CLICK
        }
        listener.onRecord(
            type = type,
            x1 = state.downX, y1 = state.downY,
            x2 = state.upX, y2 = state.upY,
            touchDurationMs = duration,
            startedAtMillis = state.downWallClock
        )
    }
}
