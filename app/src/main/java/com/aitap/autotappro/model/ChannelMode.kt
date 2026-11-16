package com.aitap.autotappro.model

/**
 * 回放通道选择模式（用户可手动指定，随配置持久化）。
 *
 * - [AUTO] 自动：无障碍已连接优先；不可用自动降级 Root；都不可用提示授权。
 * - [FORCE_ACCESSIBILITY] 强制无障碍：只走无障碍，不可用明确报错，不静默降级 Root。
 * - [FORCE_ROOT] 强制 Root：只走 Root，不可用明确报错，不静默降级无障碍。
 */
enum class ChannelMode {
    AUTO,
    FORCE_ACCESSIBILITY,
    FORCE_ROOT;

    val displayName: String
        get() = when (this) {
            AUTO -> "自动"
            FORCE_ACCESSIBILITY -> "强制无障碍"
            FORCE_ROOT -> "强制 Root"
        }

    companion object {
        fun fromName(name: String?): ChannelMode =
            runCatching { valueOf(name ?: "") }.getOrDefault(AUTO)
    }
}

/** 实际生效的回放通道。 */
enum class ChannelKind {
    ACCESSIBILITY,
    ROOT,
    NONE
}

/** 生效通道 + 选择模式 → 上屏文案。 */
fun channelLabel(mode: ChannelMode, kind: ChannelKind): String = when (kind) {
    ChannelKind.ACCESSIBILITY ->
        if (mode == ChannelMode.FORCE_ACCESSIBILITY) "强制无障碍" else "自动·无障碍"
    ChannelKind.ROOT ->
        if (mode == ChannelMode.FORCE_ROOT) "强制 Root" else "自动·Root"
    ChannelKind.NONE -> "未就绪"
}
