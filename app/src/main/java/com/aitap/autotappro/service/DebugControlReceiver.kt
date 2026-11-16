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
        private const val TAG = "TapFloat"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        when (intent?.action) {
            ACTION_START -> {
                ClickerCoordinator.refreshActiveFromDisk()
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
                ClickerCoordinator.addPoint(PointActionType.CLICK, 720f, 1400f, 720f, 1400f)
                ClickerCoordinator.addPoint(PointActionType.CLICK, 1000f, 2100f, 1000f, 2100f)
                Log.i(TAG, "DEBUG_ADD_TEST points=${ClickerCoordinator.profile.value.points.size}")
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
            ACTION_DUMP -> {
                val pts = ClickerCoordinator.profile.value.points
                Log.i(TAG, "DEBUG_DUMP recording=${ClickerCoordinator.recording.value.isRecording} points=${pts.size}")
                pts.forEach { p ->
                    Log.i(TAG, "  #${p.id} ${p.type.name} (${p.x1.roundToInt()},${p.y1.roundToInt()})" +
                        " -> (${p.x2.roundToInt()},${p.y2.roundToInt()})" +
                        " delay=${p.delayMs} touch=${p.touchDurationMs} repeat=${p.repeatCount}")
                }
            }
            else -> Log.w(TAG, "DEBUG receiver 未知 action=${intent?.action}")
        }
    }
}
