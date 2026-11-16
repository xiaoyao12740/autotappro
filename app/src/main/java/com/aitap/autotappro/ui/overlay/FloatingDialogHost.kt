package com.aitap.autotappro.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.aitap.autotappro.R

/**
 * 服务内悬浮弹窗宿主：Service 没有 Activity token，不能用 AlertDialog（BadTokenException）。
 * 这里通过 WindowManager 把一个遮罩 + 内容 View 直接 addView，关闭时 removeView。
 */
object FloatingDialogHost {

    /** 当前打开的弹窗句柄，便于录制开始等场景统一收起。 */
    private val openHandles = mutableSetOf<DialogHandle>()

    /** 关闭所有已打开的悬浮弹窗/菜单。 */
    fun dismissAll() {
        openHandles.toList().forEach { it.dismiss() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /**
     * 把 [content] 作为居中悬浮面板加入；点击遮罩或调用返回的 [DialogHandle.dismiss] 关闭。
     */
    fun show(
        context: Context,
        wm: WindowManager,
        content: View,
        onDismiss: () -> Unit
    ): DialogHandle {
        val mask = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            addView(content)
        }

        val maskParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        var dismissed = false
        val holder = arrayOfNulls<DialogHandle>(1)
        val handle = DialogHandle {
            if (dismissed) return@DialogHandle
            dismissed = true
            holder[0]?.let { openHandles.remove(it) }
            runCatching { wm.removeView(mask) }
            onDismiss()
        }
        holder[0] = handle
        openHandles.add(handle)

        mask.setOnClickListener { handle.dismiss() }
        content.setOnClickListener { /* 阻止冒泡到遮罩 */ }

        runCatching { wm.addView(mask, maskParams) }
            .onFailure {
                android.util.Log.e("TapFloat", "弹窗 addView 失败: ${it.message}", it)
                onDismiss()
            }
        return handle
    }

    /** 弹窗关闭句柄。 */
    class DialogHandle(private val dismissAction: () -> Unit) {
        fun dismiss() = dismissAction()
    }
}
