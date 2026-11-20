package com.aitap.autotappro.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.aitap.autotappro.model.ClickPoint
import kotlin.math.roundToInt

/**
 * 录制态「拟添加」占位标记：绿色虚线空心圆 + 编号，区别于已确认点位的实心气泡。
 * 纯展示、不可触摸（窗口层另置 NOT_TOUCHABLE），不参与选中/拖动/长按。
 */
class TentativeMarkerView(
    context: Context,
    val point: ClickPoint,
    private val isEndpoint: Boolean,
    private val resolver: (ClickPoint, Boolean) -> Pair<Float, Float>
) : FrameLayout(context) {

    companion object {
        const val MARKER_DP = 40
    }

    private val density = resources.displayMetrics.density
    private val markerPx = (MARKER_DP * density).roundToInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 124, 232, 168)
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 90, 220, 140)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val label: TextView

    init {
        label = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.argb(245, 60, 200, 110))
            textSize = 12f
            text = if (isEndpoint) "②${point.id}" else "${point.id}"
        }
        addView(label, LayoutParams(markerPx, markerPx, Gravity.CENTER))
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "拟添加点位 ${point.id}"
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(markerPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(markerPx, MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (markerPx / 2f) - (4 * density)
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        super.dispatchDraw(canvas)
    }

    fun centerX(): Float = resolver(point, isEndpoint).first
    fun centerY(): Float = resolver(point, isEndpoint).second
    fun markerSize() = markerPx
}
