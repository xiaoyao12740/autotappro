package com.aitap.autotappro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.aitap.autotappro.core.L
import android.view.Gravity
import android.view.MotionEvent
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
import com.aitap.autotappro.model.PointCoords
import com.aitap.autotappro.model.channelLabel
import com.aitap.autotappro.ui.overlay.AddActionMenu
import com.aitap.autotappro.ui.overlay.FloatingDialogHost
import com.aitap.autotappro.ui.overlay.PointBubbleView
import com.aitap.autotappro.ui.overlay.PointParamDialog
import com.aitap.autotappro.ui.overlay.RecordCaptureOverlay
import com.aitap.autotappro.ui.overlay.SidePanelView
import com.aitap.autotappro.ui.overlay.SwipeLineView
import com.aitap.autotappro.ui.overlay.TentativeMarkerView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        /** 通知栏急停：只停回放、保留悬浮面板，避免面板被系统弹窗盖住时无法停止。 */
        const val ACTION_STOP_RUNNER = "com.aitap.autotappro.action.STOP_RUNNER"
        const val RECORD_REPLAY_TRIGGER_MS = 100L
        const val RECORD_IGNORE_EXTRA_MS = 250L

        /** 调试期由接收器取用；release 不发调试广播。 */
        @Volatile var instance: ClickerFloatingService? = null
            private set

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
        instance = this
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
        panel?.updateMetrics(screenW, screenH)
        bubbles.values.flatten().forEach { it.updateMetrics(screenW, screenH) }
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
                    L.i(TAG, "captureFrameOffset loc=(${loc[0]},${loc[1]}) params=(${params.x},${params.y})" +
                        " offset=($frameOffsetX,$frameOffsetY) screen=${screenW}x${screenH}")
                    sanitizePanelBounds()
                    // 偏移回写后重排气泡/拟添加标记，避免旋转瞬间仍按旧父帧偏移定位造成位移。
                    rebuildBubbles()
                }
        }
    }

    private fun startForegroundCompat() {
        val channelId = "clicker_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "点击器悬浮面板", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮面板服务存活；面板被遮挡时可在通知栏停止连点"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, ClickerFloatingService::class.java).setAction(ACTION_STOP_RUNNER)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("AutoTapPro 点击器运行中")
            .setContentText("侧边悬浮面板已显示；面板被遮挡时可点下方停止")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止连点", stopPi)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else 0
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(1001, notification, fgsType)
            else startForeground(1001, notification)
        }.onFailure { L.w("TapFloat", "startForeground 失败: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_RUNNER -> {
                // 通知栏急停：停掉回放但保留悬浮面板，便于再次启动。
                ClickRunner.stop()
                ClickerCoordinator.setRunState(RunState.Idle)
                L.i(TAG, "通知栏急停：回放已停止")
                return START_STICKY
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
        // 运行中旋转也刷新 Runner 的真实宽高，避免派发仍按旧朝向边界换算。
        ClickRunner.refreshMetrics()
        panel?.let { p -> panelParams?.let { captureFrameOffset(p, it) } }
        // 横竖屏切换后把面板校正进新边界内，避免旧坐标落到屏外或贴边留白。
        sanitizePanelBounds()
        L.i(TAG, "onConfigurationChanged screen=${screenW}x${screenH} orientation=${newConfig.orientation}")
        rebuildBubbles()
        // 首帧布局可能仍带旧朝向偏移；延迟到窗口完成新朝向布局后再测一次并校正气泡。
        scope.launch {
            delay(250L)
            readRealMetrics()
            ClickRunner.refreshMetrics()
            panel?.let { p -> panelParams?.let { captureFrameOffset(p, it) } }
            sanitizePanelBounds()
            rebuildBubbles()
        }
    }

    /** 面板参数在当前横竖屏真实边界内夹取（扣除悬浮父帧偏移）。 */
    private fun panelMaxX(): Int {
        val w = panel?.panelWidthPx() ?: return 0
        return (screenW - frameOffsetX - w).coerceAtLeast(0)
    }

    private fun panelMaxY(): Int {
        val h = panel?.panelHeightPx() ?: return 0
        return (screenH - frameOffsetY - h).coerceAtLeast(0)
    }

    /** 相对父帧的负向边界：父帧原点在整屏有偏移时，负 params 才能贴到物理左/上边缘。 */
    private fun panelMinX(): Int = -frameOffsetX
    private fun panelMinY(): Int = -frameOffsetY

    private fun sanitizePanelBounds() {
        val p = panel ?: return
        val params = panelParams ?: return
        params.x = params.x.coerceIn(panelMinX(), panelMaxX())
        params.y = params.y.coerceIn(panelMinY(), panelMaxY())
        p.setPanelPosition(params.x, params.y)
        updateManaged(p, params)
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        // 脱挂体检：宿主回收后旧引用 parent==null，先置空，重开走完整重建路径。
        clearDanglingRefs()
        // 部分 ROM 会在无障碍宿主静默回收窗口后，View 仍报告 attached/token 有效，
        // 单纯状态判定无法识别；重开时无条件摘除旧面板引用，确保走完整重建路径。
        panel?.let { old ->
            L.i(TAG, "重开面板：摘除旧视图后重建")
            removeManaged(old)
            overlayHosts.remove(old)
        }
        panel = null
        panelParams = null

        // 录制层先于面板加入：同一应用悬浮窗宿主内后挂载的面板居于上层，录制中停止钮仍可点到。
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
            // 主控面板必须留在应用悬浮窗宿主：无障碍 overlay 宿主在服务重连/通道切换后
            // 会静默回收窗口（View 附着但 Surface 归零，面板不可见），稳定性不达标。
            addManaged(panel!!, params)
            panelParams = params
            captureFrameOffset(panel!!, params)
        } catch (t: Throwable) {
            L.e("TapFloat", "面板 addView 失败: ${t.javaClass.simpleName} ${t.message}", t)
            Toast.makeText(this, "悬浮面板添加失败: ${t.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        attachObservers()
        rebuildBubbles()
    }

    /** 清理已脱挂（parent==null / 未附着 / token 失效 / 宿主已销毁）的残留引用，避免重开被非空守卫拦截。 */
    private fun clearDanglingRefs() {
        panel?.let { p ->
            val a11yHostDead = overlayHosts[p] == true && TapAccessibilityService.instance == null
            val dropped = p.parent == null || !p.isAttachedToWindow || p.windowToken == null || a11yHostDead
            if (dropped) {
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
        // 就绪态按当前模式实时解析（不依赖运行成功后才回填的 activeChannel）：
        // 否则 Root 直连且无障碍未绑定的场景下，播放钮会被永久置灰、无法首次启动。
        scope.launch {
            combine(
                ClickerCoordinator.profile.map { it.channelMode }.distinctUntilChanged(),
                ClickerCoordinator.activeChannel
            ) { mode, _ -> mode }
                .collectLatest { mode ->
                    val res = ChannelResolver.resolve(mode)
                    panel?.setChannelReady(res.kind != ChannelKind.NONE, channelLabel(mode, res.kind))
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
        override fun onToggleRun() {
            L.i(TAG, "面板启停点击 runState=${ClickerCoordinator.runState.value} " +
                "channel=${ClickerCoordinator.activeChannel.value}")
            if (ClickerCoordinator.recording.value.isRecording) {
                Toast.makeText(this@ClickerFloatingService, "录制中请先停止录制", Toast.LENGTH_LONG).show()
                return
            }
            when (ClickerCoordinator.runState.value) {
                RunState.Running, RunState.Paused -> ClickRunner.stop()
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
                L.i(TAG, "录制中忽略添加点位")
                Toast.makeText(this@ClickerFloatingService, "录制中不可添加点位，请先停止录制", Toast.LENGTH_LONG).show()
                return
            }
            L.i(TAG, "面板＋添加点位")
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
                p.x = x.coerceIn(panelMinX(), panelMaxX())
                p.y = y.coerceIn(panelMinY(), panelMaxY())
                panel?.let { updateManaged(it, p) }
            }
        }

        override fun onPanelDragEnded() {
            snapPanelToEdge()
        }

        override fun onPanelLayoutChanged() {
            panel?.let { v ->
                panelParams?.let {
                    updateManaged(v, it)
                    // 布局重排后父帧位置可能已变（横竖屏切换），重测一次偏移并校正气泡。
                    captureFrameOffset(v, it)
                }
            }
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
     * 拖拽松手后吸附到距离最近的一条屏幕边（上 / 下 / 左 / 右均可）；未开启吸附则保持松手位置。
     * 边界按当前横竖屏真实尺寸 + 悬浮父帧偏移计算；贴边目标用视图实测屏幕坐标换算，
     * 避免父帧有偏移时贴边留白。
     */
    private fun snapPanelToEdge() {
        val p = panel ?: return
        val params = panelParams ?: return
        if (!ClickerCoordinator.profile.value.snapToEdge) return
        val w = p.panelWidthPx()
        val h = p.panelHeightPx()
        // 距离用 params（相对父帧）+ 实测父帧偏移换算绝对坐标；不读 getLocationOnScreen——
        // updateViewLayout 后视图帧异步提交，旧坐标会导致四边距离算错、全部误吸到同一边。
        val absX = params.x + frameOffsetX
        val absY = params.y + frameOffsetY
        val distL = absX
        val distR = screenW - (absX + w)
        val distT = absY
        val distB = screenH - (absY + h)
        when (minOf(distL, distR, distT, distB)) {
            distL -> params.x = panelMinX()
            distR -> params.x = panelMaxX()
            distT -> params.y = panelMinY()
            else -> params.y = panelMaxY()
        }
        p.setPanelPosition(params.x, params.y)
        updateManaged(p, params)
    }

    // ---- 点位气泡 ----

    private fun addPointOfType(type: PointActionType) {
        val cx = (screenW / 2f)
        val cy = (screenH / 2f)
        ClickerCoordinator.addPoint(type, cx, cy, cx, cy)
        ClickerCoordinator.profile.value.points.lastOrNull()?.let {
            PointCoords.markCurrent(it, screenW, screenH)
        }
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
        // 拖拽落在当前朝向，刷新点位的朝向标识与源宽高，避免下次换算按旧朝向处理。
        PointCoords.markCurrent(point, screenW, screenH)
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
            resolver = coordResolver,
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
                        this, wm, point, screenW, screenH,
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
            .onFailure { L.e("TapFloat", "气泡 addView 失败 #${point.id}: ${it.message}", it) }
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
    /** 点位坐标按当前朝向解析给气泡/标记/连线使用。 */
    private val coordResolver: (ClickPoint, Boolean) -> Pair<Float, Float> = { point, endpoint ->
        if (endpoint) PointCoords.resolveEnd(point, screenW, screenH)
        else PointCoords.resolveStart(point, screenW, screenH)
    }

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
                val start = TentativeMarkerView(this, point, isEndpoint = false, coordResolver)
                val end = TentativeMarkerView(this, point, isEndpoint = true, coordResolver)
                runCatching { addManaged(start, tentativeParams(start)) }
                runCatching { addManaged(end, tentativeParams(end)) }
                tentative.add(start)
                tentative.add(end)
                val (tx1, ty1) = PointCoords.resolveStart(point, screenW, screenH)
                val (tx2, ty2) = PointCoords.resolveEnd(point, screenW, screenH)
                line.setLine(tx1, ty1, tx2, ty2)
            } else {
                val m = TentativeMarkerView(this, point, isEndpoint = false, coordResolver)
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
        // 录制层留在应用悬浮窗宿主，先于面板挂载：同宿主内面板后 addView 居于上层，
        // 录制中停止钮仍可点到。无障碍 overlay 层级恒高于普通 overlay，可触摸时会反过来
        // 盖住面板截留停止钮，且 A11y 宿主存在静默回收风险，故录制层不迁 A11y。
        addManaged(view, params)
        recordOverlay = view
        recordParams = params
    }

    /** 服务销毁时摘除全屏捕获层并清空引用与忽略窗协程。 */
    private fun removeRecordOverlay() {
        recordPassthroughJob?.cancel()
        recordPassthroughJob = null
        recordOverlay?.let { removeManaged(it) }
        recordOverlay = null
        recordParams = null
    }

    /** 录制层触摸态：录制中可触摸以截留手势记录；其余态 NOT_TOUCHABLE 穿透到目标 App。 */
    private fun setRecordTouchable(touchable: Boolean) {
        val view = recordOverlay ?: run {
            L.w(TAG, "setRecordTouchable 跳过：recordOverlay=null touchable=$touchable"); return
        }
        val params = recordParams ?: run {
            L.w(TAG, "setRecordTouchable 跳过：recordParams=null touchable=$touchable"); return
        }
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val hasFlag = (params.flags and flag) != 0
        val needFlag = !touchable
        L.i(TAG, "setRecordTouchable touchable=$touchable hasFlag=$hasFlag needFlag=$needFlag " +
            "flagsBefore=${params.flags} hostA11y=${overlayHosts[view]}")
        if (hasFlag == needFlag) return
        params.flags = if (needFlag) params.flags or flag else params.flags and flag.inv()
        L.i(TAG, "setRecordTouchable flagsAfter=${params.flags} -> updateManaged")
        // 用 updateViewLayout 改 flags，不能移除后重 addView——重加会把录制层提到 z-order 最顶层，
        // 盖住面板并截留停止录制钮的触摸（表现为录不停）。录制层必须始终位于面板下层。
        val ok = updateManagedChecked(view, params)
        L.i(TAG, "setRecordTouchable updateManaged ok=$ok")
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
        // 录制落在哪种朝向，就把该朝向与源宽高记到点位上。
        PointCoords.markCurrent(point, screenW, screenH)
        val count = ClickerCoordinator.recording.value.recordedCount
        // 系统可能静默 Toast，改用顶部悬浮反馈条显示类型与起止坐标。
        showFeedback(recordFeedbackText(count, type, x1, y1, x2, y2, durationMs))
        L.i(TAG, "录制 #$count ${recordFeedbackText(count, type, x1, y1, x2, y2, durationMs)}")
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
            L.i(TAG, "录制穿透回放 ${point.type} (${point.x1.toInt()},${point.y1.toInt()}) -> $outcome")
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
        val (sx, sy) = PointCoords.resolveStart(swipe, screenW, screenH)
        val (ex, ey) = PointCoords.resolveEnd(swipe, screenW, screenH)
        line.setLine(sx, sy, ex, ey)
    }

    private fun persistAfterEdit() {
        ClickerCoordinator.saveActiveProfile()
        rebuildBubbles()
    }

    /**
     * 调试专用：在收起圆钮中心模拟一次真实 DOWN → 保持 holdMs → UP，
     * 直接走面板自身 dispatchTouchEvent 状态机，用于在 adb 无法长按注入的 ROM 上
     * 验证「长按展开、短按启停」判定。仅 debug 广播调用，release 不暴露入口。
     */
    /** 调试专用：在指定绝对坐标加一个 CLICK 点并打上当前朝向元数据，供横纵屏换算复测。 */
    fun debugAddPointAt(x: Float, y: Float) {
        ClickerCoordinator.addPoint(PointActionType.CLICK, x, y, x, y)
        ClickerCoordinator.profile.value.points.lastOrNull()?.let {
            PointCoords.markCurrent(it, screenW, screenH)
        }
        rebuildBubbles()
        L.i(TAG, "DEBUG_ADD_AT ($x,$y) screen=${screenW}x${screenH}")
    }

    /** 调试专用：把面板移到给定（相对父帧）坐标并可选松手吸附，回写夹取后的边界，供四边停留逻辑真机验证。 */
    fun debugMovePanelTo(x: Int, y: Int, snap: Boolean) {
        val p = panel ?: run { L.i(TAG, "DEBUG_MOVE panel=null"); return }
        val params = panelParams ?: run { L.i(TAG, "DEBUG_MOVE params=null"); return }
        params.x = x.coerceIn(panelMinX(), panelMaxX())
        params.y = y.coerceIn(panelMinY(), panelMaxY())
        p.setPanelPosition(params.x, params.y)
        updateManaged(p, params)
        if (snap) snapPanelToEdge()
        L.i(TAG, "DEBUG_MOVE target=($x,$y) snap=$snap -> params=(${params.x},${params.y}) " +
            "boundsX=[${panelMinX()},${panelMaxX()}] boundsY=[${panelMinY()},${panelMaxY()}] " +
            "offset=($frameOffsetX,$frameOffsetY) screen=${screenW}x${screenH}")
    }

    fun debugSimulateHold(holdMs: Long) {
        val p = panel ?: run { L.i(TAG, "DEBUG_HOLD panel=null"); return }
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        val cx = loc[0] + p.width / 2f
        val cy = loc[1] + p.height / 2f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0)
        L.i(TAG, "DEBUG_HOLD down center=($cx,$cy) collapsed=${p.isCollapsed()}")
        p.dispatchTouchEvent(down)
        Handler(Looper.getMainLooper()).postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(upTime, upTime, MotionEvent.ACTION_UP, cx, cy, 0)
            p.dispatchTouchEvent(up)
            L.i(TAG, "DEBUG_HOLD up dispatched collapsed=${p.isCollapsed()}")
        }, holdMs)
    }

    private fun baseParams(w: Int, h: Int, gravity: Int) =
        // NOT_TOUCH_MODAL：窗口矩形外的触摸穿透到下层应用，不被本悬浮窗模态截留；
        // 缺该标志时小窗口也会按模态处理，易被系统判定为“遮挡/拦截操作”而弹风控提醒。
        WindowManager.LayoutParams(
            w, h, chosenOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    private fun fullScreenParams() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            chosenOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSPARENT
        )

    private fun chosenOverlayType(): Int = appOverlayType()

    private fun a11yOverlayType(): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) appOverlayType()
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun appOverlayType(): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    /**
     * @param preferA11y true 时，无障碍服务在线则优先挂 TYPE_ACCESSIBILITY_OVERLAY（与成熟三方
     *        连点器一致：不可触摸/穿透类标记层不计入普通悬浮窗风控统计）；无障碍未在线或挂载
     *        失败时回退应用悬浮窗宿主。可触摸主控面板必须传 false，由应用悬浮窗持有，保证生命周期稳定。
     * 宿主归属在 addView 成功后记录，后续 update/remove 必须沿用同一宿主与窗口类型。
     */
    private fun addManaged(view: View, params: WindowManager.LayoutParams, preferA11y: Boolean = false): Boolean {
        if (preferA11y && TapAccessibilityService.instance != null) {
            params.type = a11yOverlayType()
            if (TapAccessibilityService.addOverlayView(view, params)) {
                overlayHosts[view] = true
                return true
            }
            L.w("TapFloat", "无障碍 overlay 挂载失败，回退普通悬浮窗: ${view.javaClass.simpleName}")
        }
        params.type = appOverlayType()
        val ok = runCatching { wm.addView(view, params); true }
            .onFailure { L.e("TapFloat", "应用悬浮窗 addView 失败: ${it.message}", it) }
            .onSuccess { overlayHosts[view] = false }
            .getOrElse { false }
        return ok
    }

    private fun updateManaged(view: View, params: WindowManager.LayoutParams) {
        updateManagedChecked(view, params)
    }

    private fun updateManagedChecked(view: View, params: WindowManager.LayoutParams): Boolean {
        // 按 addView 时记录的宿主归一化窗口类型，避免 update 传入与原窗口不同的 type。
        val byA11y = overlayHosts[view] == true
        params.type = if (byA11y) a11yOverlayType() else appOverlayType()
        if (byA11y) {
            if (TapAccessibilityService.updateOverlayView(view, params)) return true
            L.e("TapFloat", "updateManaged A11y 失败: ${view.javaClass.simpleName}")
        } else {
            return runCatching { wm.updateViewLayout(view, params); true }
                .onFailure { L.e("TapFloat", "updateManaged 普通窗失败: ${view.javaClass.simpleName} ${it.message}", it) }
                .getOrDefault(false)
        }
        return TapAccessibilityService.updateOverlayView(view, params)
    }

    private fun removeManaged(view: View) {
        if (overlayHosts[view] == true) TapAccessibilityService.removeOverlayView(view)
        else runCatching { wm.removeView(view) }
        overlayHosts.remove(view)
    }

    override fun onDestroy() {
        instance = null
        profileJob?.cancel(); stateJob?.cancel()
        bubbles.values.flatten().forEach { removeManaged(it) }
        bubbles.clear()
        swipeLine?.let { removeManaged(it) }
        swipeLine = null
        removeRecordOverlay()
        clearTentative()
        feedbackView?.let { removeManaged(it) }
        feedbackView = null
        panel?.let { removeManaged(it) }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
