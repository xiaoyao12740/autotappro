package com.aitap.autotappro.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.aitap.autotappro.model.ClickPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 单个点位气泡：圆形编号钮，可拖动定位。
 * SWIPE 由服务层成对创建起点/终点气泡，并叠加一条虚线连线层。
 *
 * 触摸：短按选中；长按弹参数面板；拖动更新坐标（夹取屏内）。
 */
class PointBubbleView(
    context: Context,
    val point: ClickPoint,
    private val isEndpoint: Boolean,
    private val screenW: Int,
    private val screenH: Int,
    private val onDrag: (Float, Float) -> Unit,
    private val onSelect: (PointBubbleView) -> Unit,
    private val onLongPress: (PointBubbleView) -> Unit
) : FrameLayout(context) {

    companion object {
        const val BUBBLE_DP = 40
    }

    private val density = resources.displayMetrics.density
    private val bubblePx = (BUBBLE_DP * density).roundToInt()
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var longPressed = false
    private var longPressRunnable: Runnable? = null

    private val label: TextView

    init {
        label = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFB7E8CB"))
            textSize = 12f
            text = if (isEndpoint) "②${point.id}" else "${point.id}"
            isLongClickable = false
        }
        val lp = LayoutParams(bubblePx, bubblePx, Gravity.CENTER)
        addView(label, lp)
        background = context.getDrawable(com.aitap.autotappro.R.drawable.bg_bubble)
        contentDescription = "点位 ${point.id}"
        isClickable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        handleTouch(e)
        return true
    }

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startX = if (isEndpoint) point.x2 else point.x1
                startY = if (isEndpoint) point.y2 else point.y1
                moved = false; longPressed = false
                onSelect(this)
                longPressRunnable = Runnable {
                    if (!moved && !longPressed) notifyLongPress()
                }.also { postDelayed(it, 500) }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (abs(dx) + abs(dy) > 6f) {
                    moved = true
                    clearLongPressPending()
                    // point 坐标存的是气泡中心；夹取时按中心范围夹取，避免半颗气泡出屏。
                    val half = bubblePx / 2f
                    val nx = (startX + dx).coerceIn(half, screenW - half)
                    val ny = (startY + dy).coerceIn(half, screenH - half)
                    onDrag(nx, ny)
                }
            }
            MotionEvent.ACTION_UP -> {
                clearLongPressPending()
                if (!moved && !longPressed) onSelect(this)
            }
            MotionEvent.ACTION_CANCEL -> clearLongPressPending()
        }
    }

    private fun clearLongPressPending() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    fun notifyLongPress() {
        longPressed = true
        onLongPress(this)
    }

    fun updateLabel() {
        label.text = if (isEndpoint) "②${point.id}" else "${point.id}"
    }

    fun centerX(): Float = if (isEndpoint) point.x2 else point.x1
    fun centerY(): Float = if (isEndpoint) point.y2 else point.y1

    /** 气泡左上角坐标（WindowManager x/y）。 */
    fun left(): Float = centerX() - bubblePx / 2f
    fun top(): Float = centerY() - bubblePx / 2f

    fun bubbleSize() = bubblePx
}

/**
 * SWIPE 起终点之间的虚线连线层（透明，铺满屏幕，仅绘制虚线）。
 */
class SwipeLineView(context: Context) : View(context) {
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    var fromX: Float = 0f; var fromY: Float = 0f
    var toX: Float = 0f; var toY: Float = 0f

    fun setLine(fx: Float, fy: Float, tx: Float, ty: Float) {
        fromX = fx; fromY = fy; toX = tx; toY = ty
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fromX == toX && fromY == toY) return
        canvas.drawLine(fromX, fromY, toX, toY, dashPaint)
    }
}
