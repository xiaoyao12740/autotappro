package com.aitap.autotappro.ui.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aitap.autotappro.model.PointActionType

/**
 * ＋号「选择要添加的功能」悬浮菜单（服务内 WindowManager 弹窗，不使用 AlertDialog）。
 * 本轮：点击 / 长按 / 滑动 / 双击 / Home / Back；长按 10 秒、通知栏键留 V1.1。
 */
object AddActionMenu {

    private val items = listOf(
        PointActionType.CLICK,
        PointActionType.LONG_PRESS,
        PointActionType.SWIPE,
        PointActionType.DOUBLE_CLICK,
        PointActionType.HOME,
        PointActionType.BACK
    )

    fun show(
        context: Context,
        wm: WindowManager,
        onPick: (PointActionType) -> Unit,
        onDismiss: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(com.aitap.autotappro.R.drawable.bg_menu_sheet)
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                (220 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(context).apply {
            text = "选择要添加的功能"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            val pb = (10 * density).toInt()
            setPadding(0, pb, 0, pb)
        }
        sheet.addView(title)

        var handle: FloatingDialogHost.DialogHandle? = null
        items.forEach { type ->
            val item = TextView(context).apply {
                text = type.displayName
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                val h = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, h
                )
                setOnClickListener {
                    handle?.dismiss()
                    onPick(type)
                }
            }
            sheet.addView(item)
        }

        val cancel = TextView(context).apply {
            text = "取消"
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 15f
            gravity = Gravity.CENTER
            val h = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            )
            setOnClickListener { handle?.dismiss() }
        }
        sheet.addView(cancel)

        handle = FloatingDialogHost.show(context, wm, sheet, onDismiss)
    }
}
