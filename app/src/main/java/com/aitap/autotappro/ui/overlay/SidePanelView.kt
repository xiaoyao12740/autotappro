package com.aitap.autotappro.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.aitap.autotappro.R
import com.aitap.autotappro.core.L
import com.aitap.autotappro.core.RunState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 侧边窄条面板。
 *
 * 按状态显隐（不出现当前状态用不到的按钮）：
 * - 空闲（未录制）：收起钮、启动、录制、眼睛、＋、－、退出。
 * - 录制中：停止录制（录制钮变色）、眼睛、退出；隐藏启动 / ＋ / －。
 * - 运行 / 暂停：停止（点后回空闲，钮变回启动）、眼睛、收起、退出；隐藏录制 / ＋ / －。
 *
 * 折叠态：仅单个圆钮；短按 = 启动 / 停止切换，长按 = 展开面板，拖动 = 移动位置。
 *
 * 点位显隐按钮使用简笔画眼睛图标：睁眼（无斜杠）= 点位可见；闭眼带斜杠 = 点位隐藏。
 * 每个按钮自身带完整圆形背景、零内边距、图标居中，不被窄条裁切。
 */
class SidePanelView(
    context: Context,
    screenW: Int,
    screenH: Int,
    private val callbacks: Callbacks
) : FrameLayout(context) {

    /** 可夹取边界（随横竖屏切换由服务层刷新）。 */
    private var boundW = screenW
    private var boundH = screenH

    interface Callbacks {
        /** 启停切换：空闲启动；运行 / 暂停则停止回空闲。 */
        fun onToggleRun()
        fun onToggleRecord()
        fun onAddPoint()
        fun onRemoveSelected()
        fun onClosePanel()
        fun onTogglePointsVisible()
        fun onPanelMoved(x: Int, y: Int)
        fun onPanelDragEnded()
        fun onPanelLayoutChanged()
        fun onToggleCollapseKnob()
        fun onPanelCollapsedChanged(collapsed: Boolean)
    }

    private val density = resources.displayMetrics.density
    private var collapsed = false
    private var panelX = 24
    private var panelY = 240

    private val btnSize = (36 * density).roundToInt()
    private val knobSize = (36 * density).roundToInt()
    private val gap = (5 * density).roundToInt()
    private val panelWidth = btnSize

    private var barBtn: TextView? = null
    private var recordBtn: TextView? = null
    private var pointsBtn: ImageView? = null
    private var addBtn: TextView? = null
    private var removeBtn: TextView? = null
    private var closeBtn: TextView? = null
    private var collapseKnob: TextView? = null
    private var expandKnob: TextView? = null
    private var channelLabel: TextView? = null

    /** 折叠窄条右上角的工作状态角标：▶=待启动，‖=运行/暂停中。 */
    private var statusBadge: TextView? = null
    private val badgeSize = (14 * density).roundToInt()

    /** 当前是否处于运行/暂停等活动状态。 */
    private var active = false

    /** 最近一次收到的运行状态，供通道就绪态变化后重算 active。 */
    private var lastState: RunState = RunState.Idle

    /** 当前是否处于录制态。 */
    private var recording = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    /** 折叠态长按展开。 */
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        L.i("TapPanel", "longPress runnable fired moved=$moved longFired=$longPressFired")
        if (!moved && !longPressFired) {
            longPressFired = true
            callbacks.onToggleCollapseKnob()
        }
    }

    private val longPressMs = 500L

    private fun logTouch(action: String, e: MotionEvent) {
        L.i("TapPanel", "touch $action collapsed=$collapsed moved=$moved longFired=$longPressFired " +
            "raw=(${e.rawX.toInt()},${e.rawY.toInt()}) panel=($panelX,$panelY)")
    }

    init {
        setBackgroundResource(R.drawable.bg_panel)
        val vPad = (4 * density).roundToInt()
        setPadding(0, vPad, 0, vPad)
        buildExpandedButtons()
        buildKnob()
    }

    private fun frameLp(w: Int, h: Int, gravity: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply { this.gravity = gravity }

    private fun buildExpandedButtons() {
        channelLabel = TextView(context).apply {
            text = "未就绪"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D5DCE4"))
            textSize = 9f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = frameLp(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (24 * density).roundToInt(), Gravity.CENTER_HORIZONTAL
            )
        }
        addView(channelLabel)
        expandKnob = makeKnob("⟨")
        barBtn = makeBtn("▶", "启动/停止")
        recordBtn = makeBtn("●", "录制")
        pointsBtn = makeIconBtn(R.drawable.ic_eye, "隐藏/显示点位")
        addBtn = makeBtn("＋", "添加点位")
        removeBtn = makeBtn("－", "删除选中")
        closeBtn = makeBtn("✕", "关闭")

        barBtn?.setOnClickListener { callbacks.onToggleRun() }
        recordBtn?.setOnClickListener { callbacks.onToggleRecord() }
        pointsBtn?.setOnClickListener { callbacks.onTogglePointsVisible() }
        addBtn?.setOnClickListener { callbacks.onAddPoint() }
        removeBtn?.setOnClickListener { callbacks.onRemoveSelected() }
        closeBtn?.setOnClickListener { callbacks.onClosePanel() }
    }

    private fun buildKnob() {
        collapseKnob = TextView(context).apply {
            text = "☰"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = frameLp(knobSize, knobSize, Gravity.CENTER)
            background = context.getDrawable(R.drawable.bg_panel_btn)
        }
        statusBadge = TextView(context).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 8.5f
            includeFontPadding = false
            minimumWidth = 0
            minimumHeight = 0
            visibility = GONE
            background = context.getDrawable(R.drawable.bg_panel_badge)
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END)
        }
        addView(statusBadge)
    }

    /** 纯文字圆形按钮：零内边距、零最小宽高、居中、完整圆形背景，图标不被窄条裁切。 */
    private fun makeBtn(text: String, desc: String): TextView {
        val b = TextView(context).apply {
            this.text = text
            contentDescription = desc
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.bg_panel_btn)
            layoutParams = frameLp(btnSize, btnSize, Gravity.CENTER_HORIZONTAL)
        }
        addView(b)
        return b
    }

    /** 矢量图标圆形按钮（眼睛显隐）。 */
    private fun makeIconBtn(resId: Int, desc: String): ImageView {
        val v = ImageView(context).apply {
            setImageResource(resId)
            contentDescription = desc
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (5 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            background = context.getDrawable(R.drawable.bg_panel_btn)
            layoutParams = frameLp(btnSize, btnSize, Gravity.CENTER_HORIZONTAL)
        }
        addView(v)
        return v
    }

    private fun makeKnob(text: String): TextView {
        val t = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = frameLp(btnSize, btnSize, Gravity.CENTER_HORIZONTAL)
            background = context.getDrawable(R.drawable.bg_panel_btn)
        }
        addView(t)
        return t
    }

    private var channelReady: Boolean = true

    fun setChannelText(text: String) {
        channelLabel?.text = text
    }

    /**
     * 通道就绪态：未就绪（NONE）时启动钮彻底置灰，标签红色明确警告，禁止伪装运行。
     */
    fun setChannelReady(ready: Boolean, label: String) {
        channelReady = ready
        channelLabel?.text = label
        channelLabel?.setTextColor(
            if (ready) Color.WHITE else Color.argb(255, 255, 96, 96)
        )
        barBtn?.isEnabled = ready
        barBtn?.alpha = if (ready) 1f else 0.35f
        // 通道就绪态变化后按最近运行状态重算活动谓词，避免录制/编辑钮显隐不同步。
        recomputeActive()
    }

    private fun recomputeActive() {
        active = channelReady && (lastState == RunState.Running || lastState == RunState.Paused)
    }

    /** 录制态按钮：录制中显红色方块■，空闲显灰色圆点●。 */
    fun setRecordingState(recording: Boolean) {
        this.recording = recording
        recordBtn?.text = if (recording) "■" else "●"
        recordBtn?.setTextColor(
            if (recording) Color.argb(255, 255, 96, 96) else Color.WHITE
        )
        // 录制态保护：禁用添加/删除等编辑钮；收起钮在录制态已由谓词隐藏。
        val editControls = listOfNotNull(addBtn, removeBtn)
        editControls.forEach { v ->
            v.isEnabled = !recording
            v.alpha = if (recording) 0.35f else 1f
        }
        refreshVisibility()
    }

    fun setRunState(state: RunState) {
        lastState = state
        recomputeActive()
        barBtn?.text = when (state) {
            RunState.Running, RunState.Paused -> "■"
            else -> "▶"
        }
        refreshVisibility()
    }

    /** 点位显隐：可见=睁眼；隐藏=闭眼带斜杠。 */
    fun setPointsVisible(visible: Boolean) {
        pointsBtn?.setImageResource(if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off)
    }

    fun setPanelPosition(x: Int, y: Int) {
        panelX = x; panelY = y
    }

    fun panelPosition(): Pair<Int, Int> = panelX to panelY

    fun panelWidthPx(): Int = width.coerceAtLeast(btnSize)

    fun panelHeightPx(): Int = height.coerceAtLeast(btnSize)

    /** 横竖屏切换后刷新夹取边界。 */
    fun updateMetrics(w: Int, h: Int) {
        boundW = w
        boundH = h
    }

    fun isCollapsed(): Boolean = collapsed

    fun toggleCollapsed() = applyCollapsed(!collapsed)

    /** 由服务按持久化的 panelCollapsed 初始态设置。 */
    fun applyInitial(collapse: Boolean) {
        collapsed = collapse
        refreshVisibility()
        if (collapse) {
            if (collapseKnob?.parent == null) addView(collapseKnob)
        } else {
            removeView(collapseKnob)
        }
        post { callbacks.onPanelLayoutChanged() }
    }

    private fun applyCollapsed(collapse: Boolean) {
        applyInitial(collapse)
        callbacks.onPanelCollapsedChanged(collapse)
    }

    // ---- 状态显隐谓词 ----

    private val showChannel get() = !collapsed
    private val showExpandKnob get() = !collapsed && !recording
    private val showBar get() = !collapsed && !recording
    private val showRecord get() = !collapsed && !active
    private val showEye get() = !collapsed
    private val showEdit get() = !collapsed && !recording && !active
    private val showClose get() = !collapsed

    private fun refreshVisibility() {
        channelLabel?.visibility = if (showChannel) VISIBLE else GONE
        expandKnob?.visibility = if (showExpandKnob) VISIBLE else GONE
        barBtn?.visibility = if (showBar) VISIBLE else GONE
        recordBtn?.visibility = if (showRecord) VISIBLE else GONE
        pointsBtn?.visibility = if (showEye) VISIBLE else GONE
        addBtn?.visibility = if (showEdit) VISIBLE else GONE
        removeBtn?.visibility = if (showEdit) VISIBLE else GONE
        closeBtn?.visibility = if (showClose) VISIBLE else GONE
        refreshBadge()
    }

    /** 折叠窄条右上角角标：仅折叠态显示；‖=运行/暂停中，▶=待启动。 */
    private fun refreshBadge() {
        val badge = statusBadge ?: return
        badge.visibility = if (collapsed) VISIBLE else GONE
        if (active) {
            badge.text = "‖"
            badge.setTextColor(Color.argb(255, 76, 217, 100))
        } else {
            badge.text = "▶"
            badge.setTextColor(Color.WHITE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度固定为按钮宽；高度按可见按钮/标签数量纵向堆叠。
        val padH = paddingLeft + paddingRight
        val padV = paddingTop + paddingBottom
        val visible = orderedChildren()
        val count = visible.size
        val w = panelWidth + padH
        val h = if (count == 0) btnSize + padV
        else btnSize * count + gap * (count - 1) + padV
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val padT = paddingTop
        val centerX = (r - l) / 2
        var top = padT
        for (child in orderedChildren()) {
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val left = centerX - cw / 2
            child.layout(left, top, left + cw, top + ch)
            top += ch + gap
        }
        // 状态角标不参与纵向堆叠，单独钉在窄条右上角。
        statusBadge?.let { badge ->
            if (badge.visibility == VISIBLE) {
                val bl = width - badgeSize
                badge.layout(bl, 0, bl + badgeSize, badgeSize)
            }
        }
    }

    private fun orderedChildren(): List<View> = listOfNotNull(
        channelLabel?.takeIf { showChannel },
        expandKnob?.takeIf { showExpandKnob },
        barBtn?.takeIf { showBar },
        recordBtn?.takeIf { showRecord },
        pointsBtn?.takeIf { showEye },
        addBtn?.takeIf { showEdit },
        removeBtn?.takeIf { showEdit },
        closeBtn?.takeIf { showClose },
        collapseKnob?.takeIf { collapsed }
    )

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 窄条整条统一处理，避免子 Button 的点击消费打断 DOWN/UP 链路。
        handleTouch(ev)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = handleTouch(e)

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                logTouch("DOWN", e)
                downRawX = e.rawX; downRawY = e.rawY
                startX = panelX; startY = panelY
                moved = false
                longPressFired = false
                // 仅折叠圆钮需要长按展开；展开态子按钮各自处理点击。
                if (collapsed) {
                    longPressHandler.postDelayed(longPressRunnable, longPressMs)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downRawX).roundToInt()
                val dy = (e.rawY - downRawY).roundToInt()
                if (abs(dx) + abs(dy) > 6) {
                    if (!moved) L.i("TapPanel", "move beyond slop dx=$dx dy=$dy -> cancel long press")
                    moved = true
                    longPressHandler.removeCallbacks(longPressRunnable)
                    panelX = (startX + dx).coerceIn(0, boundW - width.coerceAtLeast(1))
                    panelY = (startY + dy).coerceIn(0, boundH - height.coerceAtLeast(1))
                    callbacks.onPanelMoved(panelX, panelY)
                }
            }
            MotionEvent.ACTION_UP -> {
                logTouch("UP", e)
                longPressHandler.removeCallbacks(longPressRunnable)
                if (!longPressFired && !moved) dispatchTapAt(e.rawX, e.rawY)
                else if (moved) callbacks.onPanelDragEnded()
            }
            MotionEvent.ACTION_CANCEL -> {
                logTouch("CANCEL", e)
                longPressHandler.removeCallbacks(longPressRunnable)
            }
        }
        return true
    }

    /** 短按命中：先交给子 View 自己的 OnClickListener；折叠圆钮短按=启停，长按已在 DOWN 后触发展开。 */
    private fun dispatchTapAt(rawX: Float, rawY: Float) {
        val v = findChildAt(rawX, rawY)
        L.i("TapPanel", "dispatchTapAt raw=(${rawX.toInt()},${rawY.toInt()}) hit=${v?.javaClass?.simpleName} " +
            "text=${(v as? TextView)?.text} enabled=${v?.isEnabled}")
        if (v == null) return
        if (!v.isEnabled) return
        if (v === collapseKnob) {
            callbacks.onToggleRun()
            return
        }
        if (v === expandKnob) {
            callbacks.onToggleCollapseKnob()
            return
        }
        v.performClick()
    }

    private fun findChildAt(rawX: Float, rawY: Float): View? {
        val loc = IntArray(2)
        var hit: View? = null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != VISIBLE) continue
            if (child === statusBadge) continue
            child.getLocationOnScreen(loc)
            val r = android.graphics.Rect(loc[0], loc[1], loc[0] + child.width, loc[1] + child.height)
            L.i("TapPanel", "  child #$i ${(child as? TextView)?.text ?: child.javaClass.simpleName} " +
                "rect=(${loc[0]},${loc[1]},${loc[0] + child.width},${loc[1] + child.height}) enabled=${child.isEnabled}")
            if (hit == null && r.contains(rawX.toInt(), rawY.toInt())) hit = child
        }
        return hit
    }
}
