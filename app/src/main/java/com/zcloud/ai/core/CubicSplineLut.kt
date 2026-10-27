package com.zcloud.ai.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 单调三次样条插值（Fritsch–Carlson），用于把 2~20 个曲线锚点
 * 展开成 256 项 UInt8 查找表。
 *
 * 为什么用 Fritsch–Carlson 而不是普通三次样条：
 * 普通样条在锚点稀疏时会出现过冲（overshoot）——曲线跑到 [0,255] 之外或
 * 在单调区间里出现反向摆动，套到画面上就是"高光翻车、暗部断层"。
 * Fritsch–Carlson 通过限制切线斜率保证插值结果单调，代价是曲线略"硬"，
 * 但这正是调色曲线想要的特性。
 */
object CubicSplineLut {

    data class Point(val x: Float, val y: Float)

    /**
     * @param points 锚点，至少 2 个；内部会自动按 x 升序排序并去重
     * @return 长度 256 的 LUT，值域 0..255
     */
    fun buildLut(points: List<Point>): IntArray {
        val pts = sanitize(points)
        if (pts.isEmpty()) return IntArray(256) { it }
        if (pts.size == 1) return IntArray(256) { clamp255(pts[0].y) }

        val n = pts.size
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y }

        // 区间步长
        val h = FloatArray(n - 1) { xs[it + 1] - xs[it] }
        // 差商
        val delta = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }

        // 端点切线：使用 Fritsch–Carlson 的调和平均端点
        val m = FloatArray(n)
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            if (delta[i - 1] * delta[i] <= 0f) {
                m[i] = 0f                       // 极值点，切线置零避免过冲
            } else {
                m[i] = (delta[i - 1] + delta[i]) / 2f
            }
        }
        // 端点再做一次单侧限制
        m[0] = limitEndpoint(m[0], delta[0], delta.getOrNull(1))
        m[n - 1] = limitEndpoint(m[n - 1], delta[n - 2], delta.getOrNull(n - 3))

        // Fritsch–Carlson 单调性修正
        for (i in 0 until n - 1) {
            val d = delta[i]
            if (abs(d) < 1e-9f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / d
            val b = m[i + 1] / d
            // 限制到半径 3 的圆内，保证不振荡
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d
                m[i + 1] = t * b * d
            }
        }

        val lut = IntArray(256)
        for (x in 0..255) {
            lut[x] = clamp255(evalAt(x.toFloat(), xs, ys, m, h))
        }
        return lut
    }

    private fun limitEndpoint(m: Float, dNear: Float, dNext: Float?): Float {
        if (dNear == 0f) return 0f
        var v = m
        if (dNext != null && dNear * dNext <= 0f) {
            v = 0f
        }
        // 端点斜率不超过相邻差商的 3 倍
        v = v.coerceIn(min(0f, 3f * dNear), max(0f, 3f * dNear))
        return v
    }

    /** Hermite 基函数求值。 */
    private fun evalAt(x: Float, xs: FloatArray, ys: FloatArray, m: FloatArray, h: FloatArray): Float {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]

        // 二分找区间
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xs[lo]) / h[lo]
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * ys[lo] + h10 * h[lo] * m[lo] + h01 * ys[lo + 1] + h11 * h[lo] * m[lo + 1]
    }

    private fun sanitize(points: List<Point>): List<Point> {
        if (points.isEmpty()) return emptyList()
        val sorted = points
            .map { Point(it.x.coerceIn(0f, 255f), it.y.coerceIn(0f, 255f)) }
            .sortedBy { it.x }
        val out = ArrayList<Point>(sorted.size)
        for (p in sorted) {
            if (out.isNotEmpty() && abs(out.last().x - p.x) < 0.5f) {
                // 同 x 只保留后一个（用户最后拖动的那个）
                out[out.size - 1] = p
            } else {
                out.add(p)
            }
        }
        return out
    }

    private fun clamp255(v: Float): Int = v.roundToIntSafe().coerceIn(0, 255)

    private fun Float.roundToIntSafe(): Int =
        if (isNaN()) 0 else kotlin.math.round(this).toInt()

    // ---------------- 反向：LUT → 等距采样锚点 ----------------

    /** 把 256 项 LUT 降采样成 [count] 个锚点，用于 JSON 回传和曲线编辑。 */
    fun lutToPoints(lut: IntArray, count: Int = 5): List<Point> {
        if (count < 2) return emptyList()
        val out = ArrayList<Point>(count)
        for (i in 0 until count) {
            val x = if (count == 1) 0 else ((i.toFloat() / (count - 1)) * 255f).roundToIntSafe()
            val xi = x.coerceIn(0, 255)
            out.add(Point(x.toFloat(), lut.getOrElse(xi) { xi }.toFloat()))
        }
        return out
    }

    /** 生成 identity LUT（0..255）。 */
    fun identityLut(): IntArray = IntArray(256) { it }

    /** 应用 LUT + 基础参数到单个通道值（预览用）。 */
    fun applyLut(lut: IntArray, v: Int): Int = lut.getOrElse(v.coerceIn(0, 255)) { v }

    // ---------------- 18 字节硬件曲线段编解码 ----------------

    /**
     * 把 LUT 压成相机固件用的 18 字节曲线段。
     *
     * 依据逆向结论（work/out/RE_picture_control.md §4.2）：
     * 载荷 +0x1A / +0x2C / +0x3E 各 18 字节，语义为
     * "18 个等距 x 采样点的 y 值"，0x80 = 中性(128)，0xFF = 该点不参与。
     *
     * @param neutralValue 中性值，默认 0x80
     * @param useAllPoints true = 18 点全填（推荐，机身可直接吃）；
     *                     false = 只填前 8 点，其余 0xFF（与机身出厂 STANDARD 模板一致）
     */
    fun lutToCurveSegment(
        lut: IntArray,
        neutralValue: Int = 0x80,
        useAllPoints: Boolean = true,
    ): ByteArray {
        val seg = ByteArray(18) { 0xFF.toByte() }
        val filled = if (useAllPoints) 18 else 8
        for (i in 0 until filled) {
            val x = kotlin.math.round(i * 255.0 / 17.0).toInt().coerceIn(0, 255)
            val y = lut.getOrElse(x) { x }.coerceIn(0, 255)
            seg[i] = y.toByte()
        }
        // 保底：如果整段都是中性，则全部写成 neutralValue（机身会当作"线性"处理）
        val allNeutral = (0 until filled).all { (seg[it].toInt() and 0xFF) == neutralValue }
        if (allNeutral) {
            for (i in 0 until filled) seg[i] = neutralValue.toByte()
        }
        return seg
    }

    /** 18 字节曲线段 → 256 LUT（0xFF 视为不参与，用 identity 补齐）。 */
    fun curveSegmentToLut(seg: ByteArray, neutralValue: Int = 0x80): IntArray {
        val pts = ArrayList<Point>()
        for (i in 0 until min(18, seg.size)) {
            val raw = seg[i].toInt() and 0xFF
            if (raw == 0xFF) continue
            val x = ((i.toFloat() / 17f) * 255f)
            pts.add(Point(x, raw.toFloat()))
        }
        return if (pts.size >= 2) buildLut(pts) else identityLut()
    }
}
