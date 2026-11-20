package com.aitap.autotappro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.aitap.autotappro.channel.ChannelResolver
import com.aitap.autotappro.core.ClickRunner
import com.aitap.autotappro.core.ClickerCoordinator
import com.aitap.autotappro.core.RunState
import com.aitap.autotappro.model.ChannelKind
import com.aitap.autotappro.model.ClickPoint
import com.aitap.autotappro.model.PointActionType
import com.aitap.autotappro.model.channelLabel
import com.aitap.autotappro.ui.overlay.AddActionMenu
import com.aitap.autotappro.ui.overlay.FloatingDialogHost
import com.aitap.autotappro.ui.overlay.PointBubbleView
import com.aitap.autotappro.ui.overlay.PointParamDialog
import com.aitap.autotappro.ui.overlay.RecordCaptureOverlay
import com.aitap.autotappro.ui.overlay.SidePanelView
import com.aitap.autotappro.ui.overlay.SwipeLineView
import com.aitap.autotappro.ui.overlay.TentativeMarkerView
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 点击器悬浮窗服务（批 2 主交付物）。
 *
 * - 独立于 Activity：窄条、点位气泡、连线全部在本服务内 WindowManager.addView。
 * - 状态源：观察 [ClickerCoordinator] 的 profile / runState，不直接持有 Activity。
 * - 生命周期：onStartCommand 收 START/STOP；onDestroy 移除全部悬浮窗与弹窗。
 */
class ClickerFloatingService : Service() {

    companion object {
        private const val TAG = "TapFloat"
        const val ACTION_START = "com.aitap.autotappro.action.START"
        const val ACTION_STOP = "com.aitap.autotappro.action.STOP"
        const val RECORD_REPLAY_TRIGGER_MS = 100L
        const val RECORD_IGNORE_EXTRA_MS = 250L

        fun start(context: Context) {
            val intent = Intent(context, ClickerFloatingService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClickerFloatingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private lateinit var wm: WindowManager
    private var panel: SidePanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val bubbles = linkedMapOf<Int, MutableList<PointBubbleView>>()
    private var swipeLine: SwipeLineView? = null

    /** 录制全屏捕获层：仅录制态可触摸，其余态 NOT_TOUCHABLE 穿透。 */
    private var recordOverlay: RecordCaptureOverlay? = null
    private var recordParams: WindowManager.LayoutParams? = null
    private var recordPassthroughJob: Job? = null

    /** 录制反馈条：非触摸，悬在顶部，实时显示最近一个动作的类型与起止坐标。 */
    private var feedbackView: TextView? = null
    private var feedbackParams: WindowManager.LayoutParams? = null
    private var feedbackHideJob: Job? = null

    /** 录制态「拟添加」虚线占位标记；停止录制后清空，由正式气泡接替。 */
    private val tentative = mutableListOf<View>()
    private var tentativeLine: SwipeLineView? = null

    /** 视图归属：true=无障碍服务 overlay，false=应用悬浮窗。 */
    private val overlayHosts = mutableMapOf<View, Boolean>()

    private var selectedPointId: Int? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var profileJob: Job? = null
    private var stateJob: Job? = null

    private var screenW = 0
    private var screenH = 0

    /**
     * 点位气泡是否可触摸。运行/暂停态置为 false（加 FLAG_NOT_TOUCHABLE），
     * 使回放注入手势穿透到下层目标 App，而不是被压在落点上的气泡自身截留；
     * 空闲/完成/失败后恢复 true，允许拖动、长按编辑。对齐参考实现的触摸态切换。
     */
    private var bubblesTouchable = true

    /** 悬浮父帧原点在整屏绝对坐标系中的偏移（addView 后实测回填，状态栏高资源值兜底）。 */
    private var frameOffsetX = 0
    private var frameOffsetY = 0

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        readRealMetrics()
        // 状态栏高作为父帧顶部偏移的初始兜底；首帧布局后用实测位置校正。
        frameOffsetY = statusBarHeight()
        ClickerCoordinator.initialize(this)
        ClickRunner.initialize(this)
        startForegroundCompat()
    }

    private fun readRealMetrics() {
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels.coerceAtLeast(1)
        screenH = metrics.heightPixels.coerceAtLeast(1)
    }

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    /** addView 后实测父帧原点偏移：locationOnScreen 为绝对坐标，减去 params 坐标即父帧偏移。 */
    private fun captureFrameOffset(view: View, params: WindowManager.LayoutParams) {
        view.post {
            val loc = IntArray(2)
            runCatching { view.getLocationOnScreen(loc) }
                .onSuccess {
                    frameOffsetX = loc[0] - params.x
                    frameOffsetY = loc[1] - params.y
                }
        }
    }

    private fun startForegroundCompat() {
        val channelId = "clicker_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "点击器悬浮面板", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "保持悬浮面板服务存活" }
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("AutoTapPro 点击器运行中")
            .setContentText("侧边悬浮面板已显示")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else 0
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(1001, notification, fgsType)
            else startForeground(1001, notification)
        }.onFailure { Log.w("TapFloat", "startForeground 失败: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> showOverlay()
        }
        return START_STICKY
    }

    /**
     * 横竖屏切换后：重读当前旋转下的真实宽高，重测父帧偏移，并重排气泡/拟添加标记，
     * 避免启动时缓存的竖屏尺寸导致横屏气泡定位、拖动夹取使用过期边界。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        readRealMetrics()
        panel?.let { p -> panelParams?.let { captureFrameOffset(p, it) } }
        Log.i(TAG, "onConfigurationChanged screen=${screenW}x${screenH} orientation=${newConfig.orientation}")
        rebuildBubbles()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        // 脱挂体检：宿主回收后旧引用 parent==null，先置空，重开走完整重建路径。
        clearDanglingRefs()
        if (panel != null) return

        // 录制层先于面板加入：位于面板下层，录制中面板停止钮仍可点到。
        createRecordOverlay()
        createFeedbackChip()

        try {
            panel = SidePanelView(this, screenW, screenH, panelCallbacks)
            panel?.applyInitial(ClickerCoordinator.profile.value.panelCollapsed)
            panel?.setPointsVisible(ClickerCoordinator.profile.value.pointsVisible)
            val params = baseParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { x = 8; y = 240 }
            addManaged(panel!!, params)
            panelParams = params
            captureFrameOffset(panel!!, params)
        } catch (t: Throwable) {
            Log.e("TapFloat", "面板 addView 失败: ${t.javaClass.simpleName} ${t.message}", t)
            Toast.makeText(this, "悬浮面板添加失败: ${t.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        attachObservers()
        rebuildBubbles()
    }

    /** 清理已脱挂（parent==null / 未附着）的残留引用，避免重开被非空守卫拦截。 */
    private fun clearDanglingRefs() {
        panel?.let { p ->
            if (p.parent == null || !p.isAttachedToWindow) {
                overlayHosts.remove(p)
                panel = null
                panelParams = null
            }
        }
        bubbles.values.flatten().forEach { b ->
            if (b.parent == null) overlayHosts.remove(b)
        }
        bubbles.values.forEach { group -> group.removeAll { it.parent == null } }
        swipeLine?.let { line ->
            if (line.parent == null) {
                overlayHosts.remove(line)
                swipeLine = null
            }
        }
    }

    private fun attachObservers() {
        profileJob = scope.launch {
            ClickerCoordinator.profile.collectLatest { p ->
                panel?.setPointsVisible(p.pointsVisible)
                rebuildBubbles()
            }
        }
        stateJob = scope.launch {
            ClickerCoordinator.runState.collectLatest { state ->
                panel?.setRunState(state)
                // 运行/暂停/录制态气泡穿透到目标 App；其余态恢复可拖动编辑。
                setBubblesTouchable(bubblesTouchableAllowed())
                if (state == RunState.Failed) {
                    ClickRunner.lastError?.let {
                        Toast.makeText(this@ClickerFloatingService, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        scope.launch {
            ClickerCoordinator.activeChannel.collectLatest { kind ->
                val text = channelLabel(ClickerCoordinator.channelMode(), kind)
                panel?.setChannelReady(kind != ChannelKind.NONE, text)
            }
        }
        scope.launch {
            ClickerCoordinator.recording.collectLatest { rec ->
                panel?.setRecordingState(rec.isRecording)
                setRecordTouchable(rec.isRecording)
                // 录制态气泡穿透；停止录制后补渲染已录点位气泡。
                setBubblesTouchable(bubblesTouchableAllowed())
                if (rec.isRecording) {
                    clearTentative()
                    showFeedback("● 录制中：点击 / 长按 / 滑动，点面板 ■ 停止")
                } else {
                    hideFeedback()
                    clearTentative()
                    rebuildBubbles()
                }
            }
        }
    }

    // ---- 面板回调 ----

    private val panelCallbacks = object : SidePanelView.Callbacks {
        override fun onTogglePlayPause() {
            Log.i(TAG, "面板▶点击 runState=${ClickerCoordinator.runState.value} " +
                "channel=${ClickerCoordinator.activeChannel.value}")
            when (ClickerCoordinator.runState.value) {
                RunState.Running -> ClickRunner.pause()
                RunState.Paused -> ClickRunner.resume()
                else -> {
                    // 先同步把气泡切到不可触摸，确保第一次注入手势不会被仍可触摸的气泡截留
                    // （StateFlow 观察回调与 updateViewLayout 是异步的，晚于首轮派发会产生竞态）。
                    setBubblesTouchable(false)
                    if (!ClickRunner.start(ClickerCoordinator.profile.value)) {
                        Toast.makeText(this@ClickerFloatingService, "已有任务在运行", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        override fun onEmergencyStop() {
            Log.i(TAG, "面板⏹急停")
            // 急停：只终止执行回 Idle，保留悬浮面板；与 ✕（停止并关闭面板）分开。
            ClickRunner.stop()
        }

        override fun onToggleRecord() {
            if (ClickerCoordinator.recording.value.isRecording) {
                val count = ClickerCoordinator.stopRecording()
                Toast.makeText(this@ClickerFloatingService, "录制结束，共记录 $count 个动作", Toast.LENGTH_LONG).show()
            } else {
                if (ClickRunner.hasActiveRunner()) {
                    Toast.makeText(this@ClickerFloatingService, "执行中不可录制，请先停止", Toast.LENGTH_LONG).show()
                    return
                }
                // 开始录制前收起所有我方弹窗/菜单，避免盖住停止录制钮。
                FloatingDialogHost.dismissAll()
                val ok = ClickerCoordinator.startRecording()
                Toast.makeText(
                    this@ClickerFloatingService,
                    if (ok) "录制中：点击/滑动屏幕即可记录动作" else "当前状态无法录制",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onAddPoint() {
            if (ClickerCoordinator.recording.value.isRecording) {
                Log.i(TAG, "录制中忽略添加点位")
                Toast.makeText(this@ClickerFloatingService, "录制中不可添加点位，请先停止录制", Toast.LENGTH_LONG).show()
                return
            }
            Log.i(TAG, "面板＋添加点位")
            AddActionMenu.show(
                this@ClickerFloatingService, wm,
                onPick = { type -> addPointOfType(type) },
                onDismiss = { }
            )
        }

        override fun onRemoveSelected() {
            if (ClickerCoordinator.recording.value.isRecording) {
                Toast.makeText(this@ClickerFloatingService, "录制中不可删除点位", Toast.LENGTH_LONG).show()
                return
            }
            val id = selectedPointId
            if (id != null) {
                ClickerCoordinator.removePoint(id)
                selectedPointId = null
            } else {
                // 未选中：兜底删除最后一个点位，方便便捷删减。
                val last = ClickerCoordinator.profile.value.points.lastOrNull()
                if (last == null) {
                    Toast.makeText(this@ClickerFloatingService, "没有可删除的点位", Toast.LENGTH_SHORT).show()
                    return
                }
                ClickerCoordinator.removeLastPoint()
            }
        }

        override fun onClosePanel() {
            ClickRunner.stop()
            if (ClickerCoordinator.recording.value.isRecording) ClickerCoordinator.stopRecording()
            FloatingDialogHost.dismissAll()
            stopSelf()
        }

        override fun onTogglePointsVisible() {
            val cur = ClickerCoordinator.profile.value.pointsVisible
            ClickerCoordinator.setPointsVisible(!cur)
        }

        override fun onPanelMoved(x: Int, y: Int) {
            panelParams?.let { p ->
                p.x = x; p.y = y
                panel?.let { updateManaged(it, p) }
            }
        }

        override fun onPanelDragEnded() {
            snapPanelToEdge()
        }

        override fun onPanelLayoutChanged() {
            panel?.let { v -> panelParams?.let { updateManaged(v, it) } }
        }

        override fun onToggleCollapseKnob() {
            if (ClickerCoordinator.recording.value.isRecording) return
            panel?.toggleCollapsed()
        }

        override fun onPanelCollapsedChanged(collapsed: Boolean) {
            ClickerCoordinator.setPanelCollapsed(collapsed)
        }
    }

    /**
     * 拖拽松手后按开关吸附到最近的左/右边缘；未开启吸附则保持松手位置。
     * 仅贴边横向归位，纵向位置不变。
     */
    private fun snapPanelToEdge() {
        val p = panel ?: return
        val params = panelParams ?: return
        if (!ClickerCoordinator.profile.value.snapToEdge) return
        val w = p.panelWidthPx()
        val center = params.x + w / 2f
        val targetX = if (center < screenW / 2f) 0 else (screenW - w).coerceAtLeast(0)
        if (params.x != targetX) {
            params.x = targetX
            p.setPanelPosition(params.x, params.y)
            updateManaged(p, params)
        }
    }

    // ---- 点位气泡 ----

    private fun addPointOfType(type: PointActionType) {
        val cx = (screenW / 2f)
        val cy = (screenH / 2f)
        ClickerCoordinator.addPoint(type, cx, cy, cx, cy)
    }

    private fun rebuildBubbles() {
        // 先清掉旧气泡与连线
        bubbles.values.flatten().forEach { removeManaged(it) }
        bubbles.clear()
        swipeLine?.let { removeManaged(it) }
        swipeLine = null

        // 录制态不渲染可交互气泡，改为非触摸的虚线空心圆拟添加标记；停止后再统一渲染正式气泡。
        if (ClickerCoordinator.recording.value.isRecording) {
            renderTentative()
            return
        }

        // 点位显隐开关关闭：不渲染气泡与连线，避免手操时被标记遮挡。
        if (!ClickerCoordinator.profile.value.pointsVisible) return

        val points = ClickerCoordinator.profile.value.points
        points.forEach { point ->
            val group = mutableListOf<PointBubbleView>()
            if (point.type == PointActionType.SWIPE) {
                val line = SwipeLineView(this).also { swipeLine = it }
                runCatching { addManaged(line, fullScreenParams()) }

                val start = makeBubble(point, isEndpoint = false) { b, nx, ny ->
                    moveBubble(b, point, nx, ny, isEndpoint = false)
                }
                val end = makeBubble(point, isEndpoint = true) { b, nx, ny ->
                    moveBubble(b, point, nx, ny, isEndpoint = true)
                }
                group.add(start); group.add(end)
            } else {
                group.add(makeBubble(point, isEndpoint = false) { b, nx, ny ->
                    moveBubble(b, point, nx, ny, isEndpoint = false)
                })
            }
            bubbles[point.id] = group
        }
        refreshLine()
    }

    /** 拖动中：同步点位坐标并重定位气泡窗口（夹取屏内），刷新 SWIPE 虚线。 */
    private fun moveBubble(
        bubble: PointBubbleView,
        point: ClickPoint,
        nx: Float,
        ny: Float,
        isEndpoint: Boolean
    ) {
        if (isEndpoint) { point.x2 = nx; point.y2 = ny } else { point.x1 = nx; point.y1 = ny }
        updateManaged(bubble, bubbleParams(bubble))
        refreshLine()
    }

    private fun makeBubble(
        point: ClickPoint,
        isEndpoint: Boolean,
        onDrag: (PointBubbleView, Float, Float) -> Unit
    ): PointBubbleView {
        val holder = arrayOfNulls<PointBubbleView>(1)
        val bubble = PointBubbleView(
            this, point, isEndpoint, screenW, screenH,
            onDrag = { nx, ny -> holder[0]?.let { onDrag(it, nx, ny) } },
            onSelect = { selected ->
                if (!ClickerCoordinator.recording.value.isRecording) {
                    selectedPointId = selected.point.id
                }
            },
            onLongPress = { selected ->
                if (!ClickerCoordinator.recording.value.isRecording) {
                    selectedPointId = selected.point.id
                    PointParamDialog.show(
                        this, wm, point,
                        onSave = { persistAfterEdit() },
                        onDelete = {
                            ClickerCoordinator.removePoint(it.id)
                            selectedPointId = null
                        },
                        onDismiss = { }
                    )
                }
            }
        )
        holder[0] = bubble
        val params = bubbleParams(bubble)
        runCatching { addManaged(bubble, params) }
            .onFailure { Log.e("TapFloat", "气泡 addView 失败 #${point.id}: ${it.message}", it) }
        return bubble
    }

    private fun bubbleParams(bubble: PointBubbleView): WindowManager.LayoutParams {
        val size = bubble.bubbleSize()
        val half = size / 2f
        val cx = bubble.centerX()
        val cy = bubble.centerY()
        return baseParams(size, size, Gravity.TOP or Gravity.START).apply {
            // 点位存整屏绝对坐标；params 相对悬浮父帧，减去实测父帧原点偏移。
            x = (cx - half - frameOffsetX).toInt()
            y = (cy - half - frameOffsetY).toInt()
            if (!bubblesTouchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        }
    }

    /**
     * 切换所有点位气泡的触摸态；运行/暂停态不可触摸，保证手势穿透到目标 App。
     *
     * 注意：不能只靠 `updateViewLayout` 改 flags——flags 由 WindowManager 异步提交，
     * 首轮注入手势可能赶在生效前发出，仍被仍可触摸的气泡截留。这里采用移除后按新
     * LayoutParams 重新 `addView`：flags 在窗口创建时原子生效，确保切换确定落地。
     */
    /** 气泡可触摸编辑的前提：非运行/暂停，且不在录制态。 */
    private fun bubblesTouchableAllowed(): Boolean {
        val s = ClickerCoordinator.runState.value
        return s != RunState.Running && s != RunState.Paused && !ClickerCoordinator.recording.value.isRecording
    }

    private fun setBubblesTouchable(touchable: Boolean) {
        if (bubblesTouchable == touchable) return
        bubblesTouchable = touchable
        bubbles.values.flatten().forEach { bubble ->
            removeManaged(bubble)
            addManaged(bubble, bubbleParams(bubble))
        }
    }

    // ---- 录制态拟添加标记 ----

    private fun renderTentative() {
        clearTentative()
        val points = ClickerCoordinator.profile.value.points
        points.forEach { point ->
            if (point.type == PointActionType.SWIPE) {
                val line = SwipeLineView(this).also { tentativeLine = it }
                runCatching { addManaged(line, fullScreenParams()) }
                tentative.add(line)
                val start = TentativeMarkerView(this, point, isEndpoint = false)
                val end = TentativeMarkerView(this, point, isEndpoint = true)
                runCatching { addManaged(start, tentativeParams(start)) }
                runCatching { addManaged(end, tentativeParams(end)) }
                tentative.add(start)
                tentative.add(end)
                line.setLine(point.x1, point.y1, point.x2, point.y2)
            } else {
                val m = TentativeMarkerView(this, point, isEndpoint = false)
                runCatching { addManaged(m, tentativeParams(m)) }
                tentative.add(m)
            }
        }
    }

    private fun tentativeParams(m: TentativeMarkerView): WindowManager.LayoutParams {
        val size = m.markerSize()
        val half = size / 2f
        return baseParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = (m.centerX() - half - frameOffsetX).toInt()
            y = (m.centerY() - half - frameOffsetY).toInt()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun clearTentative() {
        tentative.forEach { removeManaged(it) }
        tentative.clear()
        tentativeLine?.let { removeManaged(it) }
        tentativeLine = null
    }

    // ---- 录制反馈条 ----

    private fun createFeedbackChip() {
        if (feedbackView != null) return
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 13f
            val padH = (14 * density).roundToInt()
            val padV = (8 * density).roundToInt()
            setPadding(padH, padV, padH, padV)
            maxWidth = screenW - (48 * density).roundToInt()
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 255, 255, 255))
                cornerRadius = (12 * density).toFloat()
                setStroke((1 * density).toInt(), Color.argb(40, 0, 0, 0))
            }
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            chosenOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (110 * density).roundToInt()
        }
        addManaged(tv, params)
        feedbackView = tv
        feedbackParams = params
    }

    private fun showFeedback(text: String) {
        val tv = feedbackView ?: return
        tv.text = text
        if (tv.visibility != View.VISIBLE) {
            tv.visibility = View.VISIBLE
            feedbackParams?.let { updateManaged(tv, it) }
        }
        // 瞬态通知：约 1 秒后自动消失；新动作到来时重置计时。
        feedbackHideJob?.cancel()
        feedbackHideJob = scope.launch {
            delay(1000L)
            hideFeedback()
        }
    }

    private fun hideFeedback() {
        feedbackHideJob?.cancel()
        feedbackHideJob = null
        val tv = feedbackView ?: return
        if (tv.visibility == View.VISIBLE) {
            tv.visibility = View.GONE
            feedbackParams?.let { updateManaged(tv, it) }
        }
    }

    private fun recordFeedbackText(
        count: Int, type: PointActionType,
        x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long
    ): String {
        val sx = x1.toInt(); val sy = y1.toInt()
        val ex = x2.toInt(); val ey = y2.toInt()
        return when (type) {
            PointActionType.SWIPE -> "#$count 滑动 ($sx,$sy)→($ex,$ey) ${durMs}ms"
            PointActionType.LONG_PRESS -> "#$count 长按 ($sx,$sy) ${durMs}ms"
            PointActionType.HOME -> "#$count Home"
            PointActionType.BACK -> "#$count Back"
            else -> "#$count 点击 ($sx,$sy) ${durMs}ms"
        }
    }

    // ---- 录制捕获层 ----

    private fun createRecordOverlay() {
        if (recordOverlay != null) return
        val view = RecordCaptureOverlay(this) { type, x1, y1, x2, y2, dur, startedAt ->
            onRecorded(type, x1, y1, x2, y2, dur, startedAt)
        }
        val params = fullScreenParams()
        addManaged(view, params)
        recordOverlay = view
        recordParams = params
    }

    /** 录制层触摸态：录制中可触摸以截留手势记录；其余态 NOT_TOUCHABLE 穿透到目标 App。 */
    private fun setRecordTouchable(touchable: Boolean) {
        val view = recordOverlay ?: return
        val params = recordParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val hasFlag = (params.flags and flag) != 0
        val needFlag = !touchable
        if (hasFlag == needFlag) return
        params.flags = if (needFlag) params.flags or flag else params.flags and flag.inv()
        // 用 updateViewLayout 改 flags，不能移除后重 addView——重加会把录制层提到 z-order 最顶层，
        // 盖住面板并截留停止录制钮的触摸（表现为录不停）。录制层必须始终位于面板下层。
        updateManaged(view, params)
    }

    private fun onRecorded(
        type: PointActionType,
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long, startedAtMillis: Long
    ) {
        val point = ClickerCoordinator.addRecordedAction(
            type = type, x1 = x1, y1 = y1, x2 = x2, y2 = y2,
            touchDurationMs = durationMs, startedAtMillis = startedAtMillis
        ) ?: return
        val count = ClickerCoordinator.recording.value.recordedCount
        // 系统可能静默 Toast，改用顶部悬浮反馈条显示类型与起止坐标。
        showFeedback(recordFeedbackText(count, type, x1, y1, x2, y2, durationMs))
        Log.i(TAG, "录制 #$count ${recordFeedbackText(count, type, x1, y1, x2, y2, durationMs)}")
        // 刷新拟添加虚线圆标记（非触摸，停止录制后由正式气泡接替）。
        renderTentative()
        // 录制即穿透：同步把这一步模拟到目标 App，忽略窗口内不再回录合成手势。
        replayRecorded(point)
    }

    private fun replayRecorded(point: ClickPoint) {
        scope.launch {
            val resolution = ChannelResolver.resolve(ClickerCoordinator.channelMode())
            val channel = resolution.channel
            if (channel == null) {
                Toast.makeText(this@ClickerFloatingService, "动作已录制，自动模拟失败：${resolution.reason}", Toast.LENGTH_LONG).show()
                return@launch
            }
            // 关键：必须在注入合成手势【之前】先挂上忽略窗口。原实现是等 channel.tap 回调完成后才挂载，
            // 合成手势的 DOWN/MOVE/UP 在此之前已到达捕获层并被回录；回录点又触发回放，形成自反馈叠加
            // （一个物理点击叠出 2~4 个点）。先挂窗口，注入序列全程落在忽略期内，不再回录。
            armRecordPassthrough(point.touchDurationMs)
            val outcome = when (point.type) {
                PointActionType.SWIPE -> channel.swipe(
                    point.x1.toInt(), point.y1.toInt(), point.x2.toInt(), point.y2.toInt(),
                    point.touchDurationMs.coerceAtLeast(1L)
                )
                PointActionType.HOME -> channel.goHome()
                PointActionType.BACK -> channel.goBack()
                else -> channel.tap(point.x1.toInt(), point.y1.toInt(), point.touchDurationMs.coerceAtLeast(30L))
            }
            Log.i(TAG, "录制穿透回放 ${point.type} (${point.x1.toInt()},${point.y1.toInt()}) -> $outcome")
            // 派发结束后再延长一次忽略窗，兜底收尾/迟到事件。
            armRecordPassthrough(point.touchDurationMs)
        }
    }

    /** 合成回放期间把捕获层列入忽略窗口，避免回放手势被再次记录。 */
    private fun armRecordPassthrough(touchDurationMs: Long) {
        val until = SystemClock.uptimeMillis() +
            RECORD_REPLAY_TRIGGER_MS + touchDurationMs.coerceAtLeast(1L) + RECORD_IGNORE_EXTRA_MS
        recordOverlay?.let { it.ignoreUntilMillis = maxOf(it.ignoreUntilMillis, until) }
        recordPassthroughJob?.cancel()
        recordPassthroughJob = scope.launch {
            while (isActive) {
                val remaining = until - SystemClock.uptimeMillis()
                if (remaining <= 0L) break
                delay(remaining)
            }
        }
    }

    private fun refreshLine() {
        val line = swipeLine ?: return
        val swipe = ClickerCoordinator.profile.value.points.firstOrNull {
            it.type == PointActionType.SWIPE
        } ?: return
        line.setLine(swipe.x1, swipe.y1, swipe.x2, swipe.y2)
    }

    private fun persistAfterEdit() {
        ClickerCoordinator.saveActiveProfile()
        rebuildBubbles()
    }

    private fun baseParams(w: Int, h: Int, gravity: Int) =
        WindowManager.LayoutParams(w, h, chosenOverlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { this.gravity = gravity }

    private fun fullScreenParams() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            chosenOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSPARENT
        )

    private fun chosenOverlayType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            return WindowManager.LayoutParams.TYPE_PHONE
        }
        return if (TapAccessibilityService.instance != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private fun addManaged(view: View, params: WindowManager.LayoutParams): Boolean {
        val useA11y = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            params.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        if (useA11y && TapAccessibilityService.addOverlayView(view, params)) {
            overlayHosts[view] = true
            return true
        }
        if (useA11y) {
            Log.w("TapFloat", "无障碍 overlay addView 失败，回退应用悬浮窗 type=2032")
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val ok = runCatching { wm.addView(view, params); true }
            .onFailure { Log.e("TapFloat", "应用悬浮窗 addView 失败: ${it.message}", it) }
            .onSuccess { overlayHosts[view] = false }
            .getOrElse { false }
        return ok
    }

    private fun updateManaged(view: View, params: WindowManager.LayoutParams) {
        val byA11y = overlayHosts[view] == true
        if (byA11y) {
            if (TapAccessibilityService.updateOverlayView(view, params)) return
        } else {
            runCatching { wm.updateViewLayout(view, params); return }
        }
        TapAccessibilityService.updateOverlayView(view, params)
    }

    private fun removeManaged(view: View) {
        if (overlayHosts[view] == true) TapAccessibilityService.removeOverlayView(view)
        else runCatching { wm.removeView(view) }
        overlayHosts.remove(view)
    }

    override fun onDestroy() {
        profileJob?.cancel(); stateJob?.cancel()
        recordPassthroughJob?.cancel()
        bubbles.values.flatten().forEach { removeManaged(it) }
        bubbles.clear()
        swipeLine?.let { removeManaged(it) }
        swipeLine = null
        recordOverlay?.let { removeManaged(it) }
        recordOverlay = null
        clearTentative()
        feedbackView?.let { removeManaged(it) }
        feedbackView = null
        panel?.let { removeManaged(it) }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
