package com.aitap.autotappro.ui.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aitap.autotappro.model.ClickPoint
import com.aitap.autotappro.model.PointActionType

/**
 * 点位参数悬浮面板（服务内 WindowManager 弹窗，不使用 AlertDialog）。
 * 延迟 / 触摸时长 / 重复次数 / 随机距离 / 随机延迟 / 坐标；滑动额外显示终点坐标。
 */
object PointParamDialog {

    fun show(
        context: Context,
        wm: WindowManager,
        point: ClickPoint,
        onSave: (ClickPoint) -> Unit,
        onDelete: (ClickPoint) -> Unit,
        onDismiss: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.aitap.autotappro.R.drawable.bg_menu_sheet)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                (300 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(context).apply {
            text = "编辑点位 #${point.id} · ${point.type.displayName}"
            setTextColor(Color.WHITE)
            textSize = 16f
            val pb = (8 * density).toInt()
            setPadding(0, pb, 0, pb)
        }
        container.addView(title)

        val isCoord = point.type.usesCoordinates
        val isSwipe = point.type == PointActionType.SWIPE

        fun field(label: String, value: String): EditText {
            val tv = TextView(context).apply {
                text = label; setTextColor(Color.argb(220, 255, 255, 255)); textSize = 13f
            }
            val et = EditText(context).apply {
                setText(value)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(Color.WHITE)
            }
            container.addView(tv); container.addView(et)
            return et
        }

        val etX1 = field("起点 X", point.x1.toInt().toString())
        val etY1 = field("起点 Y", point.y1.toInt().toString())
        var etX2: EditText? = null; var etY2: EditText? = null
        if (isSwipe) {
            etX2 = field("终点 X", point.x2.toInt().toString())
            etY2 = field("终点 Y", point.y2.toInt().toString())
        }
        val etDelay = field("延迟 (ms)", point.delayMs.toString())
        val etTouch = field("触摸时长 (ms)", point.touchDurationMs.toString())
        val etRepeat = field("重复次数", point.repeatCount.toString())
        val etOffset = field("随机距离 (px)", point.randomOffsetPx.toString())
        val etRandDelay = field("随机延迟 (ms)", point.randomDelayMs.toString())

        if (!isCoord) {
            etX1.visibility = android.view.View.GONE
            etY1.visibility = android.view.View.GONE
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val tp = (12 * density).toInt()
            setPadding(0, tp, 0, 0)
        }

        fun actionButton(text: String, color: Int, onClick: () -> Unit) {
            val b = TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = 15f
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * density).toInt()
                )
                lp.marginStart = (12 * density).toInt()
                layoutParams = lp
                setOnClickListener { onClick() }
            }
            buttons.addView(b)
        }

        var handle: FloatingDialogHost.DialogHandle? = null
        actionButton("删除", Color.argb(220, 255, 120, 120)) {
            handle?.dismiss(); onDelete(point)
        }
        actionButton("取消", Color.argb(200, 255, 255, 255)) { handle?.dismiss() }
        fun parseLong(raw: String, fallback: Long): Long {
            val cleaned = raw.trim().replace(Regex("[^0-9]"), "")
            if (cleaned.isEmpty()) return fallback
            return cleaned.toLongOrNull()?.coerceAtLeast(0L) ?: fallback
        }
        fun parseInt(raw: String, fallback: Int, min: Int): Int {
            val cleaned = raw.trim().replace(Regex("[^0-9]"), "")
            if (cleaned.isEmpty()) return fallback
            return cleaned.toIntOrNull()?.coerceAtLeast(min) ?: fallback
        }
        fun parseFloat(raw: String, fallback: Float): Float {
            val cleaned = raw.trim().replace(Regex("[^0-9.]"), "")
            if (cleaned.isEmpty()) return fallback
            return cleaned.toFloatOrNull() ?: fallback
        }

        actionButton("保存", Color.WHITE) {
            val rawDelay = etDelay.text.toString()
            val rawTouch = etTouch.text.toString()
            val rawRepeat = etRepeat.text.toString()
            point.x1 = parseFloat(etX1.text.toString(), point.x1)
            point.y1 = parseFloat(etY1.text.toString(), point.y1)
            if (isSwipe) {
                point.x2 = parseFloat(etX2?.text.toString(), point.x2)
                point.y2 = parseFloat(etY2?.text.toString(), point.y2)
            }
            point.delayMs = parseLong(rawDelay, point.delayMs)
            point.touchDurationMs = parseLong(rawTouch, point.touchDurationMs)
            point.repeatCount = parseInt(rawRepeat, point.repeatCount, 1)
            point.randomOffsetPx = parseInt(etOffset.text.toString(), point.randomOffsetPx, 0)
            point.randomDelayMs = parseLong(etRandDelay.text.toString(), point.randomDelayMs)
            android.util.Log.i("TapPanel", "保存点位#${point.id} raw[delay='$rawDelay' touch='$rawTouch' repeat='$rawRepeat'] " +
                "-> delay=${point.delayMs} touch=${point.touchDurationMs} repeat=${point.repeatCount}")
            handle?.dismiss(); onSave(point)
        }
        container.addView(buttons)

        val scroll = ScrollView(context).apply { addView(container) }
        handle = FloatingDialogHost.show(context, wm, scroll, onDismiss)
    }
}
