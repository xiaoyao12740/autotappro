package com.aitap.autotappro.channel

import com.aitap.autotappro.model.ChannelKind

/** 单次手势/全局动作的执行回执。 */
sealed class GestureOutcome {
    /** 成功完成。 */
    object Completed : GestureOutcome()

    /** 被取消（停止/暂停退出）。 */
    object Cancelled : GestureOutcome()

    /** 失败；[message] 带退出码 / stderr 摘要，供悬浮层或 Toast 明确回执。 */
    data class Failed(val message: String) : GestureOutcome()
}

/**
 * 回放通道抽象：无障碍通道与 Root 通道实现同一套原语，Runner 不感知底层差异。
 *
 * 坐标一律为整屏绝对像素；HOME/BACK 由实现内部分流到全局动作或 keyevent。
 */
interface GestureChannel {

    val kind: ChannelKind

    /** 点击 / 长按（durationMs 为按下到抬起时长）。 */
    suspend fun tap(x: Int, y: Int, durationMs: Long): GestureOutcome

    /** 滑动：起点 (sx,sy) → 终点 (ex,ey)，时长 durationMs。 */
    suspend fun swipe(
        sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long
    ): GestureOutcome

    /** 回桌面。 */
    suspend fun goHome(): GestureOutcome

    /** 返回。 */
    suspend fun goBack(): GestureOutcome
}
