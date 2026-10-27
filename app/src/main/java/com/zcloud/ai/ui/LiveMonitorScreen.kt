package com.zcloud.ai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zcloud.ai.ptp.Ptp

/**
 * Tab「监看」：实时取景 + 快门 + 曝光参数 + 机内照片快览。
 *
 * 交互设计原则——右手单手持机时拇指能到的地方才有按钮：
 *   取景画面全宽在上，快门/参数在下，参数用滑杆而不是键盘输入。
 */
@Composable
fun LiveMonitorScreen(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (android.hardware.usb.UsbDevice) -> Unit = {},
) {
    // 设置里打开「监看页常亮」时给窗口加 FLAG_KEEP_SCREEN_ON。
    // 用 DisposableEffect 而不是 Activity 里的全局标志：离开这个 tab 就自动撤销，
    // 不会出现「明明在看相册，屏幕却一直亮着」的情况。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(ui.settings.keepScreenOn) {
        val window = (ctx as? android.app.Activity)?.window
        if (window != null) {
            if (ui.settings.keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (!ui.isConnected) {
            ConnectBar(ui = ui, vm = vm, onRequestUsbPermission = onRequestUsbPermission)
            Spacer(Modifier.height(10.dp))
        }
        LiveViewStage(ui = ui, vm = vm)
        Spacer(Modifier.height(10.dp))
        ShutterRow(ui = ui, vm = vm)
        Spacer(Modifier.height(10.dp))

        if (ui.captureMsg.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZColors.Surface)
                    .padding(8.dp),
            ) {
                Text(
                    ui.captureMsg,
                    color = ZColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        ExposurePanel(ui = ui, vm = vm)
        Spacer(Modifier.height(12.dp))
        SectionTitle("刚拍的这一张", trailing = {
            // 机身快门按键拍的照片不走 CaptureToSdram（App 收不到 0x9207），
            // 只能主动去存储卡尾部取最新一张，所以给一个显式入口
            ZOutlineButton(
                text = if (ui.capturing) "取回中…" else "从机身取最新",
                onClick = { vm.loadNewestFromCamera() },
                enabled = ui.isConnected && !ui.capturing,
            )
        })
        LastCaptureCard(ui = ui, vm = vm)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LiveViewStage(ui: UiState, vm: MainViewModel) {    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        val frame = ui.liveViewBitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "实时取景",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        !ui.isConnected -> "未连接相机"
                        ui.liveViewError.isNotBlank() -> ui.liveViewError
                        ui.liveViewActive -> "等待首帧…"
                        else -> "点下方「开启取景」"
                    },
                    color = ZColors.TextTertiary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "0x9201 开启 / 0x9203 取帧 / 0x9202 关闭",
                    color = Color(0xFF3A3A3A),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // 左上角状态与帧计数
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ui.liveViewActive) ZColors.Danger else ZColors.TextTertiary)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (ui.liveViewActive) "LIVE ${ui.liveViewFrames}" else "OFF",
                color = if (ui.liveViewActive) ZColors.Danger else ZColors.TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }

        // 右上角：取景参数回显
        if (ui.isConnected) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "1/${shutterDenom(ui.shutterSeconds)}  F${fmtF(ui.aperture)}  ISO${ui.iso}",
                    color = ZColors.NikonYellow,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "EV${if (ui.exposureComp >= 0) "+" else ""}${"%.1f".format(ui.exposureComp)}  ${
                        programName(ui.exposureProgram)
                    }",
                    color = ZColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZPrimaryButton(
            text = if (ui.liveViewActive) "关闭取景" else "开启取景",
            onClick = { vm.toggleLiveView() },
            modifier = Modifier.weight(1f),
            enabled = ui.isConnected,
            loading = false,
        )
        ZOutlineButton(
            text = "AF",
            onClick = { vm.triggerAutoFocusNow() },
            modifier = Modifier.width(72.dp),
            enabled = ui.isConnected,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZOutlineButton(
            text = "近 −",
            onClick = { vm.moveFocus(-2) },
            modifier = Modifier.weight(1f),
            enabled = ui.isConnected,
        )
        ZOutlineButton(
            text = "远 +",
            onClick = { vm.moveFocus(2) },
            modifier = Modifier.weight(1f),
            enabled = ui.isConnected,
        )
    }
}

@Composable
private fun ShutterRow(ui: UiState, vm: MainViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 快门按钮：外圈黄环 + 内圈实心
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(50))
                .background(if (ui.capturing) ZColors.SurfaceHigher else ZColors.NikonYellow)
                .pointerInput(ui.isConnected, ui.capturing) {
                    detectTapGestures(onTap = { if (ui.isConnected && !ui.capturing) vm.capturePhoto() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ui.capturing) ZColors.SurfaceHigh else Color(0xFF121212)),
            )
            if (ui.capturing) {
                Text("…", color = ZColors.NikonYellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (ui.capturing) "拍摄中" else "快门",
                color = ZColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "0x9207 → 0xC101 → 0x1009",
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        ZOutlineButton(
            text = if (ui.movieRecording) "停止录像" else "录像",
            onClick = { vm.toggleMovieRecording() },
            enabled = ui.isConnected,
        )
    }
}

@Composable
private fun ExposurePanel(ui: UiState, vm: MainViewModel) {
    SectionTitle("曝光参数", trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZOutlineButton(text = "读回", onClick = { vm.loadShootingParams() }, enabled = ui.isConnected)
            ZOutlineButton(text = "下发", onClick = { vm.applyShootingParams() }, enabled = ui.isConnected && !ui.probingWrite)
            // 「属性」= 问机身 0x1014 描述符，看这条属性到底是不是只读；全程只读，随时可点
            ZOutlineButton(
                text = if (ui.probingWrite) "读取中…" else "属性",
                onClick = { vm.probePropWritable() },
                enabled = ui.isConnected && !ui.probingWrite,
            )
        }
    })

    ModeChips(ui = ui, vm = vm)

    // 机身当前拍摄模式下，哪些参数由相机自己接管——写进去只会拿到 AccessDenied，所以直接禁掉
    // 另外：机身自己通过 0x1014 描述符声明为「只读」的属性（Z30 上 0x500E/0x500D/0xD100 就是）
    // 无论什么档位都写不进去，滑杆一律禁掉，避免用户反复点「下发」反复看 AccessDenied
    val progReadOnly = Ptp.DPC_EXPOSURE_PROGRAM_MODE in ui.readOnlyProps
    val shutterReadOnly = Ptp.DPC_NIKON_EXPOSURE_TIME in ui.readOnlyProps &&
        Ptp.DPC_EXPOSURE_TIME in ui.readOnlyProps
    val apertureByCamera = ui.exposureProgram == 4          // S 档：光圈由机身配
    val shutterByCamera = !(ui.exposureProgram == 1 || ui.exposureProgram == 4) || shutterReadOnly
    val evByCamera = ui.exposureProgram == 1                // M 档：EV 无意义
    if (apertureByCamera || shutterByCamera || evByCamera || progReadOnly) {
        Text(
            buildString {
                if (progReadOnly) {
                    append("机身声明 0x500E 只读 —— 档位只能在 Z30 上转盘，界面上的 M/P/A/S 只是显示")
                    if (apertureByCamera || shutterByCamera || evByCamera) append("。\n")
                }
                if (apertureByCamera || shutterByCamera || evByCamera) {
                    append("当前 ")
                    append(
                        when (ui.exposureProgram) {
                            1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> "—"
                        },
                    )
                    append(" 档：")
                    val names = ArrayList<String>()
                    if (apertureByCamera) names.add("光圈")
                    if (shutterByCamera) names.add("快门")
                    if (evByCamera) names.add("曝光补偿")
                    append(names.joinToString("、"))
                    append(" 由机身决定，下发时会自动跳过")
                }
            },
            color = ZColors.TextTertiary,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }

    // 「属性」按钮读回来的可写性结论：直接摊在面板上，不用翻日志
    if (ui.probeLines.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .background(ZColors.SurfaceHigh, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (ui.writeMsg.isNotBlank()) {
                Text(
                    ui.writeMsg,
                    color = ZColors.NikonYellow,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            ui.probeLines.forEach { line ->
                Text(
                    line,
                    color = ZColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp,
                )
            }
        }
    }

    ParamRow(
        label = "光圈",
        value = "F${fmtF(ui.aperture)}",
        enabled = ui.isConnected && !apertureByCamera,
        slider = {
            Slider(
                value = fStopIndex(ui.aperture),
                onValueChange = { vm.setAperture(fStops[it.toInt().coerceIn(0, fStops.lastIndex)]) },
                valueRange = 0f..(fStops.size - 1).toFloat(),
                steps = fStops.size - 2,
                enabled = ui.isConnected && !apertureByCamera,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
    ParamRow(
        label = "快门",
        value = if (ui.shutterSeconds >= 1f) "${"%.1f".format(ui.shutterSeconds)}s"
        else "1/${shutterDenom(ui.shutterSeconds)}",
        enabled = ui.isConnected && !shutterByCamera,
        slider = {
            Slider(
                value = shutterIndex(ui.shutterSeconds).toFloat(),
                onValueChange = { vm.setShutterSeconds(shutterValues[it.toInt().coerceIn(0, shutterValues.lastIndex)]) },
                valueRange = 0f..(shutterValues.size - 1).toFloat(),
                steps = shutterValues.size - 2,
                enabled = ui.isConnected && !shutterByCamera,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
    ParamRow(
        label = "ISO",
        value = "${ui.iso}",
        enabled = ui.isConnected,
        slider = {
            Slider(
                value = isoIndex(ui.iso).toFloat(),
                onValueChange = { vm.setIso(isoValues[it.toInt().coerceIn(0, isoValues.lastIndex)]) },
                valueRange = 0f..(isoValues.size - 1).toFloat(),
                steps = isoValues.size - 2,
                enabled = ui.isConnected,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
    ParamRow(
        label = "曝光补偿",
        value = "${if (ui.exposureComp >= 0) "+" else ""}${"%.1f".format(ui.exposureComp)}",
        enabled = ui.isConnected && !evByCamera,
        slider = {
            Slider(
                value = ui.exposureComp,
                onValueChange = { vm.setExposureComp((Math.round(it * 3f) / 3f)) },
                valueRange = -5f..5f,
                enabled = ui.isConnected && !evByCamera,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
}

@Composable
private fun ModeChips(ui: UiState, vm: MainViewModel) {
    var expanded by remember { mutableStateOf(true) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(1 to "M", 2 to "P", 3 to "A", 4 to "S").forEach { (v, label) ->
            val sel = ui.exposureProgram == v
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) ZColors.NikonYellow else ZColors.SurfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .pointerInput(v, ui.isConnected) {
                        // 点档位就直接发到机身，不必再点「下发」——这是用户最容易误会的一步
                        detectTapGestures(onTap = { if (ui.isConnected) vm.applyExposureProgram(v) })
                    },
            ) {
                Text(
                    label,
                    color = if (sel) Color(0xFF121212) else ZColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ZColors.SurfaceHigh)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { expanded = !expanded }) },
        ) {
            Text(
                "PC ${picCtrlName(ui.picCtrlItem)}",
                color = ZColors.YellowDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    if (expanded) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(
                1 to "标准", 2 to "自然", 3 to "鲜艳", 4 to "单色",
            ).forEach { (v, label) ->
                val sel = ui.picCtrlItem == v
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel) ZColors.YellowDim.copy(alpha = 0.3f) else ZColors.Surface)
                        .padding(vertical = 5.dp)
                        .pointerInput(v, ui.isConnected) {
                            detectTapGestures(onTap = { if (ui.isConnected) vm.setPicCtrlItem(v) })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (sel) ZColors.NikonYellow else ZColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    enabled: Boolean,
    slider: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = ZColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(56.dp),
        )
        Box(modifier = Modifier.weight(1f)) { slider() }
        Text(
            value,
            color = if (enabled) ZColors.NikonYellow else ZColors.TextTertiary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(66.dp),
        )
    }
}

@Composable
private fun LastCaptureCard(ui: UiState, vm: MainViewModel) {
    val bmp = ui.lastCapture
    if (bmp == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ZColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有取回照片", color = ZColors.TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                // 关键区分：点 App 里的黄环快门才会走 0x9207；用机身按键拍的照片只能从存储卡取
                Text(
                    "点上面的黄环快门 → 走 0x9207 拍摄\n用机身按键拍的照片，点右上「从机身取最新」",
                    color = ZColors.TextTertiary.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        return
    }
    var full by remember(bmp) { mutableStateOf(false) }
    Column {
        // 大图：按原始宽高比铺满整宽，Fit 不裁切——要看细节，裁一刀就没意义了
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF000000))
                .pointerInput(bmp) { detectTapGestures(onTap = { full = true }) },
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "刚拍摄的照片",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                contentScale = ContentScale.Fit,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "${bmp.width}×${bmp.height} · 点开看细节",
                    color = ZColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZPrimaryButton(
                text = "存到手机相册",
                onClick = { vm.saveLastCaptureToPhone() },
                modifier = Modifier.weight(1f),
            )
            ZOutlineButton(
                text = "当作参考图",
                onClick = {
                    // 把刚拍的这张当参考图，直接进 AI 还原调色
                    vm.useLastCaptureAsReference()
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        ZOutlineButton(
            text = "全屏查看原图",
            onClick = { full = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (full) {
        FullScreenPhoto(bmp = bmp, onDismiss = { full = false })
    }
}

/**
 * 全屏看图：双指缩放 + 拖动 + 双击复位。
 *
 * 拍完要判断对焦、噪点、色彩，180dp 高的缩略图看不出任何东西，
 * 所以这里给一个真正的 1:1 观察窗口。
 */
@Composable
private fun FullScreenPhoto(bmp: android.graphics.Bitmap, onDismiss: () -> Unit) {
    var scale by remember(bmp) { mutableStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .pointerInput(bmp) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1.01f) 1f else 2.2f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        },
                        onTap = { /* 单击不退出，避免误触 */ },
                    )
                }
                .pointerInput(bmp) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (ns <= 1.001f) {
                            androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            offset + pan
                        }
                        scale = ns
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "拍摄原图",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${bmp.width}×${bmp.height}  ${"%.0f".format(scale * 100)}%",
                    color = ZColors.NikonYellow,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            ZOutlineButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            )
        }
    }
}

// ---------------- 数值表与格式化 ----------------

private val fStops = floatArrayOf(
    2.8f, 3.2f, 3.5f, 4.0f, 4.5f, 5.0f, 5.6f, 6.3f, 7.1f, 8.0f,
    9.0f, 10f, 11f, 13f, 14f, 16f, 18f, 20f, 22f, 25f, 29f, 32f,
)

private val shutterValues = floatArrayOf(
    1f / 4000, 1f / 2000, 1f / 1000, 1f / 500, 1f / 250, 1f / 125, 1f / 60,
    1f / 30, 1f / 15, 1f / 8, 1f / 4, 1f / 2, 1f, 2f, 4f, 8f, 15f, 30f,
)

private val isoValues = intArrayOf(
    100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
    1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000,
    10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200,
)

private fun fStopIndex(f: Float): Float {
    var best = 0
    var bestDiff = Float.MAX_VALUE
    fStops.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - f)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best.toFloat()
}

private fun shutterIndex(s: Float): Int {
    var best = 0
    var bestDiff = Float.MAX_VALUE
    shutterValues.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - s)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best
}

private fun isoIndex(iso: Int): Int {
    var best = 0
    var bestDiff = Int.MAX_VALUE
    isoValues.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - iso)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best
}

private fun shutterDenom(s: Float): Int = if (s <= 0f) 0 else Math.round(1f / s)

private fun fmtF(f: Float): String {
    val r = Math.round(f * 10f) / 10f
    return if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else "%.1f".format(r)
}

private fun programName(p: Int): String = when (p) {
    1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> "A"
}

private fun picCtrlName(v: Int): String = when (v) {
    1 -> "标准"; 2 -> "自然"; 3 -> "鲜艳"; 4 -> "单色"
    5 -> "人像"; 6 -> "风景"; 7 -> "平直"; 8 -> "自动"
    else -> "标准"
}

/**
 * 未连接时的连接条：直接在主页面就能连相机，不用先跳到调试台。
 *
 * 连接是监看的前置条件，把入口放在需要它的地方，比藏在第四个 tab 里合理。
 */
@Composable
private fun ConnectBar(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (android.hardware.usb.UsbDevice) -> Unit,
) {
    var devices by remember { mutableStateOf(vm.scanUsbDevices()) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C1A0E))
            .padding(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "连接 Z30",
                    color = ZColors.NikonYellow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (ui.usbState) {
                        com.zcloud.ai.ptp.NikonPtpDriver.State.PERMISSION_PENDING -> "等待授权…"
                        com.zcloud.ai.ptp.NikonPtpDriver.State.ERROR -> "上次连接异常"
                        else -> "未连接"
                    },
                    color = ZColors.TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                ZOutlineButton(
                    text = "重新扫描",
                    onClick = { devices = vm.scanUsbDevices() },
                )
            }
            Spacer(Modifier.height(6.dp))
            if (devices.isEmpty()) {
                Text(
                    "未枚举到 Nikon 设备（VID 0x04B0）。\n" +
                        "检查：① 相机 USB 模式设为 MTP/PTP，不是「相机控制」；\n" +
                        "② 线材支持数据传输；③ 手机 USB 设置里 OTG 已开。",
                    color = ZColors.TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
            } else {
                devices.forEach { dev ->
                    val granted = vm.hasPermission(dev)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "%s %s".format(
                                    dev.productName ?: "Nikon",
                                    "PID 0x%04X".format(dev.productId),
                                ),
                                color = ZColors.TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                if (granted) "已授权" else "未授权",
                                color = if (granted) ZColors.Success else ZColors.TextTertiary,
                                fontSize = 9.sp,
                            )
                        }
                        ZPrimaryButton(
                            text = "连接",
                            onClick = {
                                if (granted) vm.connectDevice(dev) else onRequestUsbPermission(dev)
                            },
                        )
                    }
                }
            }
        }
    }
}
