package com.aitap.autotappro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.session.MediaSession
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aitap.autotappro.core.ClickRunner
import com.aitap.autotappro.core.ClickerCoordinator
import com.aitap.autotappro.core.RunState
import com.aitap.autotappro.model.ChannelKind
import com.aitap.autotappro.model.ClickProfile

/**
 * 无障碍服务：只承担三件事——
 *  1) 持有进程内连接态（供通道探测与无障碍 overlay 代理）；
 *  2) 代理 TYPE_ACCESSIBILITY_OVERLAY 视图的 add/update/remove；
 *  3) 音量下键强制停止（MediaSession 兜底）。
 *
 * 手势编排已下沉到 [ClickRunner]，由解析出的无障碍 / Root 通道执行；本类不再内嵌 Runner。
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TapA11yService"

        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        fun selfComponentName(context: Context): String =
            android.content.ComponentName(context, TapAccessibilityService::class.java)
                .flattenToString()

        fun isEnabledBySystem(context: Context): Boolean {
            val masterEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            ?: ""
            val expectedFlat = selfComponentName(context)
            fun normalize(s: String): String {
                val slash = s.trim().indexOf('/')
                if (slash < 0) return s.trim().lowercase()
                val pkg = s.trim().substring(0, slash)
                var cls = s.trim().substring(slash + 1)
                if (cls.startsWith(".")) cls = pkg + cls
                return "$pkg/$cls".lowercase()
            }
            val expectedNorm = normalize(expectedFlat)
            return masterEnabled && enabled.split(':')
                .any { normalize(it) == expectedNorm }
        }

        fun start(profile: ClickProfile): Boolean = ClickRunner.start(profile)
        fun pause(): Boolean = ClickRunner.pause()
        fun resume(): Boolean = ClickRunner.resume()
        fun stop(): Boolean = ClickRunner.stop()

        fun addOverlayView(view: View, params: WindowManager.LayoutParams): Boolean =
            instance?.addOverlayViewInternal(view, params) ?: false

        fun updateOverlayView(view: View, params: WindowManager.LayoutParams): Boolean =
            instance?.updateOverlayViewInternal(view, params) ?: false

        fun removeOverlayView(view: View): Boolean =
            instance?.removeOverlayViewInternal(view) ?: false
    }

    // 音量下停止兜底：部分模拟器/ROM 上无障碍按键过滤分不到服务，
    // 用 MediaSession 截获媒体键，与 onKeyEvent 双保险。
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ClickerCoordinator.setActiveChannel(ChannelKind.ACCESSIBILITY)
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "AutoTapProStopKey").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: android.content.Intent): Boolean {
                    val ev = intent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                    if (ev == null) return super.onMediaButtonEvent(intent)
                    return handleStopKey(ev)
                }
            })
            isActive = true
        }
    }

    /** 音量下键在 Runner 活跃时强制停止；返回 true 表示已消费。 */
    private fun handleStopKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        if (!ClickRunner.hasActiveRunner()) return false
        ClickRunner.stop()
        ClickerCoordinator.setRunState(RunState.Idle)
        Log.i(TAG, "音量下键强制停止(MediaSession 兜底)")
        return true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        serviceInfo?.let { setServiceInfo(it) }
        instance = this
        ClickerCoordinator.setActiveChannel(ChannelKind.ACCESSIBILITY)
        logServiceInfo()
    }

    /** 打印注册到系统的无障碍能力位，真机核对 canPerformGestures / canRetrieveWindowContent 是否生效。 */
    private fun logServiceInfo() {
        val info = serviceInfo ?: run {
            Log.w(TAG, "onServiceConnected: serviceInfo 为 null")
            return
        }
        val caps = info.capabilities
        val canGestures = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        val canRetrieve = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        Log.i(TAG, "无障碍已连接 flags=${info.flags} capabilities=$caps " +
            "canPerformGestures=$canGestures canRetrieveWindowContent=$canRetrieve " +
            "feedbackType=${info.feedbackType}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        ClickRunner.stop()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "onKeyEvent action=${event.action} keyCode=${event.keyCode} runnerActive=${ClickRunner.hasActiveRunner()}")
        return if (handleStopKey(event)) true else super.onKeyEvent(event)
    }

    override fun onDestroy() {
        ClickRunner.stop()
        mediaSession?.release()
        mediaSession = null
        if (instance === this) {
            instance = null
            ClickerCoordinator.setActiveChannel(ChannelKind.NONE)
        }
        super.onDestroy()
    }

    // ---- 无障碍悬浮窗代理：TYPE_ACCESSIBILITY_OVERLAY 必须由本服务持有 ----

    private val overlayWm: WindowManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun addOverlayViewInternal(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching {
            if (view.parent == null) overlayWm.addView(view, params)
            true
        }.getOrElse { false }

    private fun updateOverlayViewInternal(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching { overlayWm.updateViewLayout(view, params); true }.getOrElse { false }

    private fun removeOverlayViewInternal(view: View): Boolean =
        runCatching { overlayWm.removeView(view); true }.getOrElse { false }
}
