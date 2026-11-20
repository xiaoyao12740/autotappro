package com.aitap.autotappro.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.aitap.autotappro.R
import com.aitap.autotappro.core.RunState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 侧边窄条面板。
 * 展开态：竖排完整圆形按钮 开始/暂停、录制、停止、点位显隐、＋添加、－删除、✕关闭；可整条拖动。
 * 折叠态：单个圆形钮（☰），单击展开面板；开始/暂停只在展开面板内完成；拖动移动。
 *
 * 点位显隐按钮使用简笔画眼睛图标：睁眼（无斜杠）= 点位可见；闭眼带斜杠 = 点位隐藏。
 * 每个按钮自身带完整圆形背景、零内边距、图标居中，不被窄条裁切。
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

    private val btnSize = (36 * density).roundToInt()
    private val knobSize = (36 * density).roundToInt()
    private val gap = (5 * density).roundToInt()
    private val panelWidth = btnSize

    private var barBtn: TextView? = null
    private var recordBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var pointsBtn: ImageView? = null
    private var addBtn: TextView? = null
    private var removeBtn: TextView? = null
    private var closeBtn: TextView? = null
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
        barBtn = makeBtn("▶", "开始/暂停")
        recordBtn = makeBtn("●", "录制")
        stopBtn = makeBtn("⏹", "停止")
        pointsBtn = makeIconBtn(R.drawable.ic_eye, "隐藏/显示点位")
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
            background = context.getDrawable(R.drawable.bg_panel_btn)
        }
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
        // 展开态：活动状态才显示独立急停钮；折叠圆钮固定为展开菜单图标。
        stopBtn?.visibility = if (active) VISIBLE else GONE
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
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downRawX).roundToInt()
                val dy = (e.rawY - downRawY).roundToInt()
                if (abs(dx) + abs(dy) > 6) {
                    moved = true
                    panelX = (startX + dx).coerceIn(0, screenW - width.coerceAtLeast(1))
                    panelY = (startY + dy).coerceIn(0, screenH - height.coerceAtLeast(1))
                    callbacks.onPanelMoved(panelX, panelY)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) dispatchTapAt(e.rawX, e.rawY)
                else callbacks.onPanelDragEnded()
            }
            MotionEvent.ACTION_CANCEL -> {
            }
        }
        return true
    }

    /** 短按命中：先交给子 View 自己的 OnClickListener；折叠圆钮单击为展开面板。 */
    private fun dispatchTapAt(rawX: Float, rawY: Float) {
        val v = findChildAt(rawX, rawY)
        if (v == null) return
        if (!v.isEnabled) return
        if (v === collapseKnob) {
            // 折叠态单击：展开面板；开始/暂停在展开面板内完成。
            callbacks.onToggleCollapseKnob()
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
