package com.zcloud.ai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcloud.ai.core.CameraStorage

/**
 * Tab「相册」：机内照片管理。
 *
 * 只要缩略图能出来，用户就能在手机上挑片，不必掏 SD 卡；
 * 下载走 MediaStore，不申请存储权限也能落到系统相册。
 */
@Composable
fun GalleryScreen(ui: UiState, vm: MainViewModel) {
    val items = remember(ui.galleryItems, ui.galleryFilter) {
        when (ui.galleryFilter) {
            1 -> ui.galleryItems.filter { !it.isVideo }
            2 -> ui.galleryItems.filter { it.isVideo }
            else -> ui.galleryItems
        }
    }
    var selected by remember { mutableStateOf<CameraStorage.Item?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // 存储卷容量
        if (ui.galleryVolumes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.galleryVolumes.forEach { v ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ZColors.Surface)
                            .padding(8.dp),
                    ) {
                        Column {
                            Text(v.label, color = ZColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "剩 ${v.freeImages} 张 · ${fmtBytes(v.freeBytes)}",
                                color = ZColors.TextTertiary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(ZColors.SurfaceHigher)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(v.usedRatio.coerceIn(0f, 1f))
                                        .height(3.dp)
                                        .background(if (v.usedRatio > 0.9f) ZColors.Danger else ZColors.NikonYellow)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 工具条
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZPrimaryButton(
                text = if (ui.galleryLoading) "读取中 ${ui.galleryProgress}" else "刷新最近 ${ui.galleryLimit} 张",
                onClick = { vm.refreshGallery() },
                modifier = Modifier.weight(1f),
                enabled = ui.isConnected && !ui.galleryLoading,
                loading = ui.galleryLoading,
            )
            listOf(0 to "全部", 1 to "照片", 2 to "视频").forEach { (f, label) ->
                val sel = ui.galleryFilter == f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) ZColors.NikonYellow else ZColors.SurfaceHigh)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .pointerInputTap { vm.setGalleryFilter(f) },
                ) {
                    Text(
                        label,
                        color = if (sel) Color(0xFF121212) else ZColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        if (ui.downloadMsg.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                ui.downloadMsg,
                color = ZColors.TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "只读取最近 ${ui.galleryLimit} 张（按拍摄时间倒序）—— 全卡遍历要给每个文件发一次 GetObjectInfo，" +
                "几百张就是几百次往返，太慢。需要更早的素材请用读卡器。",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (!ui.isConnected) {
            EmptyHint("先连接 Z30，再刷新机身列表")
        } else if (items.isEmpty() && !ui.galleryLoading) {
            EmptyHint("机身里还没有可读取的文件\n（或点上面「刷新机身列表」）")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.handle }) { item ->
                    GalleryCell(item = item, thumb = ui.thumbnails[item.handle]) { selected = item }
                }
            }
        }
    }

    selected?.let { item ->
        ItemSheet(item = item, ui = ui, vm = vm, onDismiss = { selected = null })
    }
}

@Composable
private fun GalleryCell(item: CameraStorage.Item, thumb: ByteArray?, onClick: () -> Unit) {
    val bmp = remember(thumb) {
        thumb?.let {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(ZColors.Surface)
            .pointerInputTap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                if (item.isVideo) "▶" else "…",
                color = ZColors.TextTertiary,
                fontSize = 18.sp,
            )
        }
        // 底部信息条
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                item.baseName.take(16),
                color = ZColors.TextPrimary,
                fontSize = 8.sp,
                maxLines = 1,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                item.displaySize,
                color = ZColors.TextTertiary,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ItemSheet(
    item: CameraStorage.Item,
    ui: UiState,
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZColors.SurfaceHigh,
        titleContentColor = ZColors.TextPrimary,
        textContentColor = ZColors.TextSecondary,
        title = { Text(item.filename, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                Text("分辨率 ${item.resolution}", fontSize = 11.sp)
                Text("大小 ${item.displaySize}", fontSize = 11.sp)
                Text("拍摄时间 ${item.capturedAt}", fontSize = 11.sp)
                if (ui.downloadingHandle == item.handle) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = ZColors.NikonYellow,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("下载中…", fontSize = 11.sp, color = ZColors.NikonYellow)
                    }
                }
            }
        },
        confirmButton = {
            ZPrimaryButton(
                text = "下载到手机",
                onClick = { vm.downloadItem(item); onDismiss() },
                enabled = ui.downloadingHandle == 0L,
            )
        },
        dismissButton = {
            ZOutlineButton(
                text = "删除",
                onClick = { vm.deleteItem(item); onDismiss() },
                enabled = ui.downloadingHandle == 0L,
            )
        },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = ZColors.TextTertiary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 小工具：指尖就够的点击。 */
private fun Modifier.pointerInputTap(onTap: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
)


private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.0f MB".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
