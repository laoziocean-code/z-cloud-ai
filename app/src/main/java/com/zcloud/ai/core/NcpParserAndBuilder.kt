package com.zcloud.ai.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 一套完整的优化校准配方（应用内部模型）。
 * 字段与相机 84 字节载荷一一对应，额外带 UI 层的可读信息。
 */
data class Recipe(
    val name: String = "AI_RECIPE",
    val baseProfile: String = "STANDARD",
    /** 机身 pcd_type：0=STANDARD/NEUTRAL/VIVID/FLAT 等彩色族，1=MONOCHROME，2=SEPIA..CARBON，8=DREAM..TOY */
    val pcdType: Int = 0,
    val sharpening: Float = 0f,
    val clarity: Float = 0f,
    val contrast: Float = 0f,
    val brightness: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val useCustomCurve: Boolean = true,
    val curvePoints: List<CubicSplineLut.Point> = DEFAULT_CURVE,
    val styleAnalysis: String = "",
    val lut: IntArray = CubicSplineLut.identityLut(),
) {
    companion object {
        val DEFAULT_CURVE = listOf(
            CubicSplineLut.Point(0f, 0f),
            CubicSplineLut.Point(64f, 64f),
            CubicSplineLut.Point(128f, 128f),
            CubicSplineLut.Point(192f, 192f),
            CubicSplineLut.Point(255f, 255f),
        )
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = name.hashCode()
}

/**
 * `.NCP` 文件解析 + 机身优化校准载荷构建。
 *
 * ⚠️ **重要更正（v1.5.0）**：机身 `0x90CC`/`0x90CD` 线上跑的**不是** 84 字节，
 * 而是 **614 字节**（见 [PcPayload614]）。614 字节才是真机实测的 PTP 载荷格式，
 * 由 `ptp-20261027.log` 里两条 `DATA-IN len=614` 逐字节破译而来。
 *
 * 下面这段 84 字节布局来自固件逆向（`work/out/RE_picture_control.md`），
 * 它是**相机内部 SDS 存储结构**，不是 PTP 线上格式 —— 拿它去写 0x90CD 会被
 * 机身按长度校验直接拒（AccessDenied 0x200F / InvalidParameter 0x201D）。
 * 保留它只为兼容用户手里可能存在的 84 字节 `.NCP` 旧文件（导入时能读出来）。
 *
 * ── 84 字节内部结构（仅用于兼容旧 NCP 文件，不用于 PTP 传输） ──
 *
 *   +0x00  20B   ASCII 名称（NUL 补齐，最多 19 字符 + 终止符）
 *   +0x14   4B   u32 = 0（保留）
 *   +0x18   1B   pcd_type
 *   +0x19   1B   0（保留）
 *   +0x1A  18B   曲线 A（亮度）
 *   +0x2C  18B   曲线 B（RGB 共用）
 *   +0x3E  18B   曲线 C（对比补偿）
 *   +0x50   2B   u16 = index | (flags << 8)
 *   +0x52   2B   保留
 *   ── 合计 84 (0x54) ──
 */
object NcpParserAndBuilder {

    /** 旧版 84 字节内部结构长度（仅导入兼容用）。 */
    const val LEGACY_PAYLOAD_SIZE = 0x54          // 84

    /** 机身 PTP 线上真正的载荷长度。 */
    const val PAYLOAD_SIZE = PcPayload614.SIZE    // 614

    const val OFF_NAME = 0x00
    const val NAME_LEN = 20
    const val OFF_RESERVED0 = 0x14
    const val OFF_PCD_TYPE = 0x18
    const val OFF_RESERVED1 = 0x19
    const val OFF_CURVE_A = 0x1A
    const val OFF_CURVE_B = 0x2C
    const val OFF_CURVE_C = 0x3E
    const val OFF_INDEX_FLAGS = 0x50
    const val OFF_TAIL = 0x52
    const val CURVE_SEG_LEN = 18
    const val NEUTRAL = 0x80
    const val NA = 0xFF

    // ---------------- 载荷构建 ----------------

    /**
     * 基于机身模板构建 614 字节载荷。
     *
     * @param template 由 0x90CC 从机身读回的原始 614 字节。传 null 时用内置线性模板。
     * @param recipe   要写入的配方
     * @param slotIndex 目标槽位号 1..9（614 格式里没有独立的 index 字段，
     *   槽位由 0x90CD 的第一个参数指定，这里保留形参仅为调用点兼容）
     */
    fun buildPayload(
        template: ByteArray?,
        recipe: Recipe,
        @Suppress("UNUSED_PARAMETER") slotIndex: Int = 1,
    ): ByteArray = PcPayload614.build(template, recipe)

    /** 内置默认模板（614 字节，线性曲线）。 */
    fun defaultTemplate(): ByteArray = PcPayload614.fallbackTemplate()

    // ---------------- 载荷 → 配方（读回校验用） ----------------

    /**
     * 把任意读回的原始字节归一化成标准 **614 字节**载荷。
     *
     * 先认 614（真机格式）；认不出来再尝试 84 字节旧结构（升级前导入的 .NCP），
     * 把它升级成 614 返回。两者都不像就返回 null。
     */
    fun normalizePayload(raw: ByteArray): ByteArray? {
        if (PcPayload614.looksLike614(raw)) return raw
        if (raw.size > PcPayload614.SIZE) {
            val off = raw.size - PcPayload614.SIZE
            val tail = raw.copyOfRange(off, raw.size)
            if (PcPayload614.looksLike614(tail)) return tail
        }
        // 旧 84 字节结构 → 升级成 614
        val legacy = normalizeLegacy84(raw)
        if (legacy != null) return PcPayload614.build(null, parseLegacyPayload(legacy)!!)
        // 纯 614 大小但不以 02 00 开头：可能是别的固件变体，宽松接受
        if (raw.size == PcPayload614.SIZE) return raw
        return null
    }

    /**
     * 把机身返回的原始字节归一化成标准 84 字节旧结构。
     * 仅用于兼容升级前导入的 .NCP 文件，**不要**再拿它去调 0x90CD。
     */
    fun normalizeLegacy84(raw: ByteArray): ByteArray? {
        if (raw.size < LEGACY_PAYLOAD_SIZE) return null
        if (raw.size == LEGACY_PAYLOAD_SIZE) return raw

        var best: ByteArray? = null
        var bestScore = -1
        val maxOffset = minOf(raw.size - LEGACY_PAYLOAD_SIZE, 8)
        for (off in 0..maxOffset) {
            val candidate = raw.copyOfRange(off, off + LEGACY_PAYLOAD_SIZE)
            val score = scoreAsPayload(candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return if (bestScore >= 3) best else raw.copyOfRange(0, LEGACY_PAYLOAD_SIZE)
    }

    /** 给一个 84 字节候选打分，越大越像真载荷。 */
    fun scoreAsPayload(p: ByteArray): Int {
        if (p.size < LEGACY_PAYLOAD_SIZE) return -1
        var score = 0

        // 名称区
        var printable = 0
        for (i in 0 until NAME_LEN) {
            val b = p[i].toInt() and 0xFF
            if (b in 0x20..0x7E || b == 0) printable++
        }
        score += printable * 2 / NAME_LEN
        if (p[0].toInt() and 0xFF in 0x41..0x5A) score += 2   // 首字母大写更像预设名

        // pcd_type
        val pcd = p[OFF_PCD_TYPE].toInt() and 0xFF
        if (pcd in intArrayOf(0, 1, 2, 8)) score += 2

        // 曲线段：单调 + 中性/忽略点密集
        val seg = p.copyOfRange(OFF_CURVE_A, OFF_CURVE_A + CURVE_SEG_LEN)
        var neutral = 0
        var monotonic = true
        var prev = -1
        for (b in seg) {
            val v = b.toInt() and 0xFF
            if (v == NEUTRAL || v == NA) neutral++
            if (prev >= 0 && v < prev - 8) monotonic = false
            prev = v
        }
        if (monotonic) score += 3
        score += neutral / 6

        return score
    }

    /**
     * 把读回的字节解析成内部 Recipe 模型。
     * 614 字节走 [PcPayload614.parse]，旧 84 字节走 [parseLegacyPayload]。
     */
    fun parsePayload(payload: ByteArray): Recipe? {
        if (payload.size == PcPayload614.SIZE) {
            PcPayload614.parse(payload)?.let { return it }
        }
        val legacy = normalizeLegacy84(payload) ?: return null
        return parseLegacyPayload(legacy)
    }

    /** 解析旧 84 字节内部结构。仅用于兼容升级前导入的 .NCP 文件。 */
    fun parseLegacyPayload(payload: ByteArray): Recipe? {
        if (payload.size < LEGACY_PAYLOAD_SIZE) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val name = readName(payload, OFF_NAME, NAME_LEN)
        val pcdType = payload[OFF_PCD_TYPE].toInt() and 0xFF
        val segA = payload.copyOfRange(OFF_CURVE_A, OFF_CURVE_A + CURVE_SEG_LEN)
        val lut = CubicSplineLut.curveSegmentToLut(segA, NEUTRAL)
        val pts = CubicSplineLut.lutToPoints(lut, 5)
        val idxFlags = bb.getShort(OFF_INDEX_FLAGS).toInt()
        return Recipe(
            name = name.ifBlank { "SLOT_${idxFlags and 0xFF}" },
            baseProfile = pcdTypeToProfileName(pcdType),
            pcdType = pcdType,
            useCustomCurve = !isLinearSegment(segA),
            curvePoints = pts,
            lut = lut,
        )
    }

    private fun isLinearSegment(seg: ByteArray): Boolean {
        for (i in 0 until CURVE_SEG_LEN) {
            val expect = kotlin.math.round(i * 255.0 / 17.0).toInt()
            val got = seg[i].toInt() and 0xFF
            if (got == NA) continue
            if (kotlin.math.abs(got - expect) > 2) return false
        }
        return true
    }

    fun pcdTypeToProfileName(t: Int): String = when (t) {
        1 -> "MONOCHROME"
        2 -> "SEPIA"
        8 -> "DREAM"
        else -> "STANDARD"
    }

    fun profileNameToPcdType(name: String): Int = when (name.uppercase()) {
        "MONOCHROME", "MONO" -> 1
        "SEPIA", "BLUE", "RED", "PINK", "CHARCOAL", "GRAPHITE", "BINARY", "CARBON" -> 2
        "DREAM", "MORNING", "POP", "SUNDAY", "SOMBER", "DRAMA", "SILENCE", "BLEACH",
        "MELANCHOLIC", "PURE", "DENIM", "TOY" -> 8
        else -> 0
    }

    // ---------------- .NCP 文件解析 ----------------

    data class NcpFile(
        val fileName: String,
        val recipe: Recipe,
        val rawSize: Int,
        val layout: String,
    )

    fun parseNcpFile(fileName: String, bytes: ByteArray): Result<NcpFile> = runCatching {
        if (bytes.size < 64) throw IllegalArgumentException("文件过小，不是合法 NCP")

        // 布局 0（首选）：614 字节机身格式 —— 裸的，或带 4 字节 "NCP\0" 魔数头
        PcPayload614.let { fmt ->
            val direct = if (fmt.looksLike614(bytes)) bytes else null
            val afterMagic = if (
                bytes.size == fmt.SIZE + 4 &&
                bytes[0] == 'N'.code.toByte() &&
                bytes[1] == 'C'.code.toByte() &&
                bytes[2] == 'P'.code.toByte()
            ) {
                val body = bytes.copyOfRange(4, bytes.size)
                if (fmt.looksLike614(body)) body else null
            } else null
            val body = direct ?: afterMagic
            if (body != null) {
                val parsed = fmt.parse(body)
                if (parsed != null) {
                    val named = parsed.copy(name = parsed.name.ifBlank { baseName(fileName) })
                    return@runCatching NcpFile(
                        fileName = fileName,
                        recipe = named,
                        rawSize = bytes.size,
                        layout = if (direct != null) "pc614-raw" else "pc614-ncp@0x4",
                    )
                }
            }
        }

        // 布局 1：旧 84 字节内部结构（或 84 的整数倍）
        if (bytes.size % LEGACY_PAYLOAD_SIZE == 0 && bytes.size <= LEGACY_PAYLOAD_SIZE * 4) {
            val candidate = bytes.copyOfRange(0, LEGACY_PAYLOAD_SIZE)
            val parsed = parseLegacyPayload(candidate)
            if (parsed != null && looksLikePayload(candidate)) {
                return@runCatching NcpFile(
                    fileName,
                    parsed.copy(name = parsed.name.ifBlank { baseName(fileName) }),
                    bytes.size,
                    "legacy84-raw",
                )
            }
        }

        // 布局 2：官方容器 "NCP" 魔数
        val magic = String(bytes, 0, minOf(4, bytes.size), StandardCharsets.US_ASCII)
        if (magic.startsWith("NCP")) {
            val found = scanForCurveBlock(bytes)
            if (found != null) {
                val (offset, len) = found
                val seg = bytes.copyOfRange(offset, offset + CURVE_SEG_LEN)
                val lut = CubicSplineLut.curveSegmentToLut(seg, NEUTRAL)
                val name = readName(bytes, 4, 20).ifBlank { baseName(fileName) }
                return@runCatching NcpFile(
                    fileName = fileName,
                    recipe = Recipe(
                        name = name,
                        baseProfile = "STANDARD",
                        pcdType = 0,
                        useCustomCurve = !isLinearSegment(seg),
                        curvePoints = CubicSplineLut.lutToPoints(lut, 5),
                        lut = lut,
                    ),
                    rawSize = bytes.size,
                    layout = "ncp-container@0x%X".format(offset),
                )
            }
        }

        // 布局 3：在整个文件里嗅探曲线块
        val found = scanForCurveBlock(bytes)
            ?: throw IllegalArgumentException("无法识别 NCP 结构（未找到曲线块）")
        val (offset, _) = found
        val seg = bytes.copyOfRange(offset, offset + CURVE_SEG_LEN)
        val lut = CubicSplineLut.curveSegmentToLut(seg, NEUTRAL)
        NcpFile(
            fileName = fileName,
            recipe = Recipe(
                name = baseName(fileName),
                pcdType = 0,
                useCustomCurve = !isLinearSegment(seg),
                curvePoints = CubicSplineLut.lutToPoints(lut, 5),
                lut = lut,
            ),
            rawSize = bytes.size,
            layout = "sniffed@0x%X".format(offset),
        )
    }

    /** 扫描连续 18 字节、只含 0x80/0xFF/单调区间的曲线块。返回 (offset, 18)。 */
    private fun scanForCurveBlock(bytes: ByteArray): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestScore = 0
        var i = 0
        while (i + CURVE_SEG_LEN <= bytes.size) {
            var score = 0
            var prev = -1
            var monotonic = true
            for (j in 0 until CURVE_SEG_LEN) {
                val v = bytes[i + j].toInt() and 0xFF
                if (v == NA) { score += 2; continue }
                if (v == NEUTRAL) score += 1
                if (prev >= 0 && v < prev - 6) monotonic = false
                prev = v
            }
            if (monotonic && score > bestScore) {
                bestScore = score
                best = i to CURVE_SEG_LEN
            }
            i++
        }
        return if (bestScore >= 6) best else null
    }

    private fun looksLikePayload(p: ByteArray): Boolean {
        // 名称区应大部分是可打印 ASCII 或 0
        var printable = 0
        for (i in 0 until NAME_LEN) {
            val b = p[i].toInt() and 0xFF
            if (b == 0 || b in 0x20..0x7E) printable++
        }
        return printable >= NAME_LEN - 2
    }

    // ---------------- 名称编解码 ----------------

    /** 机身名称区：ASCII，最多 19 字符 + NUL。非 ASCII 字符降级为 '_'。 */
    fun encodeName(name: String): ByteArray {
        val cleaned = name.trim().take(19).map { c ->
            if (c.code in 0x20..0x7E) c else '_'
        }.joinToString("")
        return cleaned.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun readName(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val maxEnd = minOf(offset + len, buf.size)
        while (end < maxEnd && buf[end].toInt() != 0) end++
        return String(buf, offset, end - offset, StandardCharsets.US_ASCII).trim()
    }

    private fun baseName(fileName: String): String =
        fileName.substringBeforeLast('.').take(19).ifBlank { "NCP_IMPORT" }

    // ---------------- 导出 ----------------

    /** 导出为 .NCP（裸载荷 + 4 字节 "NCP" 魔数头，便于分发与再导入）。 */
    fun exportNcp(payload: ByteArray): ByteArray {
        val header = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte(), 0)
        return header + payload
    }
}
