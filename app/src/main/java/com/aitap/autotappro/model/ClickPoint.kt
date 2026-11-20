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
 * @param orientation 记录/最后编辑时的朝向标识（landscape/portrait）；空串=旧配置，不做朝向换算
 * @param srcW 记录/最后编辑时的屏幕宽（px），换算归一化用
 * @param srcH 记录/最后编辑时的屏幕高（px），换算归一化用
 */
data class ClickPoint(
    val id: Int,
    var type: PointActionType = PointActionType.CLICK,
    var x1: Float = 0f,
    var y1: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    var delayMs: Long = 200L,
    var touchDurationMs: Long = 50L,
    var repeatCount: Int = 9999,
    var randomOffsetPx: Int = 0,
    var randomDelayMs: Long = 50L,
    var targetText: String = "",
    var orientation: String = "",
    var srcW: Int = 0,
    var srcH: Int = 0
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
        put("orientation", orientation)
        put("srcW", srcW)
        put("srcH", srcH)
    }

    companion object {
        fun fromJson(o: JSONObject): ClickPoint = ClickPoint(
            id = o.optInt("id", 0),
            type = runCatching { PointActionType.valueOf(o.optString("type", "")) }
                .getOrDefault(PointActionType.CLICK),
            x1 = o.optDouble("x1", 0.0).toFloat(),
            y1 = o.optDouble("y1", 0.0).toFloat(),
            x2 = o.optDouble("x2", 0.0).toFloat(),
            y2 = o.optDouble("y2", 0.0).toFloat(),
            delayMs = o.optLong("delayMs", 200L),
            touchDurationMs = o.optLong("touchDurationMs", 50L),
            repeatCount = o.optInt("repeatCount", 9999).coerceAtLeast(1),
            randomOffsetPx = o.optInt("randomOffsetPx", 0),
            randomDelayMs = o.optLong("randomDelayMs", 50L),
            targetText = o.optString("targetText", ""),
            orientation = o.optString("orientation", ""),
            srcW = o.optInt("srcW", 0),
            srcH = o.optInt("srcH", 0)
        )
    }
}
