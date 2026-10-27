package com.zcloud.ai.core

import android.util.Base64

/**
 * 配方快传码（Filter Quick-Share Code）。
 *
 * 设计目标：把一份 Nikon 优化校准配方压成**一段可以贴到微信/QQ 里的文本**，
 * 对方粘贴进来就能还原成完整配方（含 256 点 LUT）。
 *
 * 格式（先明文再 Base64 URL-Safe）：
 * ```
 * ZCAI1|名称|基础风格|锐化*100|清晰度*100|对比度*100|亮度*100|饱和度*100|色相*100|曲线点...
 * ```
 * 曲线点写成 `x:y` 用逗号分隔，最多 20 个。数值全部乘 100 取整，避免浮点区域化差异。
 *
 * 没有引入二维码库：二维码要额外依赖或几百行实现，而手机端「复制粘贴 + 系统分享」
 * 已经覆盖 99% 的传递场景，且文本可以直接进群公告/评论区。
 */
object RecipeShareCode {

    const val PREFIX = "ZCAI1:"
    private const val PAYLOAD_TAG = "ZCAI1|"
    private const val MAX_POINTS = 20

    private fun q(v: Float): Int = Math.round(v * 100f)

    private fun dq(v: String): Float = (v.toIntOrNull() ?: 0) / 100f

    /** 编码成可分享文本（带 `ZCAI1:` 前缀）。 */
    fun encode(recipe: Recipe): String {
        val pts = recipe.curvePoints
            .take(MAX_POINTS)
            .joinToString(",") { "${Math.round(it.x)}:${Math.round(it.y)}" }
        val body = buildString {
            append(PAYLOAD_TAG)
            append(sanitize(recipe.name))
            append('|').append(sanitize(recipe.baseProfile))
            append('|').append(q(recipe.sharpening))
            append('|').append(q(recipe.clarity))
            append('|').append(q(recipe.contrast))
            append('|').append(q(recipe.brightness))
            append('|').append(q(recipe.saturation))
            append('|').append(q(recipe.hue))
            append('|').append(pts)
        }
        val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return PREFIX + b64
    }

    /** 从任意文本里找出快传码并解析；找不到或损坏返回 null。 */
    fun decode(text: String): Recipe? {
        val cleaned = text.trim()
        val idx = cleaned.indexOf(PREFIX)
        val raw = if (idx >= 0) cleaned.substring(idx + PREFIX.length).trim() else cleaned
        val candidate = raw.substringBefore('\n').substringBefore(' ').trim()
        if (candidate.isEmpty()) return null

        val body = runCatching {
            String(Base64.decode(candidate, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: runCatching {
            // 容错：对方可能把 `-`/`_` 换成 `+`/`/` 或补了换行
            String(Base64.decode(candidate, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null

        if (!body.startsWith(PAYLOAD_TAG)) return null
        val parts = body.removePrefix(PAYLOAD_TAG).split('|')
        if (parts.size < 9) return null

        val points = parsePoints(parts[8])
        if (points.size < 2) return null

        val lut = CubicSplineLut.buildLut(points)
        return Recipe(
            name = parts[0].ifBlank { "SHARED" }.take(19),
            baseProfile = parts[1].ifBlank { "Standard" },
            pcdType = NcpParserAndBuilder.profileNameToPcdType(parts[1].ifBlank { "Standard" }),
            sharpening = clamp(dq(parts[2]), -3f, 9f),
            clarity = clamp(dq(parts[3]), -5f, 5f),
            contrast = clamp(dq(parts[4]), -3f, 3f),
            brightness = clamp(dq(parts[5]), -2f, 2f),
            saturation = clamp(dq(parts[6]), -3f, 3f),
            hue = clamp(dq(parts[7]), -3f, 3f),
            useCustomCurve = true,
            curvePoints = points,
            styleAnalysis = "由快传码导入 · ${points.size} 点曲线",
            lut = lut,
        )
    }

    private fun parsePoints(raw: String): List<CubicSplineLut.Point> {
        val pts = ArrayList<CubicSplineLut.Point>()
        raw.split(',').forEach { seg ->
            val p = seg.split(':')
            if (p.size == 2) {
                val x = p[0].toFloatOrNull() ?: return@forEach
                val y = p[1].toFloatOrNull() ?: return@forEach
                pts.add(
                    CubicSplineLut.Point(
                        x.coerceIn(0f, 255f),
                        y.coerceIn(0f, 255f),
                    )
                )
            }
        }
        return pts.sortedBy { it.x }.distinctBy { Math.round(it.x) }.take(MAX_POINTS)
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    private fun sanitize(s: String): String = s.replace('|', '/').replace('\n', ' ').trim()
}
