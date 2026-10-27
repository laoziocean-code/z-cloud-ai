package com.zcloud.ai.ai

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * 参考图的客观先验特征。
 *
 * 为什么要本地先算：把直方图数字（几十个 token）连同图一起发给 LLM，
 * 比让模型自己"看图估影调"准确得多，而且可以在降采样很狠的情况下仍然保住精度。
 */
data class ImageFeatures(
    val blackPoint: Int,          // 直方图 0.5% 分位
    val highlightPoint: Int,      // 直方图 99.5% 分位
    val shadowClipRatio: Float,   // 纯黑 (<4) 像素占比
    val highlightClipRatio: Float,// 纯白 (>251) 像素占比
    val midGray: Float,           // 中灰实际位置（0..255），标准 128
    val meanLuma: Float,
    val contrastSpread: Float,    // (P95 - P5) / 255
    val meanSaturation: Float,    // 0..1
    val warmBias: Float,          // R-B 归一化差，>0 偏暖
    val greenBias: Float,         // G - (R+B)/2
    val histogram16: IntArray,    // 16 桶亮度直方图（归一化 0..100）
) {
    /** 压成一行紧凑文本给 LLM，控制 token 用量。 */
    fun toPromptLine(): String = buildString {
        append("black=").append(blackPoint)
        append(" high=").append(highlightPoint)
        append(" mid=").append("%.0f".format(midGray))
        append(" mean=").append("%.0f".format(meanLuma))
        append(" spread=").append("%.2f".format(contrastSpread))
        append(" sat=").append("%.2f".format(meanSaturation))
        append(" warm=").append("%.2f".format(warmBias))
        append(" green=").append("%.2f".format(greenBias))
        append(" clipLo=").append("%.2f".format(shadowClipRatio))
        append(" clipHi=").append("%.2f".format(highlightClipRatio))
        append(" hist=").append(histogram16.joinToString(","))
    }

    companion object {
        val EMPTY = ImageFeatures(
            blackPoint = 0, highlightPoint = 255,
            shadowClipRatio = 0f, highlightClipRatio = 0f,
            midGray = 128f, meanLuma = 128f, contrastSpread = 1f,
            meanSaturation = 0f, warmBias = 0f, greenBias = 0f,
            histogram16 = IntArray(16) { 6 },
        )
    }
}

/** 直方图 / 色彩统计提取器（纯本地，无依赖）。 */
object ImageFeatureExtractor {

    /**
     * @param sampleStep 采样步长，2 = 每 2 像素取 1，兼顾速度与精度
     */
    fun extract(src: Bitmap, sampleStep: Int = 2): ImageFeatures {
        val w = src.width
        val h = src.height
        if (w == 0 || h == 0) return ImageFeatures.EMPTY

        val hist = IntArray(256)
        var total = 0L
        var sumLuma = 0.0
        var sumSat = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var clipLo = 0L
        var clipHi = 0L

        val step = max(1, sampleStep)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = src.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Rec.709 亮度
                val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b)
                val li = luma.toInt().coerceIn(0, 255)
                hist[li]++
                total++
                sumLuma += luma
                val mx = max(r, max(g, b))
                val mn = minOf(r, minOf(g, b))
                sumSat += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
                sumR += r; sumG += g; sumB += b
                if (li <= 3) clipLo++
                if (li >= 252) clipHi++
                x += step
            }
            y += step
        }
        if (total == 0L) return ImageFeatures.EMPTY

        val blackPoint = percentile(hist, total, 0.005)
        val highlightPoint = percentile(hist, total, 0.995)
        val p5 = percentile(hist, total, 0.05)
        val p95 = percentile(hist, total, 0.95)
        val meanLuma = (sumLuma / total).toFloat()
        val midGray = percentile(hist, total, 0.50).toFloat()

        val n = total.toDouble()
        val meanR = sumR / n
        val meanG = sumG / n
        val meanB = sumB / n
        val warm = ((meanR - meanB) / 255.0).toFloat()
        val green = ((meanG - (meanR + meanB) / 2.0) / 255.0).toFloat()

        // 16 桶归一化直方图（每桶 0..100）
        val buckets = IntArray(16)
        for (i in 0..255) buckets[i / 16] += hist[i]
        val maxBucket = buckets.maxOrNull() ?: 1
        val norm = IntArray(16) { ((buckets[it].toDouble() / maxBucket) * 100).toInt() }

        return ImageFeatures(
            blackPoint = blackPoint,
            highlightPoint = highlightPoint,
            shadowClipRatio = (clipLo.toDouble() / total).toFloat(),
            highlightClipRatio = (clipHi.toDouble() / total).toFloat(),
            midGray = midGray,
            meanLuma = meanLuma,
            contrastSpread = ((p95 - p5).toFloat() / 255f).coerceIn(0f, 1f),
            meanSaturation = (sumSat / total).toFloat().coerceIn(0f, 1f),
            warmBias = warm,
            greenBias = green,
            histogram16 = norm,
        )
    }

    private fun percentile(hist: IntArray, total: Long, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }

    // ---------------- 图像降采样 + Base64（省 token 的关键） ----------------

    /**
     * 把参考图压成"给 AI 看刚好够、token 尽量少"的 JPEG。
     * 长边 384px + q=62：视觉模型仍能判断影调分布与色偏，但 token 约为原图的 1/6。
     */
    fun toBase64Jpeg(src: Bitmap, maxEdge: Int = 384, quality: Int = 62): String {
        val scaled = scaleDown(src, maxEdge)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longEdge = max(w, h)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        val nw = max(1, (w * ratio).toInt())
        val nh = max(1, (h * ratio).toInt())
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
