package com.zcloud.ai.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zcloud.ai.ptp.HexLogEntry
import com.zcloud.ai.ptp.NikonPtpDriver
import com.zcloud.ai.ptp.Ptp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UsbConsoleScreen(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (UsbDevice) -> Unit,
) {
    val logs by vm.hexLog.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // 新日志自动滚到底
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {

        // ---------- 连接状态 ----------
        ConnectionCard(ui = ui, vm = vm, onRequestUsbPermission = onRequestUsbPermission)

        // ---------- 实时取景 ----------
        LiveViewCard(ui = ui, vm = vm)

        // ---------- 槽位表 ----------
        SectionTitle("机身 C-1 ~ C-9 槽位实况") {
            IconButton(onClick = { vm.refreshSlots() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, "刷新", tint = ZColors.NikonYellow, modifier = Modifier.size(17.dp))
            }
        }
        SlotTable(ui = ui, vm = vm)

        Spacer(Modifier.height(6.dp))

        // ---------- 操作码参数形态 ----------
        SectionTitle("0x90CC/0x90CD 参数形态")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpcodeChip(
                text = "(slot, 0) 双参",
                active = !ui.useAltOpcode,
                onClick = { vm.setOpcodeProfile(false) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(7.dp))
            OpcodeChip(
                text = "(slot) 单参",
                active = ui.useAltOpcode,
                onClick = { vm.setOpcodeProfile(true) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(ZColors.SurfaceHigh)
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(7.dp))
                    .clickable { vm.probeProfile() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, null, tint = ZColors.NikonYellow, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("自动探测", color = ZColors.NikonYellow, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 一键诊断 ----------
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZOutlineButton(
                text = "一键诊断",
                onClick = { vm.runDiagnose() },
                modifier = Modifier.weight(1f),
                enabled = ui.isConnected,
            )
            ZOutlineButton(
                text = "切 PC 控制模式",
                onClick = { vm.enterPcControlMode() },
                modifier = Modifier.weight(1f),
                enabled = ui.isConnected,
            )
        }
        if (ui.writeMsg.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                ui.writeMsg,
                color = ZColors.TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
            )
        }

        Spacer(Modifier.height(10.dp))

        SectionTitle("PTP 收发 Hex 流（${logs.size}）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(logs.dumpTextSafe())) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, "复制", tint = ZColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { vm.clearLog() }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.DeleteSweep, "清空", tint = ZColors.Danger, modifier = Modifier.size(17.dp))
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZColors.Surface)
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "等待连接 Z30…\n连接后这里会实时打印 Command / Data / Response 三段容器",
                    color = ZColors.TextTertiary, fontSize = 11.sp, lineHeight = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0B0B0B))
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(logs) { entry -> HexLogRow(entry) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LiveViewCard(ui: UiState, vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "实时取景 Live View",
                color = if (ui.liveViewActive) ZColors.NikonYellow else ZColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    ui.liveViewError.isNotBlank() -> ui.liveViewError
                    ui.liveViewActive -> "已收 ${ui.liveViewFrames} 帧"
                    else -> "0x9201 / 0x9203 / 0x9202"
                },
                color = if (ui.liveViewError.isNotBlank()) ZColors.Danger else ZColors.TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(7.dp))

        val frame = ui.liveViewBitmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF0B0B0B)),
            contentAlignment = Alignment.Center,
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = "实时取景",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    if (ui.liveViewActive) "等待首帧…" else "未开启取景",
                    color = ZColors.TextTertiary, fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ZPrimaryButton(
            text = if (ui.liveViewActive) "关闭取景" else "开启取景",
            onClick = { vm.toggleLiveView() },
            enabled = ui.isConnected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConnectionCard(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (UsbDevice) -> Unit,
) {
    var devices by remember(ui.usbState) { mutableStateOf(vm.nikonDevices()) }

    val stateText = when (ui.usbState) {
        NikonPtpDriver.State.SESSION_OPEN -> "Z30 已连接 · 会话已建立"
        NikonPtpDriver.State.CONNECTED -> "已握手，未开会话"
        NikonPtpDriver.State.PERMISSION_PENDING -> "等待 USB 授权…"
        NikonPtpDriver.State.ERROR -> "连接异常"
        else -> "未连接"
    }
    val stateColor = when (ui.usbState) {
        NikonPtpDriver.State.SESSION_OPEN -> ZColors.Success
        NikonPtpDriver.State.ERROR -> ZColors.Danger
        NikonPtpDriver.State.DISCONNECTED -> ZColors.TextTertiary
        else -> ZColors.NikonYellow
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface)
            .border(1.dp, if (ui.isConnected) ZColors.Success.copy(alpha = 0.5f) else ZColors.Outline, RoundedCornerShape(10.dp))
            .padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(stateColor))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stateText, color = stateColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (ui.deviceInfo.model.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "%s · VID 0x%04X PID 0x%04X · %s".format(
                            ui.deviceInfo.model,
                            ui.deviceInfo.vendorId,
                            ui.deviceInfo.productId,
                            ui.deviceInfo.serial.ifBlank { "NO-SN" },
                        ),
                        color = ZColors.TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "EP In 0x%02X / Out 0x%02X / Int 0x%02X · session=%d".format(
                            ui.deviceInfo.bulkInAddr,
                            ui.deviceInfo.bulkOutAddr,
                            ui.deviceInfo.interruptInAddr,
                            ui.deviceInfo.sessionId,
                        ),
                        color = ZColors.TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (ui.isConnected) {
                IconButton(onClick = { vm.disconnect() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.LinkOff, "断开", tint = ZColors.Danger, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = { devices = vm.scanUsbDevices() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, "重新枚举", tint = ZColors.TextSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }

        if (!ui.isConnected) {
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                // 关键分流：设备数为 0 说明手机作为 OTG 主机根本没看到任何外设，
                // 这时候再怎么调 PTP 都是白搭，先解决物理层。
                val total = vm.usbDeviceCount()
                Text(
                    if (total == 0)
                        "手机当前没有识别到任何 USB 外设（OTG 主机未建立）。\n" +
                            "这是物理层问题，不是协议问题，按顺序排查：\n" +
                            "① 拔掉电脑数据线 —— 同一个 USB-C 口不可能既做 adb 又做 OTG 主机；\n" +
                            "② 相机「设定」→「USB」→ 选 MTP/PTP，不能选「相机控制」或「充电」；\n" +
                            "③ 线材要支持数据传输（纯充电线只能供电）；\n" +
                            "④ 换个 OTG 转接头，部分转接头不供电。\n" +
                            "接好后相机会自己弹授权框，允许即可。"
                    else
                        "已枚举到 $total 个 USB 设备，但没有 Nikon（VID 1200）。\n" +
                            "说明 OTG 通了，但相机没以 PTP 设备身份出现：\n" +
                            "① 相机「设定」→「USB」→ 必须选 MTP/PTP（选「相机控制」会换成别的 VID）；\n" +
                            "② 检查是否有别的 App 占用了这个 USB 设备；\n" +
                            "③ 点右上角刷新重试。",
                    color = ZColors.TextTertiary, fontSize = 10.sp, lineHeight = 15.sp,
                )
            } else {
                devices.forEach { dev ->
                    DeviceRow(
                        dev = dev,
                        granted = vm.hasPermission(dev),
                        onConnect = {
                            if (vm.hasPermission(dev)) vm.connectDevice(dev) else onRequestUsbPermission(dev)
                        },
                    )
                    Spacer(Modifier.height(5.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                ZOutlineButton("重新扫描", onClick = { devices = vm.scanUsbDevices() }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                ZOutlineButton(
                    "导出快照",
                    onClick = { vm.exportLogToFile() },
                    modifier = Modifier.weight(1f),
                    enabled = true,
                )
                Spacer(Modifier.width(6.dp))
                ZOutlineButton(
                    "分享日志",
                    onClick = { vm.shareLatestLog() },
                    modifier = Modifier.weight(1f),
                    enabled = true,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "「分享日志」是给不接电脑的情况用的：拔掉数据线、只接 Z30 跑一轮，" +
                    "再回来点它，日志会连整个过程一起发出来。",
                color = ZColors.TextTertiary, fontSize = 9.sp, lineHeight = 13.sp,
            )
        }
    }
}

@Composable
private fun DeviceRow(dev: UsbDevice, granted: Boolean, onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ZColors.SurfaceHigh)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
            .clickable { onConnect() }
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Memory, null, tint = ZColors.NikonYellow, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                dev.productName ?: dev.deviceName,
                color = ZColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "VID 0x%04X · PID 0x%04X · if=%d".format(dev.vendorId, dev.productId, dev.interfaceCount),
                color = ZColors.TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (granted) ZColors.Success.copy(alpha = 0.16f) else ZColors.NikonYellow.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (granted) Icons.Filled.Link else Icons.Filled.Download,
                null,
                tint = if (granted) ZColors.Success else ZColors.NikonYellow,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                if (granted) "连接" else "授权",
                color = if (granted) ZColors.Success else ZColors.NikonYellow,
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SlotTable(ui: UiState, vm: MainViewModel) {
    val cols = 3
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ui.slots.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { slot ->
                    val isTarget = slot.index == ui.targetSlotIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isTarget) ZColors.NikonYellow.copy(alpha = 0.12f) else ZColors.SurfaceHigh)
                            .border(
                                1.dp,
                                if (isTarget) ZColors.NikonYellow.copy(alpha = 0.6f) else ZColors.Outline,
                                RoundedCornerShape(7.dp),
                            )
                            .clickable(enabled = ui.isConnected) { vm.dumpSlotRaw(slot.index) }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "C-${slot.index}",
                                color = if (isTarget) ZColors.NikonYellow else ZColors.TextSecondary,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        when {
                                            !ui.isConnected -> ZColors.TextTertiary
                                            slot.loaded -> ZColors.Success
                                            else -> ZColors.Danger
                                        }
                                    )
                            )
                        }
                        Spacer(Modifier.height(1.dp))
                        Text(
                            if (!ui.isConnected) "—" else slot.name.take(11),
                            color = if (slot.loaded && ui.isConnected) ZColors.TextPrimary else ZColors.TextTertiary,
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        )
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (ui.isConnected) {
            Text(
                "点任意槽位 → 读回 84 字节原始载荷并打印 Hex（用于定位偏移）",
                color = ZColors.TextTertiary, fontSize = 8.sp,
            )
        }
    }
}

@Composable
private fun OpcodeChip(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) ZColors.NikonYellow.copy(alpha = 0.16f) else ZColors.SurfaceHigh)
            .border(1.dp, if (active) ZColors.NikonYellow else ZColors.Outline, RoundedCornerShape(7.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) ZColors.NikonYellow else ZColors.TextSecondary,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun HexLogRow(entry: HexLogEntry) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val color = when (entry.direction) {
        HexLogEntry.Direction.TX -> ZColors.NikonYellow
        HexLogEntry.Direction.RX -> ZColors.Info
        HexLogEntry.Direction.EVENT -> Color(0xFFC084FC)
        HexLogEntry.Direction.WARN -> Color(0xFFFB923C)
        HexLogEntry.Direction.ERROR -> ZColors.Danger
        HexLogEntry.Direction.INFO -> ZColors.TextSecondary
    }
    val tag = when (entry.direction) {
        HexLogEntry.Direction.TX -> "TX →"
        HexLogEntry.Direction.RX -> "RX ←"
        HexLogEntry.Direction.EVENT -> "EVT "
        HexLogEntry.Direction.WARN -> "WARN"
        HexLogEntry.Direction.ERROR -> "ERR "
        HexLogEntry.Direction.INFO -> "INFO"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (entry.direction == HexLogEntry.Direction.RX) Color(0xFF0E1216) else Color(0xFF101010))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${entry.seq}",
                color = ZColors.TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(5.dp))
            Text(tag, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(5.dp))
            Text(
                fmt.format(Date(entry.timestampMillis)),
                color = ZColors.TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            )
            if (entry.transactionId >= 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    "tx=${entry.transactionId}",
                    color = ZColors.TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            entry.title,
            color = color.copy(alpha = 0.92f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
        )
        if (entry.bytes.isNotEmpty()) {
            Text(
                entry.hexDump(),
                color = Color(0xFF8FA8B8), fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp,
            )
        }
        if (entry.note.isNotBlank()) {
            Text(
                entry.note,
                color = ZColors.TextTertiary, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp,
            )
        }
    }
}

private fun List<HexLogEntry>.dumpTextSafe(): String {
    val sb = StringBuilder()
    this.forEach { e ->
        sb.appendLine("[${e.seq}] ${e.direction} tx=${e.transactionId} ${e.title}")
        if (e.bytes.isNotEmpty()) sb.appendLine(e.hexDump())
        if (e.note.isNotBlank()) sb.appendLine("  // ${e.note}")
    }
    return sb.toString()
}
