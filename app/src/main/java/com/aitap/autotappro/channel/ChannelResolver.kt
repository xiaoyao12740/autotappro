package com.aitap.autotappro.channel

import com.aitap.autotappro.model.ChannelKind
import com.aitap.autotappro.model.ChannelMode
import com.aitap.autotappro.service.TapAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通道解析：按 [ChannelMode] 在无障碍 / Root 间选择实际生效的 [GestureChannel]。
 *
 * 探测口径：
 * - 无障碍可用：[TapAccessibilityService.instance] 非空（进程内真绑定）。
 * - Root 可用：`su id` 退出码 0 且含 `uid=0`（IO 线程探测，避免阻塞主线程）。
 *
 * 选择规则见设计文档 2.2；强制模式不可用返回 [ChannelKind.NONE]，不静默降级。
 */
object ChannelResolver {

    data class Resolution(
        val kind: ChannelKind,
        val channel: GestureChannel?,
        val reason: String
    )

    suspend fun resolve(mode: ChannelMode): Resolution {
        val a11yInstance = TapAccessibilityService.instance
        val a11yOk = a11yInstance != null
        val rootOk = withContext(Dispatchers.IO) { RootHelper.isRootAvailable() }

        return when (mode) {
            ChannelMode.AUTO -> when {
                a11yOk -> Resolution(ChannelKind.ACCESSIBILITY, AccessibilityChannel(a11yInstance!!), "无障碍已连接")
                rootOk -> Resolution(ChannelKind.ROOT, RootShellChannel(), "无障碍不可用，自动降级 Root")
                else -> Resolution(ChannelKind.NONE, null, "无障碍与 Root 均不可用，请先授权")
            }

            ChannelMode.FORCE_ACCESSIBILITY -> when {
                a11yOk -> Resolution(ChannelKind.ACCESSIBILITY, AccessibilityChannel(a11yInstance!!), "强制使用无障碍")
                else -> Resolution(ChannelKind.NONE, null, "强制无障碍：服务未连接，未降级 Root")
            }

            ChannelMode.FORCE_ROOT -> when {
                rootOk -> Resolution(ChannelKind.ROOT, RootShellChannel(), "强制使用 Root")
                else -> Resolution(ChannelKind.NONE, null, "强制 Root：未授权或非 Root 设备")
            }
        }
    }
}
