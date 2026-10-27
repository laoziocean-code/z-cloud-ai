package com.zcloud.ai.core

import java.nio.charset.StandardCharsets

/**
 * Z30 真实的优化校准载荷格式：**614 字节（0x266）**。
 *
 * ── 这个格式不是猜的，是从真机 `0x90CC` 的 DATA-IN 原始字节里读出来的 ──
 *
 * 证据来源：`ptp-20261027.log` 会话 `12:38:38`（app 9 / 1.4.3），
 * `CMD 0x90CC params=[0x00C9,0x00]` → `DATA-IN len=614`，槽位里存着
 * 用户自己导入的两个富士配方。两条记录（另一条是 `[0x00CA]`）逐字节对比后，
 * 差异只落在「名称」和「LUT」两个区域，其余 68 字节完全一致 —— 这就是版式骨架。
 *
 *   +0x000   2B   `02 00`            格式/版本标识
 *   +0x002  20B   ASCII 名称（NUL 补齐），实测 `"Fuji Provia"` / `"Fuji Astia"`
 *   +0x016   3B   保留 0
 *   +0x019   1B   点位数据字节数，实测恒为 `0x08`
 *   +0x01A   1B   `0x04`
 *   +0x01B   8B   保留 0
 *   +0x023   1B   `0x01`
 *   +0x024   2B   `"I0"`（0x49 0x30）—— 机型/系列标记
 *   +0x026   1B   0
 *   +0x027   1B   `0xFF`
 *   +0x028   1B   0
 *   +0x029   1B   `0xFF`
 *   +0x02A   1B   `0x01`
 *   +0x02B   1B   `0x0F`
 *   +0x02C   1B   配方序号/族标记（Provia=0x05，Astia=0x06）
 *   +0x02D   2B   保留 0
 *   +0x02F   8B   4 组 (x, y) 控制点，各 1 字节
 *                  Provia: (47,52)(159,181)(191,205)(255,246)
 *                  Astia : (52,53)(169,178)(250,246)(252,248)
 *   +0x037  47B   保留 0
 *   +0x066 512B   256 × u16 **大端** LUT，值域 0..32767（15 位标度）
 *                 ── 实测 256 步全程单调不回折，末值 Provia=31611、Astia=32767 ──
 *   ── 0x066 + 512 = 0x266 = 614，恰好到底，无冗余尾字节 ──
 *
 * 之前按 84 字节写（那是从固件逆向出来的 SDS 内部结构，不是 PTP 线上格式）
 * 是「AccessDenied / InvalidParameter」的直接原因：机身按 614 校验长度，短包一律拒。
 */
object PcPayload614 {

    const val SIZE = 614                          // 0x266
    const val OFF_VERSION = 0x000
    const val OFF_NAME = 0x002
    const val NAME_LEN = 20
    const val OFF_POINT_COUNT = 0x019             // 点位数据字节数（=8）
    const val OFF_POINT_DATA = 0x02F              // 4 × (x, y)
    const val POINT_DATA_LEN = 8
    const val POINT_COUNT = POINT_DATA_LEN / 2    // 4 组
    const val OFF_FAMILY = 0x02C                  // 族/序号标记
    const val OFF_LUT = 0x066                     // 256 × u16 BE
    const val LUT_COUNT = 256
    const val LUT_SCALE = 32767                   // 15 位满量程
    const val LUT_END = OFF_LUT + LUT_COUNT * 2   // 0x266

    /** 判断一段原始数据是不是 614 字节载荷格式。 */
    fun looksLike614(raw: ByteArray): Boolean {
        if (raw.size != SIZE) return false
        return raw[OFF_VERSION] == 0x02.toByte() && raw[OFF_VERSION + 1] == 0x00.toByte()
    }

    /** 读名称（ASCII，截到第一个 NUL）。 */
    fun readName(p: ByteArray): String {
        if (p.size < OFF_NAME + NAME_LEN) return ""
        val sb = StringBuilder()
        for (i in 0 until NAME_LEN) {
            val b = p[OFF_NAME + i].toInt() and 0xFF
            if (b == 0) break
            sb.append(if (b in 0x20..0x7E) b.toChar() else '_')
        }
        return sb.toString().trim()
    }

    /** 写名称（ASCII，最多 19 字符，剩余 NUL 补齐）。 */
    fun writeName(p: ByteArray, name: String) {
        java.util.Arrays.fill(p, OFF_NAME, OFF_NAME + NAME_LEN, 0)
        val bytes = name.toByteArray(StandardCharsets.US_ASCII)
        val n = minOf(bytes.size, NAME_LEN - 1, 19)
        System.arraycopy(bytes, 0, p, OFF_NAME, n)
    }

    /** 读 256 项 LUT，归一到 0..255（四舍五入，避免 32767 标度来回换算丢精度）。 */
    fun readLut(p: ByteArray): IntArray {
        val out = IntArray(LUT_COUNT)
        if (p.size < LUT_END) return CubicSplineLut.identityLut()
        for (k in 0 until LUT_COUNT) {
            val o = OFF_LUT + k * 2
            val v = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
            out[k] = ((v * 255L + LUT_SCALE / 2) / LUT_SCALE).toInt().coerceIn(0, 255)
        }
        return out
    }

    /** 写 256 项 LUT（0..255 → 0..32767，大端）。 */
    fun writeLut(p: ByteArray, lut: IntArray) {
        for (k in 0 until LUT_COUNT) {
            val v = ((lut.getOrElse(k) { k }.coerceIn(0, 255).toLong() * LUT_SCALE + 127) / 255).toInt()
            val o = OFF_LUT + k * 2
            p[o] = ((v shr 8) and 0xFF).toByte()
            p[o + 1] = (v and 0xFF).toByte()
        }
    }

    /** 读 4 组控制点。 */
    fun readPoints(p: ByteArray): List<CubicSplineLut.Point> {
        if (p.size < OFF_POINT_DATA + POINT_DATA_LEN) return emptyList()
        val list = ArrayList<CubicSplineLut.Point>(POINT_COUNT)
        for (i in 0 until POINT_COUNT) {
            val x = (p[OFF_POINT_DATA + i * 2].toInt() and 0xFF).toFloat()
            val y = (p[OFF_POINT_DATA + i * 2 + 1].toInt() and 0xFF).toFloat()
            if (x == 0f && y == 0f) continue
            list.add(CubicSplineLut.Point(x, y))
        }
        return list
    }

    /**
     * 写 4 组控制点。
     *
     * 机身同时存「控制点」和「256 项 LUT」两份数据：控制点是给机身 LCD 上那条曲线
     * 用来画图的，LUT 才是成像真正走的表。这里从我们自己的 LUT 里按等距 x 采样 4 点，
     * 保证两者自洽 —— 只改 LUT 不改点，机身上看到的曲线会是别人的。
     */
    fun writePointsFromLut(p: ByteArray, lut: IntArray) {
        for (i in 0 until POINT_COUNT) {
            val x = (i * 255) / (POINT_COUNT - 1)
            val y = lut.getOrElse(x) { x }.coerceIn(0, 255)
            p[OFF_POINT_DATA + i * 2] = x.toByte()
            p[OFF_POINT_DATA + i * 2 + 1] = y.toByte()
        }
    }

    /** 解析成内部 Recipe 模型（UI 预览 / 曲线编辑器用）。 */
    fun parse(raw: ByteArray): Recipe? {
        if (raw.size != SIZE) return null
        val lut = readLut(raw)
        val pts = readPoints(raw)
        val curve = if (pts.size >= 2) pts else CubicSplineLut.lutToPoints(lut, 5)
        val name = readName(raw).ifBlank { "CUSTOM" }
        return Recipe(
            name = name,
            baseProfile = "STANDARD",
            pcdType = 0,
            useCustomCurve = true,
            curvePoints = curve,
            lut = lut,
            styleAnalysis = "机身读回（${raw.size}B / 15 位 LUT）",
        )
    }

    /**
     * 基于模板构建 614 字节载荷。
     *
     * 只动三个区域：名称、4 组控制点、256 项 LUT。
     * 其余 590 字节原样保留 —— 机身固件对 +0x000..+0x065 那些未知字节有自己的校验，
     * 之前按 84 字节硬写把它们全冲掉，是写入被拒的另一半原因。
     */
    fun build(template: ByteArray?, recipe: Recipe): ByteArray {
        val p = if (template != null && template.size == SIZE) {
            template.copyOf()
        } else {
            fallbackTemplate()
        }
        writeName(p, recipe.name)
        // 以 Recipe.lut 为准，而不是每次从 curvePoints 重新插值。
        //
        // 两个理由：
        // 1. 曲线编辑器每次拖动都会同时写好 lut（MainViewModel.updateCurvePoints），
        //    所以 lut 里已经是用户最新的意图，没有信息丢失。
        // 2. 机身存的是 15 位（0..32767），我们从 4 个控制点重推只能拿回 8 位近似，
        //    等于把用户的原始曲线「重新量化」一遍。只改名称的场景绝不能这么做。
        val lut = if (recipe.useCustomCurve) {
            val declared = recipe.lut
            val fromPoints = CubicSplineLut.buildLut(recipe.curvePoints)
            when {
                declared.size != LUT_COUNT -> fromPoints
                // lut 还是默认恒等、但曲线点不是直线 → 以曲线点为准
                declared.contentEquals(CubicSplineLut.identityLut()) &&
                    fromPoints.size == LUT_COUNT &&
                    !fromPoints.contentEquals(declared) -> fromPoints
                else -> declared
            }
        } else {
            CubicSplineLut.identityLut()
        }
        // 先记下模板原有的 LUT，用来判断「这次到底改没改曲线」
        val oldLut = if (template != null && template.size == SIZE) readLut(p) else null
        val lutChanged = oldLut == null || !oldLut.contentEquals(lut)
        if (lutChanged) {
            // 曲线变了才重写 LUT 与控制点。
            //
            // 没变就一个字节都不碰 —— 机身存的是 15 位精度（0..32767），我们内部是
            // 8 位（0..255），来回换算必然损失精度。曲线没改却重写一遍，
            // 等于把用户的原始曲线「重新量化」一次，纯属自伤。
            writeLut(p, lut)
            writePointsFromLut(p, lut)
        }
        return p
    }

    /** 兜底模板：一个结构合法、曲线为线性的 614 字节记录。 */
    fun fallbackTemplate(): ByteArray {
        val p = ByteArray(SIZE)
        p[OFF_VERSION] = 0x02
        p[OFF_VERSION + 1] = 0x00
        p[OFF_POINT_COUNT] = 0x08
        p[0x01A] = 0x04
        p[0x023] = 0x01
        p[0x024] = 0x49   // 'I'
        p[0x025] = 0x30   // '0'
        p[0x027] = 0xFF.toByte()
        p[0x029] = 0xFF.toByte()
        p[0x02A] = 0x01
        p[0x02B] = 0x0F
        p[OFF_FAMILY] = 0x05
        writeName(p, "ZCLOUD")
        val linear = IntArray(LUT_COUNT) { it }
        writeLut(p, linear)
        writePointsFromLut(p, linear)
        return p
    }

    /** 把 614 字节记录渲染成「可读摘要」，写日志/UI 用。 */
    fun describe(raw: ByteArray): String {
        if (raw.size != SIZE) return "非 614 字节（${raw.size}B）"
        val name = readName(raw)
        val lut = readLut(raw)
        val pts = readPoints(raw)
        val ptText = pts.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" }
        val mono = (1 until LUT_COUNT).all { lut[it] >= lut[it - 1] }
        return "name=\"$name\" 点=$ptText LUT[0]=${lut[0]} LUT[128]=${lut[128]} " +
            "LUT[255]=${lut[255]} 单调=$mono"
    }
}
