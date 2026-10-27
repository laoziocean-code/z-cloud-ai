package com.zcloud.ai.ptp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 一条 Hex 日志（方向 / 事务 / 摘要 / 展开的字节）。 */
data class HexLogEntry(
    val seq: Long,
    val timestampMillis: Long,
    val direction: Direction,
    val transactionId: Long,
    val title: String,
    val bytes: ByteArray,
    val note: String = "",
) {
    enum class Direction { TX, RX, EVENT, INFO, WARN, ERROR }

    fun hexDump(bytesPerLine: Int = 16, maxBytes: Int = 4096): String {
        val sb = StringBuilder()
        val n = minOf(bytes.size, maxBytes)
        var i = 0
        while (i < n) {
            sb.append("%04X  ".format(i))
            val lineEnd = minOf(i + bytesPerLine, n)
            val hex = StringBuilder()
            val ascii = StringBuilder()
            for (j in i until lineEnd) {
                val b = bytes[j].toInt() and 0xFF
                hex.append("%02X ".format(b))
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            }
            // pad hex column
            repeat((bytesPerLine - (lineEnd - i)) * 3) { hex.append(' ') }
            sb.append(hex).append(" |").append(ascii).append("|\n")
            i = lineEnd
        }
        if (bytes.size > maxBytes) sb.append("... (${bytes.size - maxBytes} bytes truncated)\n")
        return sb.toString()
    }
}

/**
 * 全局 Hex 日志总线。UI 订阅 [entries]，驱动层往里写。
 * 用环形缓冲，避免长时间联调把内存吃爆。
 */
object HexLog {
    private const val CAPACITY = 600
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val _entries = MutableStateFlow<List<HexLogEntry>>(emptyList())
    val entries: StateFlow<List<HexLogEntry>> = _entries

    private var seq = 0L

    /**
     * 日志里是否带 Hex dump。
     *
     * 关掉之后单条日志只剩一行标题，文件能小一个数量级 —— 用户反馈「日志动辄几 MB」，
     * 大部分体积就是取景帧和缩略图的 dump。真要看字节级细节时在设置里打开即可。
     */
    @Volatile
    var hexDumpEnabled: Boolean = true

    /** 是否持续写盘。关掉后只在内存环形缓冲里留最近 600 条。 */
    @Volatile
    var fileLoggingEnabled: Boolean = true

    /** 持续落盘的日志文件。真机只有一个 USB-C 口，接电脑就没法接相机，所以必须落盘。 */
    @Volatile
    private var logFile: java.io.File? = null

    @Volatile
    private var logWriter: java.io.BufferedWriter? = null

    /**
     * 开启持续写盘。由 `ZCloudApp.onCreate` 调用一次。
     *
     * 每次 [log] 都会 append 一行并 flush —— PTP 事务频率低（一条命令 3~4 条日志），
     * 这点 IO 完全不影响握手。好处是 App 被杀/重启后日志还在，拔掉相机插回电脑就能取到。
     */
    @Synchronized
    fun startFileLogging(context: android.content.Context) {
        if (logWriter != null) return
        runCatching {
            val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val name = "ptp-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(java.util.Date()) + ".log"
            val f = java.io.File(dir, name)
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                // 超限就滚一份，只留当前 + 上一份
                val old = java.io.File(dir, "$name.old")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            logFile = f
            logWriter = java.io.BufferedWriter(java.io.FileWriter(f, true))
            appendLineRaw(
                "\n===== 会话开始 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())} | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                    " | Android ${android.os.Build.VERSION.RELEASE} | app ${appVersionCode(context)} ====="
            )
        }
    }

    fun currentLogFile(): java.io.File? = logFile

    /** 关掉持续落盘时调用：把句柄关掉，后续 log 只进内存。 */
    @Synchronized
    fun stopFileLogging() {
        runCatching { logWriter?.flush(); logWriter?.close() }
        logWriter = null
    }

    @Synchronized
    private fun appendLineRaw(line: String) {
        if (!fileLoggingEnabled) return
        val w = logWriter ?: return
        runCatching {
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    private fun formatEntry(e: HexLogEntry): String {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(e.timestampMillis))
        val head = "[$ts] ${e.direction} tx=${e.transactionId} ${e.title}" +
            if (e.note.isNotEmpty()) " — ${e.note}" else ""
        // 关掉 Hex dump 时只留标题行：一帧取景就有 20KB，dump 出来是几百行，日志会被它撑爆
        if (e.bytes.isEmpty()) return head
        if (!hexDumpEnabled) return head + "（${e.bytes.size}B，未 dump）"
        return head + "\n" + e.hexDump()
    }

    @Synchronized
    fun log(
        direction: HexLogEntry.Direction,
        transactionId: Long,
        title: String,
        bytes: ByteArray,
        note: String = "",
    ) {
        val entry = HexLogEntry(
            seq = ++seq,
            timestampMillis = System.currentTimeMillis(),
            direction = direction,
            transactionId = transactionId,
            title = title,
            bytes = bytes,
            note = note,
        )
        val cur = _entries.value
        val next = if (cur.size >= CAPACITY) cur.drop(cur.size - CAPACITY + 1) + entry else cur + entry
        _entries.value = next
        // 同时写盘：环形缓冲会丢旧记录，文件不会
        appendLineRaw(formatEntry(entry))
    }

    fun info(title: String, note: String = "") =
        log(HexLogEntry.Direction.INFO, -1, title, ByteArray(0), note)

    fun warn(title: String, note: String = "") =
        log(HexLogEntry.Direction.WARN, -1, title, ByteArray(0), note)

    fun error(title: String, note: String = "") =
        log(HexLogEntry.Direction.ERROR, -1, title, ByteArray(0), note)

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun dumpText(): String = _entries.value.joinToString("\n") { e ->
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(e.timestampMillis))
        val head = "[$ts] ${e.direction} tx=${e.transactionId} ${e.title}" +
            if (e.note.isNotEmpty()) " — ${e.note}" else ""
        if (e.bytes.isEmpty()) head else head + "\n" + e.hexDump()
    }

    /**
     * 把当前日志落盘到应用专属外部目录（`Android/data/<pkg>/files/`）。
     *
     * 为什么需要它：真机联调时 USB-C 口只有一个，接了电脑做 adb 就没法接相机。
     * 让 App 自己写文件，用户拔线接相机跑一轮，再用文件管理器取日志，绕开这个死结。
     * 应用专属目录不需要任何存储权限，卸载即清理。
     */
    fun saveToFile(context: android.content.Context, prefix: String = "zcloud-log"): java.io.File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val f = java.io.File(dir, "$prefix-$ts.txt")
        return try {
            val header = buildString {
                appendLine("# Z-Cloud AI PTP 日志")
                appendLine("# 生成时间: $ts")
                appendLine("# 条目数: ${_entries.value.size}")
                appendLine("# 设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("# 应用: versionCode=${appVersionCode(context)}")
                appendLine()
            }
            f.writeText(header + dumpText())
            f
        } catch (t: Throwable) {
            null
        }
    }

    private fun appVersionCode(context: android.content.Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionCode} / ${pi.versionName}"
    } catch (t: Throwable) {
        "?"
    }
}
