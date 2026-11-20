package com.aitap.autotappro.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.aitap.autotappro.core.RunState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 侧边窄条面板。
 * 展开态：竖排按钮 开始/暂停、录制、停止、点位显隐、＋添加、－删除、✕关闭；可整条拖动。
 * 折叠态：单个小圆钮，单击 开始/暂停，长按展开面板；拖动移动。
 *
 * 面板宽度与按钮同宽：背景仅作遮挡，不接收背景点击。
 */
class SidePanelView(
    context: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val callbacks: Callbacks
) : FrameLayout(context) {

    interface Callbacks {
        fun onTogglePlayPause()
        fun onEmergencyStop()
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

    private val btnSize = (32 * density).roundToInt()
    private val knobSize = (32 * density).roundToInt()
    private val gap = (3 * density).roundToInt()
    private val panelWidth = btnSize

    private var barBtn: Button? = null
    private var recordBtn: Button? = null
    private var stopBtn: Button? = null
    private var pointsBtn: Button? = null
    private var addBtn: Button? = null
    private var removeBtn: Button? = null
    private var closeBtn: Button? = null
    private var collapseKnob: TextView? = null
    private var expandKnob: TextView? = null
    private var channelLabel: TextView? = null

    /** 当前是否处于运行/暂停等需要急停的活动状态。 */
    private var active = false

    /** 当前是否处于录制态；录制态下编辑钮禁用。 */
    private var recording = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var longPressPending = false
    private var longPressFired = false

    init {
        setBackgroundResource(com.aitap.autotappro.R.drawable.bg_panel)
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
        barBtn = makeBtn("▶", "开始/暂停")
        recordBtn = makeBtn("●", "录制")
        stopBtn = makeBtn("⏹", "停止")
        pointsBtn = makeBtn("◉", "隐藏/显示点位")
        addBtn = makeBtn("＋", "添加点位")
        removeBtn = makeBtn("－", "删除选中")
        closeBtn = makeBtn("✕", "关闭")

        barBtn?.setOnClickListener { callbacks.onTogglePlayPause() }
        recordBtn?.setOnClickListener { callbacks.onToggleRecord() }
        stopBtn?.setOnClickListener { callbacks.onEmergencyStop() }
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
            background = context.getDrawable(com.aitap.autotappro.R.drawable.bg_bubble)
        }
    }

    private fun makeBtn(text: String, desc: String): Button {
        val b = Button(context).apply {
            this.text = text
            contentDescription = desc
            textSize = 13f
            setTextColor(Color.WHITE)
            background = null
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = frameLp(btnSize, btnSize, Gravity.CENTER_HORIZONTAL)
            // 触摸事件由根布局 dispatchTouchEvent 统一判定点击/拖动；
            // 子钮保持默认 clickable 以参与 measure/layout，不在此装 OnTouchListener。
        }
        addView(b)
        return b
    }

    private fun makeKnob(text: String): TextView {
        val t = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = frameLp(btnSize, btnSize, Gravity.CENTER_HORIZONTAL)
            background = null
        }
        addView(t)
        return t
    }

    private var channelReady: Boolean = true

    fun setChannelText(text: String) {
        channelLabel?.text = text
    }

    /**
     * 通道就绪态：未就绪（NONE）时启动钮/急停钮彻底置灰，标签红色明确警告，禁止伪装运行。
     */
    fun setChannelReady(ready: Boolean, label: String) {
        channelReady = ready
        channelLabel?.text = label
        channelLabel?.setTextColor(
            if (ready) Color.WHITE else Color.argb(255, 255, 96, 96)
        )
        barBtn?.isEnabled = ready
        barBtn?.alpha = if (ready) 1f else 0.35f
        stopBtn?.isEnabled = ready
        stopBtn?.alpha = if (ready) 1f else 0.35f
    }

    /** 录制态按钮：录制中显红色方块■，空闲显灰色圆点●。 */
    fun setRecordingState(recording: Boolean) {
        this.recording = recording
        recordBtn?.text = if (recording) "■" else "●"
        recordBtn?.setTextColor(
            if (recording) Color.argb(255, 255, 96, 96) else Color.WHITE
        )
        // 录制态保护：禁用添加/删除/关闭/折叠展开等编辑钮，避免停止录制的触摸落点偏差
        // 被误当成添加点；仅保留停止录制钮与急停钮可点。
        val editControls = listOfNotNull(addBtn, removeBtn, closeBtn, expandKnob, pointsBtn)
        editControls.forEach { v ->
            v.isEnabled = !recording
            v.alpha = if (recording) 0.35f else 1f
        }
    }

    fun setRunState(state: RunState) {
        active = channelReady && (state == RunState.Running || state == RunState.Paused)
        barBtn?.text = when (state) {
            RunState.Running -> "⏸"
            RunState.Paused -> "▶"
            else -> "▶"
        }
        // 展开态：活动状态才显示独立急停钮；折叠态圆钮跟随 开始/暂停 图标。
        stopBtn?.visibility = if (active) VISIBLE else GONE
        collapseKnob?.text = when (state) {
            RunState.Running -> "⏸"
            RunState.Paused -> "▶"
            else -> "☰"
        }
    }

    /** 点位显隐按钮图标：显示中 ◉，已隐藏 ○。 */
    fun setPointsVisible(visible: Boolean) {
        pointsBtn?.text = if (visible) "◉" else "○"
    }

    fun setPanelPosition(x: Int, y: Int) {
        panelX = x; panelY = y
    }

    fun panelPosition(): Pair<Int, Int> = panelX to panelY

    fun panelWidthPx(): Int = width.coerceAtLeast(btnSize)

    fun isCollapsed(): Boolean = collapsed

    fun toggleCollapsed() = applyCollapsed(!collapsed)

    /** 由服务按持久化的 panelCollapsed 初始态设置。 */
    fun applyInitial(collapse: Boolean) {
        collapsed = collapse
        expandKnob?.visibility = if (collapse) GONE else VISIBLE
        channelLabel?.visibility = if (collapse) GONE else VISIBLE
        barBtn?.visibility = if (collapse) GONE else VISIBLE
        recordBtn?.visibility = if (collapse) GONE else VISIBLE
        stopBtn?.visibility = if (collapse || !active) GONE else VISIBLE
        pointsBtn?.visibility = if (collapse) GONE else VISIBLE
        addBtn?.visibility = if (collapse) GONE else VISIBLE
        removeBtn?.visibility = if (collapse) GONE else VISIBLE
        closeBtn?.visibility = if (collapse) GONE else VISIBLE
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度固定为按钮宽；高度按可见按钮/标签数量纵向堆叠。
        val padH = paddingLeft + paddingRight
        val padV = paddingTop + paddingBottom
        val visible = listOfNotNull(
            channelLabel?.takeIf { !collapsed },
            expandKnob?.takeIf { !collapsed },
            barBtn?.takeIf { !collapsed },
            recordBtn?.takeIf { !collapsed },
            stopBtn?.takeIf { !collapsed && active },
            pointsBtn?.takeIf { !collapsed },
            addBtn?.takeIf { !collapsed },
            removeBtn?.takeIf { !collapsed },
            closeBtn?.takeIf { !collapsed },
            collapseKnob?.takeIf { collapsed }
        )
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
        val ordered = listOfNotNull(
            channelLabel?.takeIf { !collapsed },
            expandKnob?.takeIf { !collapsed },
            barBtn?.takeIf { !collapsed },
            recordBtn?.takeIf { !collapsed },
            stopBtn?.takeIf { !collapsed && active },
            pointsBtn?.takeIf { !collapsed },
            addBtn?.takeIf { !collapsed },
            removeBtn?.takeIf { !collapsed },
            closeBtn?.takeIf { !collapsed },
            collapseKnob?.takeIf { collapsed }
        )
        for (child in ordered) {
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val left = centerX - cw / 2
            child.layout(left, top, left + cw, top + ch)
            top += ch + gap
        }
    }

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
                downRawX = e.rawX; downRawY = e.rawY
                startX = panelX; startY = panelY
                moved = false
                longPressPending = true
                longPressFired = false
                // 折叠态：长按展开面板（单击留给 开始/暂停）。
                if (collapsed) {
                    postDelayed({
                        if (longPressPending && !moved) {
                            longPressPending = false
                            longPressFired = true
                            callbacks.onToggleCollapseKnob()
                        }
                    }, 500)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downRawX).roundToInt()
                val dy = (e.rawY - downRawY).roundToInt()
                if (abs(dx) + abs(dy) > 6) {
                    moved = true
                    longPressPending = false
                    panelX = (startX + dx).coerceIn(0, screenW - width.coerceAtLeast(1))
                    panelY = (startY + dy).coerceIn(0, screenH - height.coerceAtLeast(1))
                    callbacks.onPanelMoved(panelX, panelY)
                }
            }
            MotionEvent.ACTION_UP -> {
                longPressPending = false
                if (longPressFired) {
                    // 长按已触发展开：吞掉本次抬起，避免 UP 落点误触刚展开的按钮。
                    longPressFired = false
                    return true
                }
                if (!moved) dispatchTapAt(e.rawX, e.rawY)
                else callbacks.onPanelDragEnded()
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressPending = false
                longPressFired = false
            }
        }
        return true
    }

    /** 短按命中：先交给子 View 自己的 OnClickListener；折叠圆钮单击为 开始/暂停。 */
    private fun dispatchTapAt(rawX: Float, rawY: Float) {
        val v = findChildAt(rawX, rawY)
        if (v == null) return
        if (!v.isEnabled) return
        if (v === collapseKnob) {
            // 折叠态单击：开始/暂停（不再展开）；展开由长按触发。
            callbacks.onTogglePlayPause()
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
            child.getLocationOnScreen(loc)
            val r = android.graphics.Rect(loc[0], loc[1], loc[0] + child.width, loc[1] + child.height)
            if (hit == null && r.contains(rawX.toInt(), rawY.toInt())) hit = child
        }
        return hit
    }
}
