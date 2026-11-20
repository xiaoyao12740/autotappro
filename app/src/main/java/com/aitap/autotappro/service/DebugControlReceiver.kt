package com.aitap.autotappro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.math.roundToInt
import com.aitap.autotappro.BuildConfig
import com.aitap.autotappro.core.ClickerCoordinator
import com.aitap.autotappro.core.ClickRunner
import com.aitap.autotappro.model.PointActionType

/**
 * 调试用启停接收器：仅 debug 构建注册生效，便于在真机上不依赖面板按钮坐标，
 * 通过 adb broadcast 直接启停回放以验证手势链路与间隔节奏。release 包内直接忽略。
 */
class DebugControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START = "com.aitap.autotappro.DEBUG_START"
        const val ACTION_STOP = "com.aitap.autotappro.DEBUG_STOP"
        const val ACTION_PAUSE = "com.aitap.autotappro.DEBUG_PAUSE"
        const val ACTION_RESUME = "com.aitap.autotappro.DEBUG_RESUME"
        const val ACTION_RECORD_START = "com.aitap.autotappro.DEBUG_RECORD_START"
        const val ACTION_RECORD_STOP = "com.aitap.autotappro.DEBUG_RECORD_STOP"
        const val ACTION_ADD_TEST = "com.aitap.autotappro.DEBUG_ADD_TEST"
        const val ACTION_DEL_LAST = "com.aitap.autotappro.DEBUG_DEL_LAST"
        const val ACTION_INFO = "com.aitap.autotappro.DEBUG_INFO"
        const val ACTION_OPEN_PANEL = "com.aitap.autotappro.DEBUG_OPEN_PANEL"
        const val ACTION_DUMP = "com.aitap.autotappro.DEBUG_DUMP"
        const val ACTION_DEBUG_HOLD = "com.aitap.autotappro.DEBUG_HOLD"
        const val ACTION_DEBUG_ADD_AT = "com.aitap.autotappro.DEBUG_ADD_AT"
        const val ACTION_DEBUG_MOVE_PANEL = "com.aitap.autotappro.DEBUG_MOVE_PANEL"
        private const val TAG = "TapFloat"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "DEBUG receiver guarded: BuildConfig.DEBUG=false")
            return
        }
        when (intent?.action) {
            ACTION_START -> {
                // 与面板启停口径一致：直接用当前内存 profile 启动，不先从磁盘刷新覆盖内存点位。
                Log.i(TAG, "DEBUG_START profile=${ClickerCoordinator.profile.value.name} " +
                    "points=${ClickerCoordinator.profile.value.points.size}")
                val started = ClickRunner.start(ClickerCoordinator.profile.value)
                Log.i(TAG, "DEBUG_START -> started=$started")
            }
            ACTION_STOP -> {
                Log.i(TAG, "DEBUG_STOP")
                ClickRunner.stop()
            }
            ACTION_PAUSE -> {
                val ok = ClickRunner.pause()
                Log.i(TAG, "DEBUG_PAUSE -> paused=$ok state=${ClickerCoordinator.runState.value}")
            }
            ACTION_RESUME -> {
                val ok = ClickRunner.resume()
                Log.i(TAG, "DEBUG_RESUME -> resumed=$ok state=${ClickerCoordinator.runState.value}")
            }
            ACTION_RECORD_START -> {
                val ok = ClickerCoordinator.startRecording()
                Log.i(TAG, "DEBUG_RECORD_START ok=$ok recording=${ClickerCoordinator.recording.value.isRecording}")
            }
            ACTION_RECORD_STOP -> {
                val count = ClickerCoordinator.stopRecording()
                Log.i(TAG, "DEBUG_RECORD_STOP recorded=$count points=${ClickerCoordinator.profile.value.points.size}")
            }
            ACTION_ADD_TEST -> {
                val (w, h) = realMetrics(context)
                // 按当前屏宽高比例取两个测试点，避免写死某机型中点坐标。
                val x1 = w * 0.5f; val y1 = h * 0.45f
                val x2 = w * 0.7f; val y2 = h * 0.7f
                ClickerCoordinator.addPoint(PointActionType.CLICK, x1, y1, x1, y1)
                ClickerCoordinator.addPoint(PointActionType.CLICK, x2, y2, x2, y2)
                Log.i(TAG, "DEBUG_ADD_TEST screen=${w}x${h} points=${ClickerCoordinator.profile.value.points.size}")
            }
            ACTION_DEL_LAST -> {
                ClickerCoordinator.removeLastPoint()
                Log.i(TAG, "DEBUG_DEL_LAST points=${ClickerCoordinator.profile.value.points.size}")
            }
            ACTION_INFO -> {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                val dm = android.util.DisplayMetrics()
                wm?.defaultDisplay?.getRealMetrics(dm)
                val rot = wm?.defaultDisplay?.rotation
                val cfg = context.resources.configuration
                Log.i(TAG, "DEBUG_INFO rotation=$rot realMetrics=${dm.widthPixels}x${dm.heightPixels} " +
                    "config.orientation=${cfg.orientation} sw=${cfg.screenWidthDp}dp sh=${cfg.screenHeightDp}dp")
            }
            ACTION_OPEN_PANEL -> {
                com.aitap.autotappro.service.ClickerFloatingService.start(context)
                Log.i(TAG, "DEBUG_OPEN_PANEL requested")
            }
            ACTION_DEBUG_HOLD -> {
                val holdMs = intent?.getLongExtra("holdMs", 800L) ?: 800L
                val svc = ClickerFloatingService.instance
                if (svc == null) {
                    Log.w(TAG, "DEBUG_HOLD service 未运行")
                } else {
                    svc.debugSimulateHold(holdMs)
                    Log.i(TAG, "DEBUG_HOLD dispatched holdMs=$holdMs")
                }
            }
            ACTION_DEBUG_ADD_AT -> {
                val (w, h) = realMetrics(context)
                val x = intent?.getFloatExtra("x", w / 2f) ?: (w / 2f)
                val y = intent?.getFloatExtra("y", h / 2f) ?: (h / 2f)
                val svc = ClickerFloatingService.instance
                if (svc == null) Log.w(TAG, "DEBUG_ADD_AT service 未运行")
                else svc.debugAddPointAt(x, y)
            }
            ACTION_DEBUG_MOVE_PANEL -> {
                val x = intent?.getIntExtra("x", 0) ?: 0
                val y = intent?.getIntExtra("y", 0) ?: 0
                val snap = intent?.getBooleanExtra("snap", true) ?: true
                val svc = ClickerFloatingService.instance
                if (svc == null) Log.w(TAG, "DEBUG_MOVE_PANEL service 未运行")
                else svc.debugMovePanelTo(x, y, snap)
            }
            ACTION_DUMP -> {
                val pts = ClickerCoordinator.profile.value.points
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                val dm = android.util.DisplayMetrics()
                wm?.defaultDisplay?.getRealMetrics(dm)
                val cw = dm.widthPixels.coerceAtLeast(1); val ch = dm.heightPixels.coerceAtLeast(1)
                Log.i(TAG, "DEBUG_DUMP recording=${ClickerCoordinator.recording.value.isRecording} points=${pts.size} cur=${cw}x${ch}")
                pts.forEach { p ->
                    val (rx1, ry1) = com.aitap.autotappro.model.PointCoords.resolveStart(p, cw, ch)
                    val (rx2, ry2) = com.aitap.autotappro.model.PointCoords.resolveEnd(p, cw, ch)
                    Log.i(TAG, "  #${p.id} ${p.type.name} raw=(${p.x1.roundToInt()},${p.y1.roundToInt()})" +
                        " -> (${p.x2.roundToInt()},${p.y2.roundToInt()})" +
                        " resolved=(${rx1.roundToInt()},${ry1.roundToInt()}) -> (${rx2.roundToInt()},${ry2.roundToInt()})" +
                        " delay=${p.delayMs} rand=${p.randomDelayMs} repeat=${p.repeatCount} touch=${p.touchDurationMs}" +
                        " orient=${p.orientation.ifEmpty { "-" }} src=${p.srcW}x${p.srcH}")
                }
            }
            else -> Log.w(TAG, "DEBUG receiver 未知 action=${intent?.action}")
        }
    }

    /** 读取当前设备实时屏宽高（px），调试缺省点按比例取屏中点，不写死机型坐标。 */
    private fun realMetrics(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(dm)
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        return w to h
    }
}
