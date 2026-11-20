package com.aitap.autotappro.model

import org.json.JSONArray
import org.json.JSONObject

/** 运行模式：跑一次（按 cycleCount 轮数）或循环到手动停止。 */
enum class RunMode { ONCE, LOOP_UNTIL_STOP }

/** 点位数量上限，沿用原 100 步约束。 */
const val MAX_POINTS = 100

/**
 * 一套可存档的点击配置：点位列表 + 全局运行参数，可一键还原。
 */
data class ClickProfile(
    var id: String = "default",
    var name: String = "默认配置",
    val points: MutableList<ClickPoint> = mutableListOf(),
    var runMode: RunMode = RunMode.LOOP_UNTIL_STOP,
    var cycleCount: Int = 1,
    var loopIntervalDelayMs: Long = 200L,
    var clickRandomOffsetPx: Int = 0,
    var randomDelayMs: Long = 0L,
    var panelCollapsed: Boolean = false,
    var snapToEdge: Boolean = true,
    var pointsVisible: Boolean = true,
    var channelMode: ChannelMode = ChannelMode.AUTO
) {
    val isFull: Boolean get() = points.size >= MAX_POINTS

    fun add(point: ClickPoint): Boolean {
        if (isFull) return false
        points.add(point)
        return true
    }

    fun nextPointId(): Int = (points.maxByOrNull { it.id }?.id ?: 0) + 1

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("runMode", runMode.name)
        put("cycleCount", cycleCount)
        put("loopIntervalDelayMs", loopIntervalDelayMs)
        put("clickRandomOffsetPx", clickRandomOffsetPx)
        put("randomDelayMs", randomDelayMs)
        put("panelCollapsed", panelCollapsed)
        put("snapToEdge", snapToEdge)
        put("pointsVisible", pointsVisible)
        put("channelMode", channelMode.name)
        val arr = JSONArray()
        points.forEach { arr.put(it.toJson()) }
        put("points", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): ClickProfile {
            val profile = ClickProfile(
                id = o.optString("id", "default"),
                name = o.optString("name", "默认配置"),
                runMode = runCatching { RunMode.valueOf(o.getString("runMode")) }
                    .getOrDefault(RunMode.LOOP_UNTIL_STOP),
                cycleCount = o.optInt("cycleCount", 1).coerceAtLeast(1),
                loopIntervalDelayMs = o.optLong("loopIntervalDelayMs", 200L),
                clickRandomOffsetPx = o.optInt("clickRandomOffsetPx", 0),
                randomDelayMs = o.optLong("randomDelayMs", 0L),
                panelCollapsed = o.optBoolean("panelCollapsed", false),
                snapToEdge = o.optBoolean("snapToEdge", true),
                pointsVisible = o.optBoolean("pointsVisible", true),
                channelMode = ChannelMode.fromName(o.optString("channelMode"))
            )
            val arr = o.optJSONArray("points") ?: return profile
            for (i in 0 until arr.length()) {
                // 逐条容错：单个点位 JSON 缺键 / 类型不符时跳过该点，避免拖垮整份配置载入而回退默认。
                runCatching { ClickPoint.fromJson(arr.getJSONObject(i)) }
                    .onSuccess { profile.points.add(it) }
            }
            return profile
        }
    }
}
