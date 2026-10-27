package com.zcloud.ai.core

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * PTP 标准数据集解析：存储卷 / 对象信息 / 对象句柄列表。
 *
 * 数据集布局来自 ISO 15740（PTP）：
 *   StorageInfo   : u16 AccessCapability, u64 MaxCapacity, u64 FreeSpaceInBytes,
 *                   u32 FreeSpaceInImages, PtpString StorageDescription, PtpString VolumeLabel
 *   ObjectInfo    : u32 StorageID, u16 ObjectFormat, u16 ProtectionStatus, u32 ObjectCompressedSize,
 *                   u16 ThumbFormat, u32 ThumbCompressedSize, u32 ThumbPixWidth, u32 ThumbPixHeight,
 *                   u32 ImagePixWidth, u32 ImagePixHeight, u32 ImageBitDepth, u32 ParentObject,
 *                   u16 AssociationType, u32 AssociationDesc, u32 SequenceNumber,
 *                   PtpString Filename, PtpString CaptureDate, PtpString ModificationDate, PtpString Keywords
 *
 * PtpString = u8 (字符数，含结尾 NUL) + UTF-16LE 内容 + 0x0000。
 */
object CameraStorage {

    // ---------------- 数据结构 ----------------

    data class Volume(
        val id: Long,
        val name: String,
        val capacityBytes: Long,
        val freeBytes: Long,
        val freeImages: Long,
        val readOnly: Boolean,
    ) {
        val usedBytes: Long get() = (capacityBytes - freeBytes).coerceAtLeast(0)
        val usedRatio: Float
            get() = if (capacityBytes <= 0) 0f
            else (usedBytes.toDouble() / capacityBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        val label: String
            get() = if (name.isNotBlank()) name else "存储卡 %08X".format(id)
    }

    data class Item(
        val handle: Long,
        val storageId: Long,
        val format: Int,
        val filename: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val capturedAt: String,
        val protectedObject: Boolean,
        val sequence: Long = 0,
    ) {
        val isVideo: Boolean
            get() {
                val lower = filename.lowercase(Locale.ROOT)
                return lower.endsWith(".mov") || lower.endsWith(".mp4") ||
                    lower.endsWith(".avi") || lower.endsWith(".m4v") ||
                    lower.endsWith(".mts") || lower.endsWith(".m2ts") ||
                    lower.endsWith(".nef") && false
            }

        val isRaw: Boolean
            get() {
                val lower = filename.lowercase(Locale.ROOT)
                return lower.endsWith(".nef") || lower.endsWith(".nrw") ||
                    lower.endsWith(".dng")
            }

        val displaySize: String
            get() {
                if (sizeBytes <= 0) return "—"
                val mb = sizeBytes / 1024.0 / 1024.0
                return if (mb >= 1024) "%.2f GB".format(mb / 1024) else "%.1f MB".format(mb)
            }

        val resolution: String
            get() = if (width > 0 && height > 0) "${width}×${height}" else "—"

        /** 文件名前半段（去掉扩展名），用于展示。 */
        val baseName: String
            get() = filename.substringBeforeLast('.', filename)
    }

    data class Snapshot(
        val volumes: List<Volume>,
        val items: List<Item>,
    ) {
        val capacityBytes: Long get() = volumes.sumOf { it.capacityBytes }
        val freeBytes: Long get() = volumes.sumOf { it.freeBytes }
        val photoCount: Int get() = items.count { !it.isVideo }
        val videoCount: Int get() = items.count { it.isVideo }
    }

    // ---------------- 解析 ----------------

    fun parseStorageIds(data: ByteArray?): List<Long> {
        if (data == null || data.size < 4) return emptyList()
        val count = safeCount(u32(data, 0), data.size - 4, 4)
        val out = ArrayList<Long>(count)
        for (i in 0 until count) out.add(u32(data, 4 + i * 4))
        return out
    }

    fun parseObjectHandles(data: ByteArray?): List<Long> = parseStorageIds(data)

    fun parseStorageInfo(storageId: Long, data: ByteArray?): Volume {
        if (data == null || data.size < 26) {
            return Volume(storageId, "存储卡 %08X".format(storageId), 0, 0, 0, false)
        }
        val access = u16(data, 4)
        val capacity = u64Saturated(data, 6)
        val free = u64Saturated(data, 14)
        val freeImages = u32(data, 22)
        val description = readPtpString(data, 26)
        val label = readPtpString(data, description.next)
        val name = label.value.ifBlank {
            description.value.ifBlank { "存储卡 %08X".format(storageId) }
        }
        return Volume(
            id = storageId,
            name = name,
            capacityBytes = capacity,
            freeBytes = free,
            freeImages = freeImages,
            readOnly = access != 0,
        )
    }

    /** 协会对象（文件夹）返回 null。 */
    fun parseObjectInfo(handle: Long, data: ByteArray?): Item? {
        if (data == null || data.size < 52) return null
        val storageId = u32(data, 0)
        val format = u16(data, 4)
        val protection = u16(data, 6)
        val size = u32(data, 8)
        val width = saturated(u32(data, 26))
        val height = saturated(u32(data, 30))
        val associationType = u16(data, 42)
        val sequence = u32(data, 48)
        val filename = readPtpString(data, 52)
        val capturedAt = readPtpString(data, filename.next)
        if (associationType != 0 || filename.value.isBlank()) return null
        return Item(
            handle = handle,
            storageId = storageId,
            format = format,
            filename = filename.value,
            sizeBytes = size,
            width = width,
            height = height,
            capturedAt = prettyDate(capturedAt.value),
            protectedObject = protection != 0,
            sequence = sequence,
        )
    }

    fun isAssociation(data: ByteArray?): Boolean =
        data != null && data.size >= 52 && u16(data, 42) != 0

    // ---------------- 内部工具 ----------------

    internal data class PtpString(val value: String, val next: Int)

    /** PTP 字符串：u8 长度（含结尾 NUL）+ UTF-16LE。 */
    internal fun readPtpString(data: ByteArray, offset: Int): PtpString {
        if (offset < 0 || offset >= data.size) return PtpString("", data.size)
        val chars = data[offset].toInt() and 0xFF
        if (chars == 0) return PtpString("", offset + 1)
        val byteCount = ((chars - 1) * 2).coerceAtLeast(0)
        var available = byteCount.coerceAtMost(data.size - offset - 1).coerceAtLeast(0)
        available -= available % 2
        val value = String(data, offset + 1, available, StandardCharsets.UTF_16LE)
        val next = (offset + 1 + chars * 2).coerceAtMost(data.size)
        return PtpString(value, next)
    }

    fun u16(data: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > data.size) return 0
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(data: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > data.size) return 0
        return (data[off].toLong() and 0xFF) or
            ((data[off + 1].toLong() and 0xFF) shl 8) or
            ((data[off + 2].toLong() and 0xFF) shl 16) or
            ((data[off + 3].toLong() and 0xFF) shl 24)
    }

    fun u64Saturated(data: ByteArray, off: Int): Long {
        val low = u32(data, off)
        val high = u32(data, off + 4)
        if (high > 0x7FFF_FFFFL) return Long.MAX_VALUE
        val v = (high shl 32) or low
        return if (v < 0) Long.MAX_VALUE else v
    }

    private fun safeCount(requested: Long, available: Int, stride: Int): Int =
        minOf(requested, Int.MAX_VALUE.toLong()).toInt().coerceAtMost(available / stride)

    private fun saturated(v: Long): Int = if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()

    /** "20261027T031500" → "2026-10-27 03:15"。 */
    fun prettyDate(raw: String): String {
        if (raw.length < 8) return raw.ifBlank { "—" }
        val date = "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
        if (raw.length >= 15 && raw[8] == 'T') {
            return "$date ${raw.substring(9, 11)}:${raw.substring(11, 13)}"
        }
        return date
    }
}
