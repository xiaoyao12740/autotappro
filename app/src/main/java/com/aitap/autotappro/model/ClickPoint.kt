package com.aitap.autotappro.model

import org.json.JSONObject

/**
 * 点位动作类型。
 * CLICK 点击；LONG_PRESS 长按；SWIPE 滑动；HOME/BACK 走全局动作（performGlobalAction）。
 * DOUBLE_CLICK 本轮映射为两次点击。
 */
enum class PointActionType {
    CLICK, LONG_PRESS, SWIPE, DOUBLE_CLICK, HOME, BACK;

    /** 是否占用屏幕坐标（HOME/BACK 不占坐标）。 */
    val usesCoordinates: Boolean
        get() = when (this) {
            CLICK, LONG_PRESS, SWIPE, DOUBLE_CLICK -> true
            HOME, BACK -> false
        }

    /** 是否需要触摸时长参数。 */
    val usesTouchDuration: Boolean
        get() = usesCoordinates

    val displayName: String
        get() = when (this) {
            CLICK -> "点击"
            LONG_PRESS -> "长按"
            SWIPE -> "滑动"
            DOUBLE_CLICK -> "双击"
            HOME -> "Home"
            BACK -> "Back"
        }
}

/**
 * 一个可在屏幕上定位、可重复执行的点击点位。
 *
 * @param id 点位唯一序号（展示编号）
 * @param x1,y1 起点/点击中心坐标（屏幕像素）
 * @param x2,y2 滑动终点坐标；非滑动类型与起点相同
 * @param delayMs 本点位每次派发前的间隔（毫秒）；序列第一次派发不等待，其后每次（含重复）都等待
 * @param touchDurationMs 按下到抬起的时长（毫秒），点击默认 10ms
 * @param repeatCount 本点位重复次数（≥1）
 * @param randomOffsetPx 坐标随机偏移半径（px），0 表示不偏移
 * @param randomDelayMs 在 delayMs 上叠加的随机延迟（ms），0 表示不叠加
 * @param targetText 文字定位目标；本轮保留字段但不实现
 */
data class ClickPoint(
    val id: Int,
    var type: PointActionType = PointActionType.CLICK,
    var x1: Float = 0f,
    var y1: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var delayMs: Long = 100L,
    var touchDurationMs: Long = 50L,
    var repeatCount: Int = 999,
    var randomOffsetPx: Int = 0,
    var randomDelayMs: Long = 0L,
    var targetText: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("x1", x1); put("y1", y1)
        put("x2", x2); put("y2", y2)
        put("delayMs", delayMs)
        put("touchDurationMs", touchDurationMs)
        put("repeatCount", repeatCount)
        put("randomOffsetPx", randomOffsetPx)
        put("randomDelayMs", randomDelayMs)
        put("targetText", targetText)
    }

    companion object {
        fun fromJson(o: JSONObject): ClickPoint = ClickPoint(
            id = o.getInt("id"),
            type = runCatching { PointActionType.valueOf(o.getString("type")) }
                .getOrDefault(PointActionType.CLICK),
            x1 = o.getDouble("x1").toFloat(),
            y1 = o.getDouble("y1").toFloat(),
            x2 = o.getDouble("x2").toFloat(),
            y2 = o.getDouble("y2").toFloat(),
            delayMs = o.getLong("delayMs"),
            touchDurationMs = o.getLong("touchDurationMs"),
            repeatCount = o.getInt("repeatCount").coerceAtLeast(1),
            randomOffsetPx = o.getInt("randomOffsetPx"),
            randomDelayMs = o.getLong("randomDelayMs"),
            targetText = o.optString("targetText", "")
        )
    }
}
