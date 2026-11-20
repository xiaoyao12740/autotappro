package com.aitap.autotappro.model

/**
 * 点位坐标朝向解析。
 *
 * 点位保存其录制 / 最后编辑朝向的裸像素 + 朝向标识 + 源屏宽高；渲染、派发时若与当前朝向不符，
 * 按顺时针 90° 归一化换算。无标识（旧配置）或源宽高缺失时按裸像素原值读取，不转换。
 *
 * 换算约定（顺时针 90°）：
 * - 竖 → 横：归一化后 `nl_x = np_y`，`nl_y = 1 - np_x`
 * - 横 → 竖：归一化后 `np_x = 1 - nl_y`，`np_y = nl_x`
 * 往返（横→竖→横）按比例反算可还原原像素，不二次漂移。
 */
object PointCoords {

    const val TAG_LANDSCAPE = "landscape"
    const val TAG_PORTRAIT = "portrait"

    fun tagOf(w: Int, h: Int): String = if (w > h) TAG_LANDSCAPE else TAG_PORTRAIT

    private fun isLandscape(tag: String?): Boolean = tag == TAG_LANDSCAPE

    /** 起点在当前屏幕下的像素坐标。 */
    fun resolveStart(point: ClickPoint, curW: Int, curH: Int): Pair<Float, Float> =
        resolve(point.x1, point.y1, point, curW, curH)

    /** 终点（滑动终点）在当前屏幕下的像素坐标。 */
    fun resolveEnd(point: ClickPoint, curW: Int, curH: Int): Pair<Float, Float> =
        resolve(point.x2, point.y2, point, curW, curH)

    private fun resolve(
        sx: Float, sy: Float, p: ClickPoint, cw: Int, ch: Int
    ): Pair<Float, Float> {
        val sw = p.srcW
        val sh = p.srcH
        // 旧配置 / 元数据缺失：原值读取，不做朝向换算。
        if (p.orientation.isEmpty() || sw <= 0 || sh <= 0 || cw <= 0 || ch <= 0) {
            return sx to sy
        }
        val srcLand = isLandscape(p.orientation)
        val curLand = cw > ch
        if (srcLand == curLand) return sx to sy

        val nx = (sx / sw).coerceIn(0f, 1f)
        val ny = (sy / sh).coerceIn(0f, 1f)
        return if (srcLand && !curLand) {
            // 横 → 竖：px = (1 - ny) * cw，py = nx * ch
            (1 - ny) * cw to nx * ch
        } else {
            // 竖 → 横：lx = ny * cw，ly = (1 - nx) * ch
            ny * cw to (1 - nx) * ch
        }
    }

    /** 点位在当前朝向下被写入 / 拖拽 / 手动编辑后，刷新标识与源宽高。 */
    fun markCurrent(point: ClickPoint, w: Int, h: Int) {
        point.orientation = tagOf(w, h)
        point.srcW = w.coerceAtLeast(1)
        point.srcH = h.coerceAtLeast(1)
    }
}
