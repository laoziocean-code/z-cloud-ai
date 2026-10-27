package com.zcloud.ai.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.zcloud.ai.core.Recipe
import com.zcloud.ai.ui.components.CurveEditorCanvas
import com.zcloud.ai.ui.components.CurveThumbnail
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLabScreen(
    ui: UiState,
    vm: MainViewModel,
    onPickImage: () -> Unit,
    onRequestUsbPermission: (UsbDevice) -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        // ---------- 参考图 + 预览对比 ----------
        SectionTitle("参考图 / 实时预览") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickImage, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Image, "选图", tint = ZColors.NikonYellow, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { vm.renderPreview() }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Refresh, "重渲染", tint = ZColors.TextSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }

        ComparePreview(ui = ui, vm = vm, onPickImage = onPickImage)

        Spacer(Modifier.height(10.dp))

        // ---------- AI 分析 ----------
        SectionTitle("AI 影调分析") {
            val acc = ui.activeAccount
            Chip(
                text = acc.provider.displayName.substringBefore("（"),
                color = if (acc.apiKey.isNotBlank()) ZColors.Success else ZColors.Danger,
            )
        }

        OutlinedTextField(
            value = ui.userHint,
            onValueChange = { vm.setUserHint(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("额外要求（可留空）：如「更电影感，暗部抬起」「压一点高光」", fontSize = 11.sp, color = ZColors.TextTertiary)
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = ZColors.TextPrimary),
            maxLines = 2,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ZColors.NikonYellow,
                unfocusedBorderColor = ZColors.Outline,
                focusedContainerColor = ZColors.SurfaceHigh,
                unfocusedContainerColor = ZColors.Surface,
                cursorColor = ZColors.NikonYellow,
            ),
        )

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZPrimaryButton(
                text = if (ui.analyzing) "分析中…" else "AI 逆向生成配方",
                onClick = { vm.analyzeWithAi() },
                modifier = Modifier.weight(1f),
                loading = ui.analyzing,
            )
            ZOutlineButton(
                text = "保存",
                onClick = { vm.saveCurrentPreset() },
                modifier = Modifier.width(78.dp),
            )
        }

        if (ui.analyzingMsg.isNotEmpty() || ui.lastAnalysis.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZColors.SurfaceHigh)
                    .padding(9.dp)
            ) {
                Text(
                    text = ui.analyzingMsg.ifEmpty { ui.lastAnalysis },
                    color = if (ui.analyzing) ZColors.NikonYellow else ZColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- 曲线 ----------
        SectionTitle("色调曲线 · 256-LUT") {
            Chip(
                text = if (ui.recipe.useCustomCurve) "CUSTOM" else "LINEAR",
                color = if (ui.recipe.useCustomCurve) ZColors.NikonYellow else ZColors.TextTertiary,
            )
        }
        CurveEditorCanvas(
            lut = ui.recipe.lut,
            points = ui.recipe.curvePoints,
            onPointsChange = { vm.updateCurvePoints(it) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        // ---------- 基础参数 ----------
        SectionTitle("基础参数（EXPEED 6 边界）")
        ParamSlider("锐化 Sharpening", ui.recipe.sharpening, -3f, 9f, vm, ParamKind.SHARP)
        ParamSlider("清晰度 Clarity", ui.recipe.clarity, -5f, 5f, vm, ParamKind.CLARITY)
        ParamSlider("对比度 Contrast", ui.recipe.contrast, -3f, 3f, vm, ParamKind.CONTRAST)
        ParamSlider("亮度 Brightness", ui.recipe.brightness, -2f, 2f, vm, ParamKind.BRIGHT)
        ParamSlider("饱和度 Saturation", ui.recipe.saturation, -3f, 3f, vm, ParamKind.SAT)
        ParamSlider("色相 Hue", ui.recipe.hue, -3f, 3f, vm, ParamKind.HUE)

        Spacer(Modifier.height(10.dp))

        // ---------- 配方名 ----------
        OutlinedTextField(
            value = ui.recipe.name,
            onValueChange = { vm.updateRecipeParam(name = it.take(19)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("预设名称（≤19 ASCII 字符）", fontSize = 10.sp, color = ZColors.TextTertiary) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp, color = ZColors.NikonYellow, fontFamily = FontFamily.Monospace,
            ),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ZColors.NikonYellow,
                unfocusedBorderColor = ZColors.Outline,
                focusedContainerColor = ZColors.SurfaceHigh,
                unfocusedContainerColor = ZColors.Surface,
                cursorColor = ZColors.NikonYellow,
            ),
        )

        Spacer(Modifier.height(12.dp))

        // ---------- 刷入 ----------
        SectionTitle("刷入 Z30") {
            if (ui.isConnected) Chip("READY", ZColors.Success) else Chip("NO DEVICE", ZColors.Danger)
        }

        SlotPicker(
            ui = ui,
            vm = vm,
            onRequestUsbPermission = onRequestUsbPermission,
        )

        Spacer(Modifier.height(10.dp))

        ZPrimaryButton(
            text = when {
                ui.writing -> "写入中…"
                !ui.isConnected -> "未连接 Z30 — 点此连接"
                else -> "刷入槽位 C-${ui.targetSlotIndex}"
            },
            onClick = {
                if (!ui.isConnected) {
                    val dev = vm.nikonDevices().firstOrNull()
                    if (dev != null) onRequestUsbPermission(dev) else vm.openConsole(true)
                } else {
                    vm.writeToCamera()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            loading = ui.writing,
        )

        // 写入被拒时的自救入口，按「信息量 / 破坏性」排序：
        //   1. 审计机身（纯只读：能力表 + 当前项 + 曲线通道 + 应用模式）
        //   2. 无损回写（原样读出来再原样写回去，内容零变化，但能判定 0x90CD 通不通）
        //   3. 全组合盲探（最后手段，80 种，最慢）—— v1.5.0 起载荷是 614 字节真机格式，用不上了
        if (ui.isConnected) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZOutlineButton(
                    text = if (ui.probingWrite) "进行中…" else "① 审计机身",
                    onClick = { vm.auditPicCtrl() },
                    modifier = Modifier.weight(1f),
                    enabled = !ui.probingWrite && !ui.writing,
                )
                ZOutlineButton(
                    text = "② 无损回写",
                    onClick = { vm.probeWriteBack() },
                    modifier = Modifier.weight(1f),
                    enabled = !ui.probingWrite && !ui.writing,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZOutlineButton(
                    text = "③ 全组合盲探(80)",
                    onClick = { vm.probeWriteMechanism() },
                    modifier = Modifier.weight(1f),
                    enabled = !ui.probingWrite && !ui.writing,
                )
                ZOutlineButton(
                    text = "扫描所有槽位",
                    onClick = { vm.scanAllPicCtrlItems() },
                    modifier = Modifier.weight(1f),
                    enabled = !ui.probingWrite && !ui.writing,
                )
            }
            if (ui.writeModeLabel.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已固化机制：${ui.writeModeLabel}",
                    color = ZColors.Success,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (ui.probeLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZColors.Bg)
                        .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    Column {
                        ui.probeLines.forEach { line ->
                            Text(
                                line,
                                color = ZColors.TextTertiary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp,
                            )
                        }
                    }
                }
            }
        }

        if (ui.writeMsg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                ui.writeMsg,
                color = if (ui.writeMsg.contains("失败")) ZColors.Danger else ZColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

private enum class ParamKind { SHARP, CLARITY, CONTRAST, BRIGHT, SAT, HUE }

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    vm: MainViewModel,
    kind: ParamKind,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ZColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                "%+.1f".format(value),
                color = if (kotlin.math.abs(value) > 0.01f) ZColors.NikonYellow else ZColors.TextTertiary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value,
            onValueChange = { v ->
                when (kind) {
                    ParamKind.SHARP -> vm.updateRecipeParam(sharpening = v)
                    ParamKind.CLARITY -> vm.updateRecipeParam(clarity = v)
                    ParamKind.CONTRAST -> vm.updateRecipeParam(contrast = v)
                    ParamKind.BRIGHT -> vm.updateRecipeParam(brightness = v)
                    ParamKind.SAT -> vm.updateRecipeParam(saturation = v)
                    ParamKind.HUE -> vm.updateRecipeParam(hue = v)
                }
            },
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth().height(26.dp),
            colors = SliderDefaults.colors(
                thumbColor = ZColors.NikonYellow,
                activeTrackColor = ZColors.NikonYellow,
                inactiveTrackColor = ZColors.Outline,
            ),
        )
    }
}

/** 参考图 / 效果图 上下对比，按住预览原图。 */
@Composable
private fun ComparePreview(ui: UiState, vm: MainViewModel, onPickImage: () -> Unit) {
    val ref = ui.referenceBitmap
    val preview = ui.previewBitmap
    var sliderPos by remember { mutableStateOf(0.5f) }   // 0..1 分割线位置（左右对比）

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A0A0A))
            .border(1.dp, ZColors.Outline, RoundedCornerShape(10.dp))
            .pointerInput(ref) {
                detectTapGestures(
                    onPress = {
                        vm.setShowOriginal(true)
                        tryAwaitRelease()
                        vm.setShowOriginal(false)
                    }
                )
            },
    ) {
        when {
            ref == null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Image, null,
                        tint = ZColors.TextTertiary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("点击右上角选图", color = ZColors.TextTertiary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    ZOutlineButton("从相册选择", onClick = onPickImage)
                }
            }
            ui.showOriginal || preview == null -> {
                Image(
                    bitmap = ref.asImageBitmap(),
                    contentDescription = "原图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Badge("原图", Modifier.align(Alignment.TopStart).padding(8.dp), ZColors.TextSecondary)
            }
            else -> {
                // 左右分割对比：左半原图，右半效果图
                Image(
                    bitmap = ref.asImageBitmap(),
                    contentDescription = "原图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 0.dp).let { RoundedCornerShape(0.dp) })
                ) {
                    // 用 alpha 叠加做整体对比（简单可靠，避免裁剪计算）
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "效果",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alpha = 1f,
                    )
                }
                Badge("AI 效果 · 按住看原图", Modifier.align(Alignment.BottomStart).padding(8.dp), ZColors.NikonYellow)
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, color: Color = ZColors.NikonYellow) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

/** C-1 ~ C-9 槽位选择条 + 机身当前名称。 */
@Composable
private fun SlotPicker(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (UsbDevice) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.slots.forEach { slot ->
                val selected = ui.targetSlotIndex == slot.index
                val bg = when {
                    selected -> ZColors.NikonYellow
                    slot.loaded -> ZColors.SurfaceHigh
                    else -> ZColors.Surface
                }
                val fg = when {
                    selected -> Color(0xFF121212)
                    slot.loaded -> ZColors.TextPrimary
                    else -> ZColors.TextTertiary
                }
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(
                            1.dp,
                            if (selected) ZColors.NikonYellow else ZColors.Outline,
                            RoundedCornerShape(8.dp),
                        )
                        .pointerInput(slot.index) {
                            detectTapGestures { vm.setTargetSlot(slot.index) }
                        }
                        .padding(vertical = 7.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "C-${slot.index}",
                        color = fg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        slot.name.take(9),
                        color = if (selected) Color(0xFF3A3300)
                        else if (slot.loaded) ZColors.TextTertiary
                        else ZColors.TextTertiary.copy(alpha = 0.55f),
                        fontSize = 8.sp,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (!ui.isConnected) {
            Spacer(Modifier.height(8.dp))
            val devices = vm.nikonDevices()
            if (devices.isEmpty()) {
                Text(
                    "未检测到尼康设备 · 请用 Type-C 线连接 Z30 并在相机端选择「MTP/PTP」",
                    color = ZColors.TextTertiary,
                    fontSize = 10.sp,
                )
            } else {
                devices.forEach { dev ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Usb, null, tint = ZColors.NikonYellow, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${dev.productName} (PID 0x%04X)".format(dev.productId),
                            color = ZColors.TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        ZOutlineButton("连接", onClick = { onRequestUsbPermission(dev) })
                    }
                }
            }
        }
    }
}
