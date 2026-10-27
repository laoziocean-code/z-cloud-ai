package com.zcloud.ai.core

import android.graphics.Bitmap

/**
 * 预览渲染管线：把 Recipe（256 LUT + 基础参数）套到 Bitmap 上。
 *
 * 为什么用 CPU 而不是 RenderEffect / AGSL Shader：
 *  - AGSL 需要 API 33+，minSdk 26 覆盖不到；
 *  - RenderEffect 只能做线性矩阵，做不了任意 LUT；
 *  - 预览图限制在长边 ≤1080，一次全图 LUT 映射在 Kotlin 里 30~60ms，
 *    debounce 之后手感完全够，且 100% 确定性、不会因 GPU 差异出偏差。
 */
object ImagePipeline {

    /**
     * @param lut 长度 256 的查找表
     * @param contrast   -3..3  → 映射成 gamma 式亮度压缩/拉伸
     * @param brightness -2..2  → 线性偏移
     * @param saturation -3..3  → 饱和度系数
     * @param warm       预览用色温（由 hue 推导）
     */
    fun render(
        src: Bitmap,
        lut: IntArray,
        contrast: Float = 0f,
        brightness: Float = 0f,
        saturation: Float = 0f,
        hue: Float = 0f,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 基础参数 → 简单增益（跟相机内部 EXPEED 的近似，不追求严格一致，只要预览方向对）
        val satFactor = (1f + saturation * 0.28f).coerceIn(0f, 2.5f)
        val briOffset = (brightness * 16f)
        val conFactor = (1f + contrast * 0.22f).coerceIn(0.2f, 2.4f)
        val hueShift = hue * 6f   // 度

        val safeLut = if (lut.size >= 256) lut else CubicSplineLut.identityLut()

        for (i in pixels.indices) {
            val c = pixels[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF

            // 1) 亮度曲线（LUT）
            r = safeLut[r.coerceIn(0, 255)]
            g = safeLut[g.coerceIn(0, 255)]
            b = safeLut[b.coerceIn(0, 255)]

            // 2) 对比度（围绕 128 拉伸）
            if (conFactor != 1f) {
                r = ((r - 128) * conFactor + 128).toInt()
                g = ((g - 128) * conFactor + 128).toInt()
                b = ((b - 128) * conFactor + 128).toInt()
            }

            // 3) 亮度偏移
            if (briOffset != 0f) {
                val d = briOffset.toInt()
                r += d; g += d; b += d
            }

            // 4) 饱和度
            if (satFactor != 1f) {
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b)
                r = (luma + (r - luma) * satFactor).toInt()
                g = (luma + (g - luma) * satFactor).toInt()
                b = (luma + (b - luma) * satFactor).toInt()
            }

            // 5) 色相：在 YIQ 空间做微旋转（近似，够预览用）
            if (hueShift != 0f) {
                val angle = Math.toRadians(hueShift.toDouble())
                val cosA = kotlin.math.cos(angle).toFloat()
                val sinA = kotlin.math.sin(angle).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val i0 = 0.596f * r - 0.274f * g - 0.322f * b
                val q0 = 0.211f * r - 0.523f * g + 0.312f * b
                val i1 = i0 * cosA - q0 * sinA
                val q1 = i0 * sinA + q0 * cosA
                r = (y + 0.956f * i1 + 0.621f * q1).toInt()
                g = (y - 0.272f * i1 - 0.647f * q1).toInt()
                b = (y - 1.106f * i1 + 1.703f * q1).toInt()
            }

            pixels[i] = (0xFF shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 生成一张内置测试样张（渐变 + 灰阶块 + 肤色块），离线也能演示预览效果。 */
    fun builtinSample(w: Int = 720, h: Int = 480): Bitmap {
        val pixels = IntArray(w * h)
        val blockW = w / 8
        for (y in 0 until h) {
            for (x in 0 until w) {
                val col = (x / blockW).coerceIn(0, 7)
                val base = col * 32 + (x % blockW) * 32 / blockW
                val band = if (y < h / 3) 1.0f else if (y < 2 * h / 3) 0.72f else 0.45f
                var r = (base * band).toInt().coerceIn(0, 255)
                var g = (base * band).toInt().coerceIn(0, 255)
                var b = ((base * band) * 0.96f).toInt().coerceIn(0, 255)
                // 下半部分做一块偏暖的"肤色"区，方便看色相/饱和度
                if (y > 2 * h / 3 && col in 5..7) {
                    r = (210 * band).toInt().coerceIn(0, 255)
                    g = (170 * band).toInt().coerceIn(0, 255)
                    b = (145 * band).toInt().coerceIn(0, 255)
                }
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
