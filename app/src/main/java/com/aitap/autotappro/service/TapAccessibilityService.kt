package com.aitap.autotappro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.session.MediaSession
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aitap.autotappro.BuildConfig
import com.aitap.autotappro.core.ClickRunner
import com.aitap.autotappro.core.L
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
        L.i(TAG, "音量下键强制停止(MediaSession 兜底)")
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
            L.w(TAG, "onServiceConnected: serviceInfo 为 null")
            return
        }
        val caps = info.capabilities
        val canGestures = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        val canRetrieve = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        L.i(TAG, "无障碍已连接 flags=${info.flags} capabilities=$caps " +
            "canPerformGestures=$canGestures canRetrieveWindowContent=$canRetrieve " +
            "feedbackType=${info.feedbackType}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg == packageName) return
        val text = collectRiskText(ev)

        // 系统风控确认框（ColorOS/realme/OPlus 实测由 com.oplus.gesture 承载的标准 AlertDialog）。
        // 跨厂商包名/类名/资源 id 会变，只按通用文案关键字判定；且仅在本应用回放活跃时介入，只停自己的回放，
        // 避免模态框盖住停止钮后连点停不下来、误触系统界面。
        if (isSystemRiskDialog(text) && ClickRunner.hasActiveRunner()) {
            ClickRunner.stop()
            ClickerCoordinator.setRunState(RunState.Idle)
            L.w(TAG, "检测到系统风控确认框，自动停止回放 pkg=$pkg")
            return
        }

        // DEBUG：观测上层弹窗/通知归属（包名/类名/文本），release 不进入。
        if (!BuildConfig.DEBUG) return
        val type = ev.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        ) return
        L.i(TAG, "A11yEvt type=$type pkg=$pkg cls=${ev.className} win=${ev.windowId} text=$text")
    }

    /**
     * 文案命中系统「悬浮窗正在当前页面悬浮显示，可能造成部分操作无响应，是否关闭该应用」风控框。
     * 至少两条关键字同时命中，避免单个词在正常弹窗里误触发。
     */
    private fun isSystemRiskDialog(text: String): Boolean {
        if (text.isEmpty()) return false
        var hits = 0
        if (text.contains("悬浮显示")) hits++
        if (text.contains("无响应")) hits++
        if (text.contains("是否关闭该应用")) hits++
        return hits >= 2
    }

    /**
     * 收集弹窗文本：优先取事件自带文本；未命中风控关键字时，再从事件节点树兜底遍历标题，
     * 兼容部分 ROM 在 CONTENT_CHANGED 事件里不下发标题文本的情况。
     */
    private fun collectRiskText(ev: AccessibilityEvent): String {
        val fromEvent = ev.text?.joinToString(" | ").orEmpty()
        if (isSystemRiskDialog(fromEvent)) return fromEvent
        val source = runCatching { ev.source }.getOrNull() ?: return fromEvent
        val sb = StringBuilder(fromEvent)
        runCatching { collectNodeText(source, sb, 0) }
        return sb.toString()
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 6) return
        node.text?.let { if (it.isNotEmpty()) sb.append(it).append(" | ") }
        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), sb, depth + 1)
        }
    }

    override fun onInterrupt() {
        ClickRunner.stop()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        L.i(TAG, "onKeyEvent action=${event.action} keyCode=${event.keyCode} runnerActive=${ClickRunner.hasActiveRunner()}")
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
