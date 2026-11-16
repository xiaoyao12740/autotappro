package com.aitap.autotappro.channel

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.aitap.autotappro.model.ChannelKind
import com.aitap.autotappro.service.TapAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 无障碍回放通道：包装已绑定的 [TapAccessibilityService]。
 *
 * - CLICK/LONG_PRESS/SWIPE/DOUBLE_CLICK 走 `dispatchGesture`；
 * - HOME/BACK 走 `performGlobalAction`。
 * 坐标为整屏绝对像素。
 *
 * 诊断：每次派发都打 tag=TapA11yService 日志（坐标 / started 标志 / 回调结果），
 * 真机排查“第三方收不到点击”时用 logcat 对照。
 */
class AccessibilityChannel(
    private val service: TapAccessibilityService
) : GestureChannel {

    private companion object {
        const val TAG = "TapA11yService"
    }

    override val kind: ChannelKind = ChannelKind.ACCESSIBILITY

    override suspend fun tap(x: Int, y: Int, durationMs: Long): GestureOutcome {
        val dur = durationMs.coerceAtLeast(1L)
        Log.i(TAG, "dispatchGesture tap x=$x y=$y duration=${dur}ms")
        return runCatching { dispatchTap(x, y, dur) }
            .getOrElse { e ->
                Log.e(TAG, "dispatchGesture tap 抛异常: ${e.message}", e)
                GestureOutcome.Failed("无障碍点击异常: ${e.message ?: e.javaClass.simpleName}")
            }
    }

    override suspend fun swipe(
        sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long
    ): GestureOutcome {
        val dur = durationMs.coerceAtLeast(1L)
        Log.i(TAG, "dispatchGesture swipe ($sx,$sy)->($ex,$ey) duration=${dur}ms")
        return runCatching { dispatchSwipe(sx, sy, ex, ey, dur) }
            .getOrElse { e ->
                Log.e(TAG, "dispatchGesture swipe 抛异常: ${e.message}", e)
                GestureOutcome.Failed("无障碍滑动异常: ${e.message ?: e.javaClass.simpleName}")
            }
    }

    override suspend fun goHome(): GestureOutcome =
        runCatching {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Log.i(TAG, "performGlobalAction HOME -> $ok")
            if (ok) GestureOutcome.Completed else GestureOutcome.Failed("performGlobalAction HOME 失败")
        }.getOrElse { GestureOutcome.Failed("HOME 异常: ${it.message}") }

    override suspend fun goBack(): GestureOutcome =
        runCatching {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.i(TAG, "performGlobalAction BACK -> $ok")
            if (ok) GestureOutcome.Completed else GestureOutcome.Failed("performGlobalAction BACK 失败")
        }.getOrElse { GestureOutcome.Failed("BACK 异常: ${it.message}") }

    private suspend fun dispatchTap(x: Int, y: Int, durationMs: Long): GestureOutcome =
        suspendCancellableCoroutine { cont ->
            // 与参考可用实现一致：点击路径仅 moveTo 到落点（标准零位移点击手势）。
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val started = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    Log.i(TAG, "tap onCompleted ($x,$y)")
                    if (cont.isActive) cont.resume(GestureOutcome.Completed)
                }
                override fun onCancelled(g: GestureDescription?) {
                    Log.w(TAG, "tap onCancelled ($x,$y)")
                    if (cont.isActive) cont.resume(GestureOutcome.Cancelled)
                }
            }, null)
            Log.i(TAG, "tap dispatchGesture returned started=$started")
            if (!started && cont.isActive) {
                cont.resume(GestureOutcome.Failed("dispatchGesture 未启动（服务可能未连接或手势不被允许）"))
            }
        }

    private suspend fun dispatchSwipe(
        sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long
    ): GestureOutcome = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(sx.toFloat(), sy.toFloat())
            lineTo(ex.toFloat(), ey.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val started = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.i(TAG, "swipe onCompleted ($sx,$sy)->($ex,$ey)")
                if (cont.isActive) cont.resume(GestureOutcome.Completed)
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "swipe onCancelled ($sx,$sy)->($ex,$ey)")
                if (cont.isActive) cont.resume(GestureOutcome.Cancelled)
            }
        }, null)
        Log.i(TAG, "swipe dispatchGesture returned started=$started")
        if (!started && cont.isActive) {
            cont.resume(GestureOutcome.Failed("dispatchGesture 未启动（服务可能未连接或手势不被允许）"))
        }
    }
}
