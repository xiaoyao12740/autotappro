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
 * 展开态：竖排按钮 开始/暂停、＋添加、－删除、✕关闭；可整条拖动。
 * 折叠态：单个小圆钮，单击展开/收起；拖动移动；长按弹关闭确认。
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
        fun onLongPressClose()
        fun onPanelMoved(x: Int, y: Int)
        fun onPanelLayoutChanged()
        fun onToggleCollapseKnob()
        fun onPanelCollapsedChanged(collapsed: Boolean)
    }

    private val density = resources.displayMetrics.density
    private var collapsed = false
    private var panelX = 24
    private var panelY = 240

    private val btnSize = (40 * density).roundToInt()
    private val knobSize = (40 * density).roundToInt()
    private val gap = (6 * density).roundToInt()
    private val panelWidth = (46 * density).roundToInt()

    private var barBtn: Button? = null
    private var recordBtn: Button? = null
    private var stopBtn: Button? = null
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

    init {
        setBackgroundResource(com.aitap.autotappro.R.drawable.bg_panel)
        val pad = (10 * density).roundToInt()
        setPadding(pad, pad, pad, pad)
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
            textSize = 10f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = frameLp(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (32 * density).roundToInt(), Gravity.CENTER_HORIZONTAL)
        }
        addView(channelLabel)
        expandKnob = makeKnob("⟨", 32)
        barBtn = makeBtn("▶", "开始/暂停")
        recordBtn = makeBtn("●", "录制")
        stopBtn = makeBtn("⏹", "停止")
        addBtn = makeBtn("＋", "添加点位")
        removeBtn = makeBtn("－", "删除选中")
        closeBtn = makeBtn("✕", "关闭")

        barBtn?.setOnClickListener { callbacks.onTogglePlayPause() }
        recordBtn?.setOnClickListener { callbacks.onToggleRecord() }
        stopBtn?.setOnClickListener { callbacks.onEmergencyStop() }
        addBtn?.setOnClickListener { callbacks.onAddPoint() }
        removeBtn?.setOnClickListener { callbacks.onRemoveSelected() }
        closeBtn?.setOnClickListener { callbacks.onClosePanel() }
    }

    private fun buildKnob() {
        collapseKnob = TextView(context).apply {
            text = "☰"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = frameLp(knobSize, knobSize, Gravity.CENTER)
            background = context.getDrawable(com.aitap.autotappro.R.drawable.bg_bubble)
            setOnClickListener { callbacks.onToggleCollapseKnob() }
        }
    }

    private fun makeBtn(text: String, desc: String): Button {
        val b = Button(context).apply {
            this.text = text
            contentDescription = desc
            textSize = 16f
            setTextColor(Color.WHITE)
            background = null
            layoutParams = frameLp(btnSize, btnSize, Gravity.CENTER_HORIZONTAL)
            // 触摸事件由根布局 dispatchTouchEvent 统一判定点击/拖动；
            // 子钮保持默认 clickable 以参与 measure/layout，不在此装 OnTouchListener。
        }
        addView(b)
        return b
    }

    private fun makeKnob(text: String, sizeDp: Int): TextView {
        val size = (sizeDp * density).roundToInt()
        val t = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = frameLp(size, size, Gravity.CENTER_HORIZONTAL)
            background = null
            setOnClickListener { callbacks.onToggleCollapseKnob() }
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
        val editControls = listOfNotNull(addBtn, removeBtn, closeBtn, expandKnob)
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
        // 展开态：活动状态才显示独立急停钮；折叠态：运行/暂停时圆钮变 ⏹。
        stopBtn?.visibility = if (active) VISIBLE else GONE
        collapseKnob?.text = if (active) "⏹" else "☰"
    }

    fun setPanelPosition(x: Int, y: Int) {
        panelX = x; panelY = y
    }

    fun panelPosition(): Pair<Int, Int> = panelX to panelY

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
        // 展开态：宽度取按钮宽 + 左右内边距；高度按按钮/钮数量纵向堆叠。
        val padH = paddingLeft + paddingRight
        val padV = paddingTop + paddingBottom
        val visible = listOfNotNull(
            channelLabel?.takeIf { !collapsed },
            expandKnob?.takeIf { !collapsed },
            barBtn?.takeIf { !collapsed },
            recordBtn?.takeIf { !collapsed },
            stopBtn?.takeIf { !collapsed && active },
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            android.util.Log.i("TapPanel", "dispatchTouch DOWN raw=${ev.rawX.roundToInt()},${ev.rawY.roundToInt()} " +
                "loc=${IntArray(2).also { getLocationOnScreen(it) }.contentToString()} size=${width}x$height")
        }
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
                if (collapsed && !active) {
                    postDelayed({
                        if (longPressPending && !moved) callbacks.onLongPressClose()
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
                if (!moved) dispatchTapAt(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_CANCEL -> longPressPending = false
        }
        return true
    }

    /** 短按命中：先交给子 View 自己的 OnClickListener；折叠圆钮/展开钮在折叠切换回调里处理。 */
    private fun dispatchTapAt(rawX: Float, rawY: Float) {
        val v = findChildAt(rawX, rawY)
        if (v == null) {
            android.util.Log.w("TapPanel", "dispatchTapAt 未命中任何子 View raw=${rawX.roundToInt()},${rawY.roundToInt()}")
            return
        }
        if (!v.isEnabled) {
            android.util.Log.w("TapPanel", "dispatchTapAt 命中禁用钮 ${v.contentDescription ?: v.javaClass.simpleName}，忽略")
            return
        }
        android.util.Log.i("TapPanel", "dispatchTapAt 命中 ${v.contentDescription ?: v.javaClass.simpleName}")
        if (v === collapseKnob) {
            // 折叠态：运行/暂停时圆钮是 ⏹ 急停，空闲时才是展开钮。
            if (active) callbacks.onEmergencyStop() else callbacks.onToggleCollapseKnob()
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
            android.util.Log.i("TapPanel", "  child[${child.contentDescription ?: child.javaClass.simpleName}] " +
                "rect=($loc[0],$loc[1])-${loc[0] + child.width},${loc[1] + child.height}")
            if (hit == null && r.contains(rawX.toInt(), rawY.toInt())) hit = child
        }
        return hit
    }
}
