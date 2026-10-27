package com.zcloud.ai.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcloud.ai.ai.LlmProvider
import com.zcloud.ai.ai.ProviderAccount
import com.zcloud.ai.data.SavedPreset
import com.zcloud.ai.ui.components.CurveThumbnail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetLibraryScreen(ui: UiState, vm: MainViewModel, onPickNcp: () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        // ---------- AI 供应商与 Key（可折叠：配完就收起，不占屏） ----------
        AiProviderSection(ui = ui, vm = vm)

        Spacer(Modifier.height(14.dp))

        // ---------- 配方快传 ----------
        ShareCodeSection(ui = ui, vm = vm)

        Spacer(Modifier.height(14.dp))

        // ---------- 内置 200 套预设（分类 / 排序 / 预览 / 快速刷入） ----------
        BuiltInPresetSection(ui = ui, vm = vm)

        Spacer(Modifier.height(14.dp))

        // ---------- 预设库 ----------
        SectionTitle("本地预设库（${ui.presets.size}）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickNcp, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.FileOpen, "导入NCP", tint = ZColors.NikonYellow, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = { vm.saveCurrentPreset() }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Check, "保存当前", tint = ZColors.Success, modifier = Modifier.size(17.dp))
                }
            }
        }

        if (ui.presets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZColors.Surface)
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("预设库为空", color = ZColors.TextTertiary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在「AI 炼丹炉」生成后点保存，或导入 .NCP 文件",
                        color = ZColors.TextTertiary, fontSize = 10.sp,
                    )
                }
            }
        } else {
            ui.presets.forEach { preset ->
                PresetCard(
                    preset = preset,
                    connected = ui.isConnected,
                    onLoad = { vm.loadPreset(preset) },
                    onDelete = { vm.deletePreset(preset.id) },
                    onWrite = { slot -> vm.writePresetToSlot(preset, slot) },
                )
                Spacer(Modifier.height(7.dp))
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------- 批量刷入 ----------
        SectionTitle("批量刷入")
        var batchTargets by remember { mutableStateOf(setOf(1, 2, 3)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..9).forEach { i ->
                val on = batchTargets.contains(i)
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (on) ZColors.NikonYellow else ZColors.SurfaceHigh)
                        .border(1.dp, if (on) ZColors.NikonYellow else ZColors.Outline, RoundedCornerShape(7.dp))
                        .clickable {
                            batchTargets = if (on) batchTargets - i else batchTargets + i
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "C-$i",
                        color = if (on) Color(0xFF121212) else ZColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ZPrimaryButton(
            text = if (ui.isConnected) "把当前配方刷入所选 ${batchTargets.size} 个槽位" else "未连接 Z30",
            onClick = { vm.batchWrite(batchTargets.sorted(), ui.recipe) },
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.isConnected && batchTargets.isNotEmpty(),
            loading = ui.writing,
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ProviderCard(
    acc: ProviderAccount,
    active: Boolean,
    onSelect: () -> Unit,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var keyText by remember(acc.apiKey) { mutableStateOf(acc.apiKey) }
    var modelText by remember(acc.modelOverride) { mutableStateOf(acc.modelOverride) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ZColors.SurfaceHigher else ZColors.Surface)
            .border(
                1.dp,
                if (active) ZColors.NikonYellow.copy(alpha = 0.7f) else ZColors.Outline,
                RoundedCornerShape(10.dp),
            )
            .padding(11.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                !acc.enabled -> ZColors.TextTertiary
                                acc.apiKey.isBlank() -> ZColors.Danger
                                active -> ZColors.Success
                                else -> ZColors.TextSecondary
                            }
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    acc.provider.displayName,
                    color = ZColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (acc.provider.freeTier) Chip("FREE", ZColors.Success)
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Chip("使用中", ZColors.NikonYellow)
                } else {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(ZColors.NikonYellow.copy(alpha = 0.14f))
                            .clickable { onSelect() }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text("切换", color = ZColors.NikonYellow, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(7.dp))

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it; onKeyChange(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("API Key", fontSize = 11.sp, color = ZColors.TextTertiary) },
                leadingIcon = {
                    Icon(Icons.Filled.Key, null, tint = ZColors.TextTertiary, modifier = Modifier.size(15.dp))
                },
                trailingIcon = {
                    Box(modifier = Modifier.clickable { keyVisible = !keyVisible }.padding(8.dp)) {
                        Text(
                            if (keyVisible) "隐藏" else "显示",
                            color = ZColors.TextTertiary, fontSize = 9.sp,
                        )
                    }
                },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp, color = ZColors.TextPrimary, fontFamily = FontFamily.Monospace,
                ),
                shape = RoundedCornerShape(7.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ZColors.NikonYellow,
                    unfocusedBorderColor = ZColors.Outline,
                    focusedContainerColor = ZColors.Bg,
                    unfocusedContainerColor = ZColors.Bg,
                    cursorColor = ZColors.NikonYellow,
                ),
            )

            Spacer(Modifier.height(5.dp))

            OutlinedTextField(
                value = modelText,
                onValueChange = { modelText = it; onModelChange(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("模型（留空 = ${acc.provider.defaultModel}）", fontSize = 10.sp, color = ZColors.TextTertiary)
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp, color = ZColors.TextPrimary, fontFamily = FontFamily.Monospace,
                ),
                shape = RoundedCornerShape(7.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ZColors.NikonYellow,
                    unfocusedBorderColor = ZColors.Outline,
                    focusedContainerColor = ZColors.Bg,
                    unfocusedContainerColor = ZColors.Bg,
                    cursorColor = ZColors.NikonYellow,
                ),
            )

            Spacer(Modifier.height(7.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "入 ${acc.promptTokens} · 出 ${acc.completionTokens} · 共 ${acc.requestCount} 次 · 约 ¥%.4f"
                        .format(acc.estimatedCostCny),
                    color = ZColors.TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(ZColors.Surface)
                        .clickable { onReset() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Speed, null, tint = ZColors.TextTertiary, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("清零", color = ZColors.TextTertiary, fontSize = 9.sp)
                    }
                }
            }

            if (acc.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(acc.lastError.take(160), color = ZColors.Danger, fontSize = 9.sp, lineHeight = 13.sp)
            }
            if (acc.balanceNote.isNotBlank()) {
                Text(acc.balanceNote, color = ZColors.Info, fontSize = 9.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCard(
    preset: SavedPreset,
    connected: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onWrite: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(10.dp))
            .clickable { onLoad() }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CurveThumbnail(
                lut = preset.recipe.lut,
                modifier = Modifier.size(width = 46.dp, height = 46.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preset.name,
                    color = ZColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        text = preset.source,
                        color = when (preset.source) {
                            "AI" -> ZColors.NikonYellow
                            "NCP" -> ZColors.Info
                            else -> ZColors.TextSecondary
                        },
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${preset.recipe.baseProfile} · ${preset.recipe.curvePoints.size}pt · ${fmt.format(Date(preset.createdAt))}",
                        color = ZColors.TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (preset.lastSlot in 1..9) {
                Chip("C-${preset.lastSlot}", ZColors.Success)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = { onDelete() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Delete, "删除", tint = ZColors.TextTertiary, modifier = Modifier.size(15.dp))
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                "S%+.1f C%+.1f B%+.1f Sat%+.1f H%+.1f".format(
                    preset.recipe.sharpening, preset.recipe.contrast,
                    preset.recipe.brightness, preset.recipe.saturation, preset.recipe.hue,
                ),
                color = ZColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            if (preset.recipe.styleAnalysis.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    preset.recipe.styleAnalysis,
                    color = ZColors.TextTertiary, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text("刷入到：", color = ZColors.TextTertiary, fontSize = 9.sp)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (1..9).forEach { i ->
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (connected) ZColors.NikonYellow.copy(alpha = 0.16f) else ZColors.SurfaceHigh)
                            .border(1.dp, ZColors.Outline, RoundedCornerShape(6.dp))
                            .clickable(enabled = connected) { onWrite(i) },
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
            if (expanded) "▲ 收起" else "▼ 展开刷入选项",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}

/**
 * AI 供应商配置区——折叠式。
 *
 * 设计理由：Key 配好之后就是一次性动作，之后每次进「预设库」都要滑过三张卡片才能看到预设，
 * 属于典型的「配置占了主要空间」。所以默认只留一行摘要（当前供应商 + 有没有 Key + 累计 token），
 * 点一下才展开完整的 ProviderCard；配完再点收起，状态记在 UiState 里，切页回来也不会自己弹开。
 */
@Composable
private fun AiProviderSection(ui: UiState, vm: MainViewModel) {
    val active = ui.accounts.firstOrNull { it.providerId == ui.activeProviderId }
        ?: ui.accounts.firstOrNull()
    val hasKey = ui.accounts.any { it.apiKey.isNotBlank() }
    val totalTok = ui.accounts.sumOf { it.totalTokens }

    SectionTitle("AI 供应商 / API Key / 用量") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(
                text = "$totalTok tok",
                color = if (totalTok > 0) ZColors.Info else ZColors.TextTertiary,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (ui.aiPanelExpanded) ZColors.SurfaceHigh else ZColors.NikonYellow.copy(alpha = 0.16f))
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(6.dp))
                    .clickable { vm.toggleAiPanel() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    if (ui.aiPanelExpanded) "收起" else "⚙ 配置",
                    color = if (ui.aiPanelExpanded) ZColors.TextSecondary else ZColors.NikonYellow,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    // 折叠态摘要行
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
            .clickable { vm.toggleAiPanel() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (hasKey) ZColors.Success else ZColors.Danger)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    active?.provider?.displayName ?: "未配置供应商",
                    color = ZColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(if (hasKey) "Key 已配置" else "尚未填 Key（点右侧展开）")
                        active?.model?.let { append(" · $it") }
                        if (active != null && active.requestCount > 0) {
                            append(" · 已调 ${active.requestCount} 次")
                        }
                    },
                    color = ZColors.TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                if (ui.aiPanelExpanded) "▲" else "▼",
                color = ZColors.TextTertiary,
                fontSize = 10.sp,
            )
        }
    }

    if (ui.aiPanelExpanded) {
        Spacer(Modifier.height(8.dp))
        ui.accounts.forEach { acc ->
            ProviderCard(
                acc = acc,
                active = acc.providerId == ui.activeProviderId,
                onSelect = { vm.setActiveProvider(acc.providerId) },
                onKeyChange = { vm.setApiKey(acc.providerId, it) },
                onModelChange = { vm.setModelOverride(acc.providerId, it) },
                onReset = { vm.resetUsage(acc.providerId) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1A0E))
                .border(1.dp, ZColors.YellowDim.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column {
                Text("省 token 策略（已内置）", color = ZColors.NikonYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "① 参考图长边压到 384px / JPEG q62（约 25KB），视觉 token 约为原图 1/6\n" +
                        "② 本地先算黑位/高光/中灰/饱和度/色偏/16 桶直方图，压成一行文本随图发送，模型不用猜数值\n" +
                        "③ 强制纯 JSON 输出 + max_tokens=800，禁止长篇解释\n" +
                        "④ 无视觉能力的 DeepSeek 走纯文本路径，只发特征数字，token 更省",
                    color = ZColors.TextSecondary, fontSize = 10.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                ZOutlineButton(
                    text = "配置完成，收起",
                    onClick = { vm.toggleAiPanel() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 配方快传码：把配方压成一段可以贴到微信/评论区的文本。
 *
 * 为什么不做二维码：二维码要么加依赖、要么自己写几百行编码器；
 * 而「复制文本 → 粘贴 → 导入」已经覆盖了大部分分享场景，
 * 而且文本还能直接进群公告、评论区、Issue，比截图可检索得多。
 */
@Composable
private fun ShareCodeSection(ui: UiState, vm: MainViewModel) {
    SectionTitle("配方快传码") {
        Chip(
            text = if (ui.shareCode.isBlank()) "未生成" else "${ui.shareCode.length} 字符",
            color = ZColors.YellowDim,
        )
    }
    Text(
        "把当前配方压成一段文本，对方粘贴即可还原（含 256 点 LUT）。",
        color = ZColors.TextTertiary,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(6.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZPrimaryButton(
            text = "生成快传码",
            onClick = { vm.generateShareCode() },
            modifier = Modifier.weight(1f),
        )
        ZOutlineButton(
            text = "复制",
            onClick = {
                if (ui.shareCode.isNotBlank()) vm.copyShareCodeToClipboard()
            },
            enabled = ui.shareCode.isNotBlank(),
        )
        ZOutlineButton(
            text = "分享",
            onClick = { if (ui.shareCode.isNotBlank()) vm.shareShareCode() },
            enabled = ui.shareCode.isNotBlank(),
        )
    }

    if (ui.shareCode.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(ZColors.Surface)
                .padding(8.dp),
        ) {
            Text(
                ui.shareCode,
                color = ZColors.Mono,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ui.shareCodeInput,
        onValueChange = { vm.setShareCodeInput(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("粘贴别人的快传码", fontSize = 11.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = ZColors.TextPrimary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        ),
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ZColors.NikonYellow,
            unfocusedBorderColor = ZColors.Outline,
            focusedLabelColor = ZColors.NikonYellow,
            unfocusedLabelColor = ZColors.TextTertiary,
            cursorColor = ZColors.NikonYellow,
        ),
    )
    Spacer(Modifier.height(6.dp))
    ZOutlineButton(
        text = "解析并载入配方",
        onClick = { vm.applyShareCode() },
        modifier = Modifier.fillMaxWidth(),
        enabled = ui.shareCodeInput.isNotBlank(),
    )
}
