package com.zcloud.ai.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcloud.ai.data.AppSettings

/**
 * 设置页（覆盖层，从顶栏齿轮进入）。
 *
 * 为什么把调试台挪进来：主界面五个 tab 里「调试台」对普通用户是纯噪声——
 * 它只在真机联调、协议对不上时才用得到，日常拍摄一次都不会点。
 * 占着一个底部 tab 的位置，等于把「预设库」「相册」这些真正常用的挤窄了。
 *
 * 现在底部四个 tab 全是日常功能（监看 / AI 调色 / 相册 / 预设库），
 * 调试台收进「高级」，需要时从设置进，用完整屏覆盖层打开，反而比 tab 里更宽敞。
 */
@Composable
fun SettingsScreen(ui: UiState, vm: MainViewModel) {
    val s = ui.settings
    Column(modifier = Modifier.fillMaxSize()) {

        // ---------- 顶部返回条 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF171717))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.openSettings(false) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = ZColors.NikonYellow,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                "设置",
                color = ZColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "v${com.zcloud.ai.BuildConfig.VERSION_NAME}",
                color = ZColors.TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            // ---------- 日常使用 ----------
            SectionTitle("日常使用")

            ChoiceRow(
                label = "相册读取张数",
                hint = "只读最近 N 张。全卡遍历要给每个文件发一次 GetObjectInfo，越多越慢。",
                options = AppSettings.GALLERY_LIMITS.map { it to AppSettings.galleryLimitLabel(it) },
                selected = s.galleryLimit,
                onSelect = { vm.setGalleryLimit(it) },
            )

            ChoiceRow(
                label = "取景帧率",
                hint = "取景是预览不是录制。想看清运动瞬间调高，想省电调低。",
                options = AppSettings.LIVE_VIEW_INTERVALS.map { it to AppSettings.liveViewLabel(it) },
                selected = s.liveViewIntervalMs,
                onSelect = { vm.setLiveViewInterval(it) },
            )

            SlotChoiceRow(
                selected = s.defaultTargetSlot,
                onSelect = { vm.setDefaultTargetSlot(it) },
            )

            ToggleRow(
                label = "监看页常亮",
                hint = "进监看页时不让屏幕自己灭，等光/拍延时的时候很有用。",
                checked = s.keepScreenOn,
                onChange = { vm.setKeepScreenOn(it) },
            )

            ToggleRow(
                label = "拍完自动存手机",
                hint = "开：每次拍摄完自动写进系统相册 Pictures/ZCloudAI。关：只留在 App 里，手动点保存。",
                checked = s.autoSaveCapture,
                onChange = { vm.setAutoSaveCapture(it) },
            )

            // ---------- 写入行为 ----------
            SectionTitle("刷入机身")
            ToggleRow(
                label = "写入前先备份原样模板",
                hint = "先从机身读回该槽位的原始 614 字节做模板，只覆盖名称与曲线。关掉更快，但万一写坏不好还原。",
                checked = s.backupBeforeWrite,
                onChange = { vm.setBackupBeforeWrite(it) },
            )
            ToggleRow(
                label = "写入后回读校验",
                hint = "写完立刻读回名称比对。慢一次往返，但能当场发现「以为写进去了其实没有」。",
                checked = s.verifyAfterWrite,
                onChange = { vm.setVerifyAfterWrite(it) },
            )

            // ---------- 日志 ----------
            SectionTitle("日志")
            ToggleRow(
                label = "持续写入日志文件",
                hint = "存在 Android/data/com.zcloud.ai/files/logs/。手机只有一个 USB-C 口，接电脑就没法接相机，所以必须 App 自己留证据。",
                checked = s.logToFile,
                onChange = { vm.setLogToFile(it) },
            )
            ToggleRow(
                label = "日志包含 Hex 字节",
                hint = "关掉后日志只剩一行标题。一帧取景就有 20KB，dump 出来是几百行——之前日志动辄几 MB 主要就是这个。",
                checked = s.hexInLog,
                onChange = { vm.setHexInLog(it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZOutlineButton(
                    text = "导出日志快照",
                    onClick = { vm.exportLogToFile() },
                    modifier = Modifier.weight(1f),
                )
                ZOutlineButton(
                    text = "分享日志",
                    onClick = { vm.shareLatestLog() },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "「分享日志」是给不接电脑的情况用的：拔掉数据线、只接 Z30 跑一轮，再回来点它。",
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )

            // ---------- 高级 ----------
            SectionTitle("高级")
            NavRow(
                label = "USB 调试台",
                hint = "连接状态、C1~C9 槽位实况、PTP 指令 Hex 日志。协议对不上时才需要。",
                onClick = { vm.openSettings(false); vm.openConsole(true) },
            )
            NavRow(
                label = "恢复默认设置",
                hint = "只重置本页选项，不会删掉预设库和 API Key。",
                onClick = { vm.resetSettings() },
            )

            // ---------- 关于 ----------
            SectionTitle("关于")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(ZColors.Surface)
                    .padding(12.dp),
            ) {
                Column {
                    Text(
                        "Z-CLOUD AI",
                        color = ZColors.NikonYellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "尼康 Z30 · 本地 AI 影调伴侣\n" +
                            "USB OTG 直连 · PTP 0x90CC/0x90CD · 614 字节优化校准载荷\n" +
                            "全程本地计算，参考图不上传；AI 只收压缩后的特征与缩略图。",
                        color = ZColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 单行多选一（横向 chip）。 */
@Composable
private fun ChoiceRow(
    label: String,
    hint: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = ZColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            options.forEach { (value, text) ->
                val on = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) ZColors.NikonYellow else ZColors.SurfaceHigh)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text,
                        color = if (on) Color(0xFF121212) else ZColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(hint, color = ZColors.TextTertiary, fontSize = 9.sp, lineHeight = 13.sp)
    }
}

/** 默认目标槽位 C1~C9。 */
@Composable
private fun SlotChoiceRow(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("默认目标槽位", color = ZColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..9).forEach { i ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (on) ZColors.NikonYellow else ZColors.SurfaceHigh)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$i",
                        color = if (on) Color(0xFF121212) else ZColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "每次打开 App 时预选的槽位。刷入时仍可临时改。",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
    }
}

/** 开关行。 */
@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = ZColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(hint, color = ZColors.TextTertiary, fontSize = 9.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF121212),
                checkedTrackColor = ZColors.NikonYellow,
                uncheckedThumbColor = ZColors.TextTertiary,
                uncheckedTrackColor = ZColors.SurfaceHigher,
                uncheckedBorderColor = ZColors.Outline,
            ),
        )
    }
}

/** 可点击的导航行（进二级页 / 触发动作）。 */
@Composable
private fun NavRow(label: String, hint: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ZColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = ZColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(hint, color = ZColors.TextTertiary, fontSize = 9.sp, lineHeight = 13.sp)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = ZColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
    Spacer(Modifier.height(7.dp))
}
