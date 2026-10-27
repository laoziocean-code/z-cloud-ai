package com.zcloud.ai.data

/**
 * 常规设置。
 *
 * 全部落在 DataStore 里（一个 JSON 字符串），App 重启后保留。
 * 每一项都对应一个「用户真的会想改」的取舍，没有为了填满设置页而存在的项：
 *
 * - [galleryLimit] 相册读多少张。读得多 = 等待久，这是真实矛盾，必须给用户自己选。
 * - [liveViewIntervalMs] 取景轮询间隔。手机上取景是「预览」不是「录制」，
 *   150ms（约 6fps）对构图对焦完全够；但有人要看清运动瞬间，所以给到 60ms。
 * - [backupBeforeWrite] 写入前先读一遍机身原样模板。默认开：万一写坏了还能照着原样写回去。
 * - [verifyAfterWrite] 写完立刻回读校验名称。默认开：宁慢 300ms，不要静默失败。
 * - [autoSaveCapture] 拍完自动进手机相册。默认关：很多人只是先看一眼，不想污染相册。
 * - [logToFile] 持续落盘。默认开：出问题时日志是唯一的证据（手机只有一个 USB-C 口，
 *   接电脑就没法接相机，所以必须 App 自己留证据）。
 * - [hexInLog] 日志里是否带 Hex dump。关掉后文件能小一个数量级，代价是看不到字节级细节。
 * - [defaultTargetSlot] 每次进 App 的默认目标槽位。
 * - [keepScreenOn] 监看页常亮。拍延时/等光的时候屏幕自己灭掉很烦。
 */
data class AppSettings(
    val galleryLimit: Int = 100,
    val liveViewIntervalMs: Int = 150,
    val backupBeforeWrite: Boolean = true,
    val verifyAfterWrite: Boolean = true,
    val autoSaveCapture: Boolean = false,
    val logToFile: Boolean = true,
    val hexInLog: Boolean = true,
    val defaultTargetSlot: Int = 1,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        /** 相册张数可选档位。200 已经是「能忍」的上限，再多就是分钟级等待。 */
        val GALLERY_LIMITS = listOf(50, 100, 200)

        /** 取景间隔档位：60=流畅 / 150=标准 / 300=省电 / 500=极省。 */
        val LIVE_VIEW_INTERVALS = listOf(60, 150, 300, 500)

        fun galleryLimitLabel(v: Int): String = when (v) {
            50 -> "50 张 · 最快"
            100 -> "100 张 · 推荐"
            200 -> "200 张 · 较慢"
            else -> "$v 张"
        }

        fun liveViewLabel(v: Int): String = when (v) {
            60 -> "流畅 · 约 16fps"
            150 -> "标准 · 约 6fps"
            300 -> "省电 · 约 3fps"
            500 -> "极省 · 约 2fps"
            else -> "$v ms"
        }
    }
}
