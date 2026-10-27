package com.zcloud.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zcloud.ai.ptp.NikonPtpDriver
import kotlinx.coroutines.launch

@Composable
fun AppRoot(
    vm: MainViewModel,
    onPickImage: () -> Unit,
    onPickNcp: () -> Unit,
    onRequestUsbPermission: (android.hardware.usb.UsbDevice) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.toast) {
        if (ui.toast.isNotEmpty()) {
            snackbar.showSnackbar(ui.toast)
            vm.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(ZColors.Bg)) {
        Scaffold(
            containerColor = ZColors.Bg,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = ZColors.SurfaceHigher,
                        contentColor = ZColors.NikonYellow,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            },
            bottomBar = {
                BottomBar(
                    selected = ui.selectedTab,
                    onSelect = { vm.selectTab(it) },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                TopStatusBar(ui = ui, vm = vm)
                when (ui.selectedTab) {
                    0 -> LiveMonitorScreen(ui = ui, vm = vm)
                    1 -> AiLabScreen(
                        ui = ui, vm = vm,
                        onPickImage = onPickImage,
                        onRequestUsbPermission = onRequestUsbPermission,
                    )
                    2 -> GalleryScreen(ui = ui, vm = vm)
                    else -> PresetLibraryScreen(ui = ui, vm = vm, onPickNcp = onPickNcp)
                }
            }

            // 设置与调试台都是整屏覆盖层：不占底部 tab，进来后视野反而比 tab 里更大
            if (ui.settingsOpen) {
                Box(modifier = Modifier.fillMaxSize().background(ZColors.Bg)) {
                    SettingsScreen(ui = ui, vm = vm)
                }
            }
            if (ui.consoleOpen) {
                Box(modifier = Modifier.fillMaxSize().background(ZColors.Bg)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    ) {
                        ConsoleHeader(onBack = { vm.openConsole(false) })
                        UsbConsoleScreen(ui = ui, vm = vm, onRequestUsbPermission = onRequestUsbPermission)
                    }
                }
            }
        }
    }
}

/** 调试台覆盖层的返回条。 */
@Composable
private fun ConsoleHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171717))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "返回",
                tint = ZColors.NikonYellow,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            "USB 调试台",
            color = ZColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "PTP · 0x90CC / 0x90CD",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun TopStatusBar(ui: UiState, vm: MainViewModel) {
    val connected = ui.isConnected
    val dotColor = when (ui.usbState) {
        NikonPtpDriver.State.SESSION_OPEN -> ZColors.Success
        NikonPtpDriver.State.ERROR -> ZColors.Danger
        NikonPtpDriver.State.DISCONNECTED -> ZColors.TextTertiary
        else -> ZColors.NikonYellow
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF171717), Color(0xFF101010))
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌标：Z 黄块
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ZColors.NikonYellow),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Z",
                color = Color(0xFF121212),
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                "Z-CLOUD AI",
                color = ZColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.4.sp,
            )
            Text(
                "NIKON Z30 · EXPEED 6",
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dotColor))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = when (ui.usbState) {
                        NikonPtpDriver.State.SESSION_OPEN ->
                            ui.deviceInfo.model.ifBlank { "Z 30" } + " 已连接"
                        NikonPtpDriver.State.CONNECTED -> "握手中"
                        NikonPtpDriver.State.PERMISSION_PENDING -> "等待授权"
                        NikonPtpDriver.State.ERROR -> "连接异常"
                        else -> "未连接"
                    },
                    color = if (connected) ZColors.Success else ZColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.batteryPercent in 0..100) {
                        Text(
                            "🔋${ui.batteryPercent}%",
                            color = if (ui.batteryPercent <= 20) ZColors.Danger else ZColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        "C-${ui.targetSlotIndex} 目标槽位",
                        color = ZColors.YellowDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // 设置入口：调试台和常规选项都收在这里，底部 tab 只留日常功能
        androidx.compose.material3.IconButton(
            onClick = { vm.openSettings(true) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = ZColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class TabSpec(val title: String, val icon: ImageVector)

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        TabSpec("监看", Icons.Filled.Videocam),
        TabSpec("AI 调色", Icons.Filled.AutoAwesome),
        TabSpec("相册", Icons.Filled.PhotoLibrary),
        TabSpec("预设库", Icons.Filled.Folder),
    )
    NavigationBar(
        containerColor = Color(0xFF161616),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() * 0f),
    ) {
        tabs.forEachIndexed { i, tab ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(20.dp)) },
                label = {
                    Text(
                        tab.title,
                        fontSize = 10.sp,
                        fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF121212),
                    selectedTextColor = ZColors.NikonYellow,
                    unselectedIconColor = ZColors.TextTertiary,
                    unselectedTextColor = ZColors.TextTertiary,
                    indicatorColor = ZColors.NikonYellow,
                ),
            )
        }
    }
}

/** Nikon 黄色主按钮。 */
@Composable
fun ZPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = ZColors.NikonYellow,
            contentColor = Color(0xFF121212),
            disabledContainerColor = Color(0xFF3A3A3A),
            disabledContentColor = ZColors.TextTertiary,
        ),
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color(0xFF121212),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.4.sp)
    }
}

/** 描边次级按钮。 */
@Composable
fun ZOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ZColors.Outline),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = ZColors.TextPrimary,
        ),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 小标签气泡。 */
@Composable
fun Chip(text: String, color: Color = ZColors.NikonYellow, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 区块标题。 */
@Composable
fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 13.dp)
                    .background(ZColors.NikonYellow)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                color = ZColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
        trailing?.invoke()
    }
}
