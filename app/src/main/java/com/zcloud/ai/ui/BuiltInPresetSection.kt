package com.zcloud.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcloud.ai.core.BuiltInPreset
import com.zcloud.ai.core.BuiltInPresets
import com.zcloud.ai.ui.components.CurveThumbnail

/**
 * 内置 200 套优化校准。
 *
 * 设计取舍：
 * - **分类 chip 在最上面**，因为用户的第一反应是「我要拍什么」，而不是「我要什么曲线」；
 * - **每一行自带一条灰阶预览带**，把该配方的 LUT 作用在 0~255 灰阶上直接画出来——
 *   比单纯画曲线更接近「这张照片会变什么样」，成本还只有一张 128×18 的小图；
 * - **点一下展开**才出现 C1~C9，避免所有条目铺满屏幕还全是按钮；
 * - **热门度排序**（rank）而不是姓名字母序，让最常用的几个总在前面；
 * - **分批渲染**：每批 [PAGE] 条，底部「继续加载」。200 条一次性组合时每张卡都要
 *   现画灰阶带 + 曲线缩略图，首帧会明显卡顿，分批后进入页面只画 24 张。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuiltInPresetSection(ui: UiState, vm: MainViewModel) {
    val filtered = remember(ui.builtinCategory, ui.builtinQuery) {
        val base = if (ui.builtinQuery.isBlank()) {
            BuiltInPresets.byCategory(ui.builtinCategory)
        } else {
            BuiltInPresets.search(ui.builtinQuery)
        }
        base
    }
    // 换了分类/搜索词就从头开始显示，避免「上次翻到第 8 页」串到新结果上
    var shown by remember(ui.builtinCategory, ui.builtinQuery) { mutableIntStateOf(PAGE) }

    SectionTitle("内置预设（${BuiltInPresets.ALL.size}）") {
        Chip("按热门度排序", ZColors.Info)
    }

    // 搜索
    OutlinedTextField(
        value = ui.builtinQuery,
        onValueChange = { vm.setBuiltinQuery(it) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索名称 / 场景 / 说明", fontSize = 11.sp, color = ZColors.TextTertiary) },
        leadingIcon = {
            Icon(Icons.Filled.Search, null, tint = ZColors.TextTertiary, modifier = Modifier.size(15.dp))
        },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 12.sp, color = ZColors.TextPrimary, fontFamily = FontFamily.Monospace,
        ),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ZColors.NikonYellow,
            unfocusedBorderColor = ZColors.Outline,
            focusedContainerColor = ZColors.Bg,
            unfocusedContainerColor = ZColors.Bg,
            cursorColor = ZColors.NikonYellow,
        ),
    )

    Spacer(Modifier.height(8.dp))

    // 分类 chips
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            CategoryChip(
                label = "全部 ${BuiltInPresets.ALL.size}",
                selected = ui.builtinCategory == null,
                onClick = { vm.setBuiltinCategory(null) },
            )
        }
        items(BuiltInPresets.CATEGORIES) { (id, label) ->
            CategoryChip(
                label = "$label ${BuiltInPresets.byCategory(id).size}",
                selected = ui.builtinCategory == id,
                onClick = { vm.setBuiltinCategory(id) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    if (filtered.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ZColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("没有匹配的预设", color = ZColors.TextTertiary, fontSize = 12.sp)
        }
    } else {
        filtered.take(shown).forEach { preset ->
            BuiltInCard(
                preset = preset,
                expanded = ui.builtinPreviewId == preset.id,
                connected = ui.isConnected,
                writing = ui.writing,
                onToggle = { vm.setBuiltinPreview(preset.id) },
                onLoad = { vm.loadBuiltIn(preset) },
                onSave = { vm.saveBuiltInToLibrary(preset) },
                onWrite = { slot -> vm.writeBuiltInToSlot(preset, slot) },
            )
            Spacer(Modifier.height(6.dp))
        }
        if (shown < filtered.size) {
            Spacer(Modifier.height(4.dp))
            ZOutlineButton(
                text = "继续加载（还有 ${filtered.size - shown} 条）",
                onClick = { shown = (shown + PAGE).coerceAtMost(filtered.size) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (filtered.size > PAGE) {
            Spacer(Modifier.height(4.dp))
            Text(
                "已全部显示 ${filtered.size} 条",
                color = ZColors.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 每批渲染多少条。24 条刚好一屏半，滚动一下就触底，不会让人等。 */
private const val PAGE = 24

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ZColors.NikonYellow else ZColors.SurfaceHigh)
            .border(
                1.dp,
                if (selected) ZColors.NikonYellow else ZColors.Outline,
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF121212) else ZColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuiltInCard(
    preset: BuiltInPreset,
    expanded: Boolean,
    connected: Boolean,
    writing: Boolean,
    onToggle: () -> Unit,
    onLoad: () -> Unit,
    onSave: () -> Unit,
    onWrite: (Int) -> Unit,
) {
    // 灰阶预览带：把这个配方的 LUT 直接作用在 0..255 上，肉眼就能看出对比和黑位走向
    val strip = remember(preset.id) { buildToneStrip(preset) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ZColors.Surface)
            .border(
                1.dp,
                if (expanded) ZColors.NikonYellow.copy(alpha = 0.55f) else ZColors.Outline,
                RoundedCornerShape(9.dp),
            )
            .clickable { onToggle() }
            .padding(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CurveThumbnail(
                lut = preset.recipe.lut,
                modifier = Modifier.size(width = 42.dp, height = 42.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preset.displayName,
                        color = ZColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Chip(preset.categoryLabel, ZColors.YellowDim)
                    if (preset.source == "derived") {
                        Spacer(Modifier.width(4.dp))
                        Chip("派生", ZColors.TextTertiary)
                    }
                    if (preset.rank <= 10) {
                        Spacer(Modifier.width(4.dp))
                        Chip("#${preset.rank}", ZColors.Success)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    preset.cameraName,
                    color = ZColors.TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            // 灰阶预览带
            if (strip != null) {
                Image(
                    bitmap = strip.asImageBitmap(),
                    contentDescription = "灰阶预览",
                    modifier = Modifier
                        .size(width = 54.dp, height = 30.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }

        Spacer(Modifier.height(5.dp))
        Text(
            preset.note,
            color = ZColors.TextSecondary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        if (expanded) {
            Spacer(Modifier.height(7.dp))
            Text(
                "锐化 %+.1f · 清晰 %+.1f · 对比 %+.1f · 亮度 %+.1f · 饱和 %+.1f · 色相 %+.1f"
                    .format(
                        preset.recipe.sharpening, preset.recipe.clarity,
                        preset.recipe.contrast, preset.recipe.brightness,
                        preset.recipe.saturation, preset.recipe.hue,
                    ),
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp,
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZOutlineButton(
                    text = "载入编辑",
                    onClick = onLoad,
                    modifier = Modifier.weight(1f),
                )
                ZOutlineButton(
                    text = "收藏到预设库",
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(7.dp))
            Text(
                if (connected) "直接刷入：" else "未连接 Z30（先连上即可刷入）",
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (1..9).forEach { i ->
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (connected) ZColors.NikonYellow.copy(alpha = 0.16f)
                                else ZColors.SurfaceHigh
                            )
                            .border(1.dp, ZColors.Outline, RoundedCornerShape(6.dp))
                            .clickable(enabled = connected && !writing) { onWrite(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "C$i",
                            color = if (connected) ZColors.NikonYellow else ZColors.TextTertiary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(3.dp))
        Text(
            if (expanded) "▲ 收起" else "▼ 预览 / 刷入",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 生成一条「灰阶预览带」：把 LUT 作用在横向 0→255 渐变上。
 *
 * 用 128×18 这么小的尺寸是有意的——每批 24 张卡片同时组合，大图会明显拖慢首帧。
 */
private fun buildToneStrip(preset: BuiltInPreset): Bitmap? = runCatching {
    val w = 128
    val h = 18
    val lut = preset.recipe.lut
    val satF = 1f + preset.recipe.saturation * 0.28f
    val px = IntArray(w * h)
    for (x in 0 until w) {
        val base = x * 255 / (w - 1)
        var v = if (lut.size == 256) lut[base.coerceIn(0, 255)] else base
        // 简化版：只叠饱和（灰阶上饱和不改变亮度，但会带出轻微色偏感），保证与相机观感接近
        val lum = v
        val r = (lum + (lum - lum) * satF).toInt().coerceIn(0, 255)
        val g = r
        val b = r
        val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        for (y in 0 until h) px[y * w + x] = color
    }
    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}.getOrNull()
