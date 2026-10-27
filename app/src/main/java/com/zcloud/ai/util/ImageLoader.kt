package com.zcloud.ai.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream

/** 从相册 Uri 读 Bitmap，带降采样（避免直接解码 5000 万像素照片 OOM）。 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    fun load(context: Context, uri: Uri, maxEdge: Int = 1600): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longEdge = maxOf(w, h)
                if (longEdge > maxEdge) {
                    val ratio = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize((w * ratio).toInt(), (h * ratio).toInt())
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(context, uri, maxEdge)
        }
    }.getOrElse { t ->
        Log.w(TAG, "load failed: ${uri}", t)
        runCatching { decodeLegacy(context, uri, maxEdge) }.getOrNull()
    }

    private fun decodeLegacy(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        var longEdge = maxOf(w, h)
        while (longEdge / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** 读原始字节（用于 .NCP 导入）。失败返回 null（调用方拿不到原因，排障请用 [readBytesOrThrow]）。 */
    fun readBytes(context: Context, uri: Uri, maxBytes: Int = 16 * 1024 * 1024): ByteArray? =
        runCatching { readBytesOrThrow(context, uri, maxBytes) }.getOrNull()

    /**
     * 读原始字节，失败时抛出带原因异常。
     *
     * 为什么要有这个版本：以前 `readBytes` 用 runCatching 把「文件超过上限」「URI 打不开」
     * 「不是 NCP」全部吞成 null，界面上只剩一句「读取文件失败」，用户和我们都无从下手。
     */
    fun readBytesOrThrow(context: Context, uri: Uri, maxBytes: Int = 16 * 1024 * 1024): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开文件（URI 可能已失效，请重新选择）")
        input.use { stream ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) throw IllegalStateException("文件超过 ${maxBytes / 1024}KB，拒绝读取")
                bos.write(buf, 0, n)
            }
            if (total == 0) throw IllegalStateException("文件是空的（0 字节）")
            return bos.toByteArray()
        }
    }

    /** 查询显示名，用于 NCP 预设命名。 */
    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "import.NCP"
    }.getOrElse { "import.NCP" }
}
