package com.zcloud.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zcloud.ai.ZCloudApp
import com.zcloud.ai.ai.AiColorGradingRepository
import com.zcloud.ai.ai.ImageFeatureExtractor
import com.zcloud.ai.ai.LlmProvider
import com.zcloud.ai.ai.ProviderAccount
import com.zcloud.ai.core.CameraStorage
import com.zcloud.ai.core.BuiltInPreset
import com.zcloud.ai.core.BuiltInPresets
import com.zcloud.ai.core.CubicSplineLut
import com.zcloud.ai.core.ImagePipeline
import com.zcloud.ai.core.NcpParserAndBuilder
import com.zcloud.ai.core.Recipe
import com.zcloud.ai.core.RecipeShareCode
import com.zcloud.ai.data.SavedPreset
import com.zcloud.ai.ptp.HexLog
import com.zcloud.ai.ptp.HexLogEntry
import com.zcloud.ai.ptp.NikonPtpDriver
import com.zcloud.ai.ptp.Ptp
import com.zcloud.ai.ptp.PtpException
import com.zcloud.ai.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

/** 单个槽位的机身状态。 */
data class SlotState(
    val index: Int = 0,               // 1..9
    val ptpSlot: Int = 0,             // 0x00CB..
    val name: String = "--",
    val loaded: Boolean = false,
    val isTemplate: Boolean = false,  // 是否已作为写入模板缓存
    val rawSize: Int = 0,
    /** 机身明确回答「这个槽位没有预设」（AccessDenied / 36B 全零空壳）时为 true。 */
    val isEmpty: Boolean = false,
)

data class UiState(
    val selectedTab: Int = 0,
    val referenceBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val showOriginal: Boolean = false,
    val recipe: Recipe = Recipe(),
    val features: com.zcloud.ai.ai.ImageFeatures = com.zcloud.ai.ai.ImageFeatures.EMPTY,
    val analyzing: Boolean = false,
    val analyzingMsg: String = "",
    val lastAnalysis: String = "",
    val userHint: String = "",

    val usbState: NikonPtpDriver.State = NikonPtpDriver.State.DISCONNECTED,
    val deviceInfo: NikonPtpDriver.DeviceInfo = NikonPtpDriver.DeviceInfo(),
    val slots: List<SlotState> = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
    val targetSlotIndex: Int = 1,
    val templatePayload: ByteArray? = null,
    val writing: Boolean = false,
    val writeMsg: String = "",

    /** 实时取景（0x9201 开 / 0x9203 取帧 / 0x9202 关） */
    val liveViewActive: Boolean = false,
    val liveViewBitmap: Bitmap? = null,
    val liveViewFrames: Int = 0,
    val liveViewError: String = "",

    val accounts: List<ProviderAccount> = emptyList(),
    val activeProviderId: String = LlmProvider.GLM_FLASH.id,

    val presets: List<SavedPreset> = emptyList(),
    val toast: String = "",

    // ---------------- 监看 / 拍摄 ----------------
    val liveViewReady: Boolean = false,
    val capturing: Boolean = false,
    val captureMsg: String = "",
    /** 刚拍下的一张（机身 SDRAM 回传的 JPEG 解码结果），用于「拍完即看」。 */
    val lastCapture: Bitmap? = null,
    val lastCaptureHandle: Long = 0L,
    val movieRecording: Boolean = false,
    val liveViewAutoFocus: Boolean = false,

    // ---------------- 拍摄参数（0x1015 / 0x1016） ----------------
    val iso: Int = 100,
    val aperture: Float = 5.6f,
    val shutterSeconds: Float = 1f / 125f,
    val exposureComp: Float = 0f,
    val exposureProgram: Int = 3,   // 1=M 2=P 3=A 4=S
    val whiteBalance: Int = 0x0002,
    val picCtrlItem: Int = 1,       // 1=标准 2=自然 3=鲜艳 4=单色 5=人像 6=风景 7=平直 8=自动
    val paramsLoaded: Boolean = false,
    val batteryPercent: Int = -1,

    // ---------------- 相册（照片管理） ----------------
    val galleryVolumes: List<CameraStorage.Volume> = emptyList(),
    val galleryItems: List<CameraStorage.Item> = emptyList(),
    val galleryLoading: Boolean = false,
    val galleryProgress: Int = 0,
    val galleryFilter: Int = 0,     // 0=全部 1=照片 2=视频
    val thumbnails: Map<Long, ByteArray> = emptyMap(),
    val downloadingHandle: Long = 0L,
    val downloadMsg: String = "",
    /** 只取最近 N 张。全卡遍历太慢，实测按需取尾部 100 张能把等待从分钟级降到几秒。 */
    val galleryLimit: Int = 100,

    // ---------------- 内置预设库 ----------------
    /** null = 全部；否则是 BuiltInPresets.CAT_* 之一 */
    val builtinCategory: String? = null,
    val builtinQuery: String = "",
    /** 内置库界面当前预览的那一套（点卡片先看曲线和参数，再决定刷不刷） */
    val builtinPreviewId: String? = null,

    // ---------------- 设置可见性 ----------------
    /** AI 供应商配置区是否展开。配置完点「收起」，Key 就藏起来，避免被人看到。 */
    val aiPanelExpanded: Boolean = false,

    // ---------------- 配方快传 ----------------
    val shareCode: String = "",
    val shareCodeInput: String = "",

    /**
     * 优化校准操作码的参数形态。
     * false = 两参数 `(slot, 0)`，标准写法，Z30/EXPEED 6 正常路径；
     * true  = 单参数 `(slot)`，仅作兼容退化，个别老固件只认一个参数。
     */
    val useAltOpcode: Boolean = false,

    /** 是否正在跑写入机制矩阵探测。 */
    val probingWrite: Boolean = false,
    /** 探测过程的实时进度行（最多保留最近几条）。 */
    val probeLines: List<String> = emptyList(),
    /** 已固化的写入配方描述；空 = 还没探测成功。 */
    val writeModeLabel: String = "",

    /**
     * 机身自报「只读」的属性码集合（来自 0x1014 描述符第 5 字节）。
     *
     * 真机实测 Z30：`0x500E` 曝光程序、`0x500D` 标准快门、`0xD100` 尼康快门
     * 三条全是 `GetSet=只读`，只有 ISO / 光圈 / 曝光补偿 / 白平衡 / 优化校准项可写。
     * 只读的属性发 0x1016 必然回 `0x200F AccessDenied`，重试一万次也不会变，
     * 所以这里缓存下来，下发时直接跳过，UI 也按它禁掉滑杆。
     */
    val readOnlyProps: Set<Int> = emptySet(),

    // ---------------- 设置 ----------------
    /** 常规设置（DataStore 持久化）。 */
    val settings: com.zcloud.ai.data.AppSettings = com.zcloud.ai.data.AppSettings(),
    /** USB 调试台是否以覆盖层打开（入口挪到设置里了，主界面不再占一个 tab）。 */
    val consoleOpen: Boolean = false,
    /** 设置页是否以覆盖层打开（入口：右上角齿轮）。 */
    val settingsOpen: Boolean = false,
) {
    val activeAccount: ProviderAccount
        get() = accounts.firstOrNull { it.providerId == activeProviderId }
            ?: ProviderAccount(providerId = activeProviderId)

    val isConnected: Boolean
        get() = usbState == NikonPtpDriver.State.SESSION_OPEN
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val zapp = app as ZCloudApp
    private val driver = zapp.driver
    private val store = zapp.store

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val hexLog: StateFlow<List<HexLogEntry>> = HexLog.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var previewJob: Job? = null
    private var templateCache = mutableMapOf<Int, ByteArray>()

    /**
     * 刚拍完一张，相册列表就过时了。
     *
     * 不在这里直接 refreshGallery()——读一次列表要几秒，用户拍完只是想看这张。
     * 打标记，等下次进相册页时再刷（selectTab 里判断）。
     */
    private var lastCaptureDirty = false

    /** 默认目标槽位只在首次读到设置时应用一次，避免把用户临时改的槽位覆盖回去。 */
    private var defaultSlotApplied = false

    init {
        // 挂上驱动状态
        viewModelScope.launch {
            driver.state.collect { st ->
                _ui.value = _ui.value.copy(usbState = st)
                if (st == NikonPtpDriver.State.SESSION_OPEN) {
                    refreshSlots()
                }
            }
        }
        viewModelScope.launch {
            driver.info.collect { info -> _ui.value = _ui.value.copy(deviceInfo = info) }
        }
        viewModelScope.launch {
            store.accounts.collect { list -> _ui.value = _ui.value.copy(accounts = list) }
        }
        viewModelScope.launch {
            store.activeProviderId.collect { id -> _ui.value = _ui.value.copy(activeProviderId = id) }
        }
        viewModelScope.launch {
            store.presets.collect { list -> _ui.value = _ui.value.copy(presets = list) }
        }
        viewModelScope.launch {
            store.settings.collect { s ->
                // galleryLimit 在 UiState 里也留一份，GalleryScreen 直接读它，避免改一堆调用点
                _ui.value = _ui.value.copy(settings = s, galleryLimit = s.galleryLimit)
                // 默认目标槽位只在第一次拿到设置时应用一次，
                // 之后用户在界面上临时改的槽位不能被设置流覆盖回去
                if (!defaultSlotApplied) {
                    defaultSlotApplied = true
                    if (s.defaultTargetSlot in 1..9 && _ui.value.targetSlotIndex != s.defaultTargetSlot) {
                        _ui.value = _ui.value.copy(targetSlotIndex = s.defaultTargetSlot)
                    }
                }
                // 设置里的两项直接作用在全局日志总线上，改完立刻生效
                HexLog.hexDumpEnabled = s.hexInLog
                HexLog.fileLoggingEnabled = s.logToFile
                if (!s.logToFile) HexLog.stopFileLogging() else HexLog.startFileLogging(getApplication())
            }
        }
        // 首次进入：没有参考图就用内置样张，保证预览区立刻有东西
        viewModelScope.launch(Dispatchers.Default) {
            val sample = ImagePipeline.builtinSample()
            val feats = ImageFeatureExtractor.extract(sample)
            _ui.value = _ui.value.copy(referenceBitmap = sample, features = feats)
            renderPreview()
        }
    }

    // ---------------- Tab ----------------

    fun selectTab(i: Int) {
        _ui.value = _ui.value.copy(selectedTab = i)
        // 进相册页：还没读过、或者刚拍过新照片 → 拉一次，省掉用户多点一下
        if (i == 2 && driver.isOpen && !_ui.value.galleryLoading) {
            if (_ui.value.galleryItems.isEmpty() || lastCaptureDirty) {
                lastCaptureDirty = false
                refreshGallery()
            }
        }
    }

    // ---------------- USB ----------------

    fun nikonDevices(): List<UsbDevice> = driver.findNikonDevices()

    /**
     * 主动扫描一次 USB 并**写进日志**。
     *
     * 为什么单独开一个方法：`nikonDevices()` 会被 Compose 在重组里反复调用，
     * 在那里写日志会刷屏；而真机排障时「这一轮到底枚举到了什么」恰恰是最关键的一条证据——
     * 用户拔线接相机跑一轮，回来只看日志文件就知道是没枚举到，还是枚举到了但握手失败。
     */
    fun scanUsbDevices(): List<UsbDevice> {
        val all = runCatching { usbManager().deviceList.values.toList() }.getOrDefault(emptyList())
        val nikon = driver.findNikonDevices()
        val detail = if (all.isEmpty()) {
            "0 个（OTG 主机未建立：无外设）"
        } else {
            all.joinToString("; ") {
                "%04X:%04X %s".format(it.vendorId, it.productId, it.productName ?: "?")
            }
        }
        HexLog.info(
            "USB 扫描",
            "共 ${all.size} 个外设 / Nikon ${nikon.size} 个 → $detail",
        )
        return nikon
    }

    fun hasPermission(dev: UsbDevice): Boolean = driver.hasPermission(dev)

    fun usbManager(): UsbManager =
        getApplication<Application>().getSystemService(android.content.Context.USB_SERVICE) as UsbManager

    fun connectDevice(dev: UsbDevice) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writeMsg = "正在握手…")
            val r = driver.open(dev)
            r.onSuccess { info ->
                _ui.value = _ui.value.copy(
                    writeMsg = "已连接 ${info.model.ifBlank { "Nikon" }}",
                    toast = "Z30 已连接，开始读取槽位",
                )
                // 探测操作码族
                driver.probeOpcodeProfile()
                _ui.value = _ui.value.copy(useAltOpcode = driver.useAltOpcodeProfile)
                refreshSlots()
                loadShootingParams()
            }.onFailure { t ->
                _ui.value = _ui.value.copy(
                    writeMsg = "连接失败：${t.message}",
                    toast = "连接失败：${t.message}",
                )
            }
        }
    }

    /** 相机被拔掉时由 Activity 调用：不发 CloseSession（通道已断），只复位本地状态。 */
    fun onDeviceDetached() {
        viewModelScope.launch {
            templateCache.clear()
            runCatching { driver.close() }
            _ui.value = _ui.value.copy(
                slots = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
                templatePayload = null,
                liveViewActive = false,
                liveViewBitmap = null,
                liveViewFrames = 0,
                galleryVolumes = emptyList(),
                galleryItems = emptyList(),
                thumbnails = emptyMap(),
                galleryLoading = false,
                movieRecording = false,
                paramsLoaded = false,
                writeMsg = "相机已拔出",
                toast = "Z30 已断开",
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            driver.close()
            templateCache.clear()
            _ui.value = _ui.value.copy(
                slots = (1..9).map { SlotState(it, Ptp.CUSTOM_SLOTS[it - 1]) },
                templatePayload = null,
                liveViewActive = false,
                liveViewBitmap = null,
                galleryVolumes = emptyList(),
                galleryItems = emptyList(),
                thumbnails = emptyMap(),
                movieRecording = false,
                paramsLoaded = false,
                writeMsg = "已断开",
            )
        }
    }

    /** 读取 C-1~C-9 当前名称，并把第一个成功读到的存为写入模板。 */
    fun refreshSlots() {
        if (!driver.isOpen) return
        viewModelScope.launch {
            val slots = mutableListOf<SlotState>()
            var template: ByteArray? = _ui.value.templatePayload
            for (i in 1..9) {
                val ptpSlot = Ptp.CUSTOM_SLOTS[i - 1]
                val r = driver.getPicCtrlData(ptpSlot)
                val data = r.getOrNull()
                if (data != null && data.size == com.zcloud.ai.core.PcPayload614.SIZE) {
                    templateCache[i] = data
                    if (template == null) template = data
                    val parsed = NcpParserAndBuilder.parsePayload(data)
                    slots.add(
                        SlotState(
                            index = i,
                            ptpSlot = ptpSlot,
                            name = parsed?.name?.ifBlank { "C-$i" } ?: "C-$i",
                            loaded = true,
                            isTemplate = (template === data),
                            rawSize = data.size,
                        )
                    )
                } else {
                    // 空槽位读回 AccessDenied 是尼康的正常行为（没注册过的槽位没有数据），
                    // 读到 36B 全零空壳也一样——这些显示「空槽位」而不是「读取失败」，
                    // 后者会让人以为程序坏了。
                    val code = (r.exceptionOrNull() as? PtpException)?.responseCode ?: -1
                    val label = when (code) {
                        Ptp.RC_ACCESS_DENIED, Ptp.RC_LOCAL_EMPTY_SLOT -> "空槽位"
                        Ptp.RC_INVALID_PARAMETER -> "参数不支持"
                        else -> "读取失败"
                    }
                    slots.add(SlotState(i, ptpSlot, name = label, loaded = false, isEmpty = true))
                }
            }
            _ui.value = _ui.value.copy(slots = slots, templatePayload = template)
        }
    }

    fun setTargetSlot(index: Int) {
        _ui.value = _ui.value.copy(targetSlotIndex = index)
    }

    /**
     * 跑一遍写入机制矩阵探测。
     *
     * 为什么把它做成用户可点的按钮：Z30 对 0x90CD 返回 AccessDenied 时不会说明缺什么条件，
     * 参数形态、载荷前缀、要不要先进 PC 控制模式、要不要先停取景——这些只能实测。
     * 与其让用户回报「写不进去」，不如让程序自己把「参数形态 × 载荷形态 × 前置动作」的组合跑一遍，命中即固化。
     */
    fun probeWriteMechanism() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.probingWrite) return
        viewModelScope.launch {
            val slotIdx = _ui.value.targetSlotIndex
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIdx - 1]
            val template = templateCache[slotIdx] ?: _ui.value.templatePayload
                ?: NcpParserAndBuilder.defaultTemplate()
            val payload = NcpParserAndBuilder.buildPayload(template, _ui.value.recipe, slotIdx)

            _ui.value = _ui.value.copy(
                probingWrite = true,
                probeLines = listOf("开始探测 C-$slotIdx (0x%04X)…".format(ptpSlot)),
                writeMsg = "正在探测写入机制…",
            )
            val (hit, all) = driver.probeWriteMechanism(ptpSlot, slotIdx, payload) { probe ->
                _ui.value = _ui.value.copy(
                    probeLines = (_ui.value.probeLines + "· ${probe.label} → 0x%04X %s".format(
                        probe.responseCode, Ptp.responseName(probe.responseCode),
                    ) + (probe.readBackName?.let { " ✓$it" } ?: "")).takeLast(14),
                )
            }
            if (hit != null) {
                val mode = driver.writeMode
                _ui.value = _ui.value.copy(
                    probingWrite = false,
                    writeModeLabel = mode?.describe() ?: "",
                    writeMsg = "✅ 找到可用写入机制：${mode?.describe()}\n读回名称：${hit.readBackName}\n后续写入会自动走这套。",
                    toast = "写入机制已固化",
                )
                refreshSlots()
            } else {
                val denied = all.count { it.responseCode == Ptp.RC_ACCESS_DENIED }
                _ui.value = _ui.value.copy(
                    probingWrite = false,
                    writeMsg = "❌ 试了 ${all.size} 种组合全部失败（AccessDenied $denied 次）。\n" +
                        "请到调试台把这批 Hex 日志发我——里面能看出相机到底在拒绝什么。",
                    toast = "探测失败，请发调试台日志",
                )
            }
        }
    }

    /** 扫一片 item 号，把机身里所有能读到的优化校准列出来（找「存了却显示不出来」的槽位）。 */
    fun scanAllPicCtrlItems() {
        if (!driver.isOpen) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writeMsg = "扫描 0x00C0~0x00DC…")
            val found = driver.scanPicCtrlItems { item, name ->
                _ui.value = _ui.value.copy(
                    probeLines = (_ui.value.probeLines +
                        "· 0x%04X → %s".format(item, name ?: "无")).takeLast(14),
                )
            }
            _ui.value = _ui.value.copy(
                writeMsg = if (found.isEmpty()) {
                    "扫描完 0x00C0~0x00DC，一项都读不到。\n" +
                        "说明机身当前不允许读自定义槽位——先在相机上手动选一次「自定义优化校准」再试。"
                } else {
                    "扫到 ${found.size} 项：\n" + found.joinToString("\n") {
                        "C-%d (0x%04X) = %s".format(Ptp.customSlotIndex(it.first) + 1, it.first, it.second)
                    }
                },
                toast = "扫描完成：${found.size} 项",
            )
            refreshSlots()
        }
    }

    /**
     * 先问机身：审计优化校准能力（只读，不会改坏任何槽位）。
     *
     * 顺序上必须排在「盲写探测」前面——真机上 0x90CD 的失败码在 AccessDenied(0x200F) 和
     * InvalidParameter(0x201D) 之间跳，这两种码本身区分不出「参数错」和「没权限」，
     * 只有先拿到 0x90CF 能力表、0xD200 当前项、0x90C5 曲线通道的应答，才知道该往哪修。
     */
    fun auditPicCtrl() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.probingWrite) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                probingWrite = true,
                probeLines = listOf("正在向机身提问（全部只读操作）…"),
                writeMsg = "审计中…",
            )
            val r = driver.auditPictureControl()
            val text = r.getOrElse { "审计失败：${it.message}" }
            _ui.value = _ui.value.copy(
                probingWrite = false,
                writeMsg = text,
                probeLines = text.lines().filter { it.isNotBlank() }.takeLast(14),
                toast = if (r.isSuccess) "审计完成，结果见下方" else "审计失败",
            )
        }
    }

    /**
     * 无损回写：把机身某个已存在的槽位原样读出来再原样写回去。
     *
     * 这是整个写入问题的分水岭——内容一个字节都没变，所以绝不可能损坏数据，
     * 但它能回答「0x90CD 到底通不通」这个决定性问题是/否。
     */
    fun probeWriteBack() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.probingWrite) return
        viewModelScope.launch {
            val slotIdx = _ui.value.targetSlotIndex
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIdx - 1]
            _ui.value = _ui.value.copy(
                probingWrite = true,
                probeLines = listOf("无损回写 C-$slotIdx (0x%04X)…".format(ptpSlot)),
                writeMsg = "正在无损回写…",
            )
            val (hit, all) = driver.probeWriteBackSame(ptpSlot) { probe ->
                _ui.value = _ui.value.copy(
                    probeLines = (_ui.value.probeLines +
                        "· ${probe.params} → 0x%04X %s".format(
                            probe.responseCode, Ptp.responseName(probe.responseCode),
                        ) + (probe.readBackName?.let { " ✓$it" } ?: "")).takeLast(14),
                )
            }
            _ui.value = if (hit != null) {
                _ui.value.copy(
                    probingWrite = false,
                    writeModeLabel = driver.writeMode?.describe() ?: "",
                    writeMsg = "✅ 无损回写成功（${hit.params}）——0x90CD 通道是通的，614 字节载荷被接受。\n" +
                        "现在直接点「刷入」就能写进去。",
                    toast = "0x90CD 通道可用",
                )
            } else {
                _ui.value.copy(
                    probingWrite = false,
                    writeMsg = "❌ 连「原样 614 字节写回」都被拒" +
                        "（${all.joinToString(" / ") { "0x%04X".format(it.responseCode) }}）。\n" +
                        "说明 0x90CD 的写权限还缺前置条件，先点「审计机身」看能力表。",
                    toast = "0x90CD 通道被拒",
                )
            }
            refreshSlots()
        }
    }

    /** 把当前 recipe 刷入目标槽位。 */
    fun writeToCamera() {
        val st = _ui.value
        if (!driver.isOpen) {
            _ui.value = st.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writing = true, writeMsg = "写入中…")
            val slotIdx = _ui.value.targetSlotIndex
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIdx - 1]
            // 关键：以机身读回的真实载荷为模板，只覆盖我们确定的字段。
            // 设置里可以关掉「先备份」：关掉后若没缓存过模板就直接用内置默认，
            // 快一步但风险高——万一机身对某些保留字节有校验，写坏了不好还原。
            if (templateCache[slotIdx] == null && _ui.value.settings.backupBeforeWrite) {
                val backup = driver.getPicCtrlData(ptpSlot).getOrNull()
                if (backup != null) {
                    templateCache[slotIdx] = backup
                    HexLog.info("写入准备", "已先读回 C-$slotIdx 原样模板（${backup.size}B）作为备份")
                } else {
                    HexLog.warn("写入准备", "C-$slotIdx 读不到原样模板，改用内置默认模板")
                }
            }
            val template = templateCache[slotIdx] ?: _ui.value.templatePayload
                ?: NcpParserAndBuilder.defaultTemplate()

            val payload = NcpParserAndBuilder.buildPayload(template, _ui.value.recipe, slotIdx)
            HexLog.info(
                "写入准备",
                "slot=C-$slotIdx(0x%04X) payload=%dB template=%s".format(
                    ptpSlot, payload.size, if (templateCache[slotIdx] != null) "机身读回" else "内置默认"
                ),
            )
            val r = driver.setPicCtrlData(ptpSlot, payload)
            r.onSuccess {
                // 写入后读回校验。设置里可以关掉：省一次往返，但默认开——静默失败比慢更糟。
                val okName: Boolean
                if (_ui.value.settings.verifyAfterWrite) {
                    val verify = driver.getPicCtrlData(ptpSlot)
                    val v = verify.getOrNull()
                    okName = v != null && com.zcloud.ai.core.PcPayload614.readName(v) ==
                        NcpParserAndBuilder.encodeName(_ui.value.recipe.name).toString(Charsets.US_ASCII)
                } else {
                    okName = true
                }
                _ui.value = _ui.value.copy(
                    writing = false,
                    writeMsg = if (!_ui.value.settings.verifyAfterWrite) "已刷入 C-$slotIdx（未回读校验）"
                    else if (okName) "已刷入 C-$slotIdx ✓ 校验通过"
                    else "已刷入 C-$slotIdx（读回名称未完全匹配，请到相机确认）",
                    toast = "C-$slotIdx 写入完成",
                )
                refreshSlots()
            }.onFailure { t ->
                val code = (t as? PtpException)?.responseCode ?: -1
                if (code == Ptp.RC_ACCESS_DENIED && driver.writeMode == null) {
                    // 第一次遇到 AccessDenied：不要闷头跑 80 种盲探——真机实测那是浪费时间，
                    // 因为 AccessDenied 和 InvalidParameter 混在一起，错误码本身不告诉缺什么。
                    // 先跑一次「无损回写」：内容零变化，却能一刀切判定 0x90CD 通道通不通。
                    _ui.value = _ui.value.copy(
                        writing = false,
                        writeMsg = "相机拒绝了标准写入（AccessDenied），正在做无损回写判定…",
                    )
                    val (hit, all) = driver.probeWriteBackSame(ptpSlot) { p ->
                        _ui.value = _ui.value.copy(
                            probeLines = (_ui.value.probeLines +
                                "· ${p.params} → 0x%04X".format(p.responseCode)).takeLast(14),
                        )
                    }
                    if (hit != null) {
                        // 通道通：按固化下来的参数形态重试一次真实写入
                        val mode = driver.writeMode
                        val retry = driver.setPicCtrlData(ptpSlot, payload)
                        _ui.value = _ui.value.copy(
                            writeModeLabel = mode?.describe() ?: "",
                            writeMsg = if (retry.isSuccess) {
                                "✅ 无损回写确认通道可用（${hit.params}），已按该形态写入 C-$slotIdx。"
                            } else {
                                "无损回写能通（${hit.params}），但真实载荷仍被拒：${retry.exceptionOrNull()?.message}\n" +
                                    "说明问题在载荷内容而非通道——把当前配方换个更简单的曲线再试。"
                            },
                            toast = if (retry.isSuccess) "写入成功" else "载荷被拒",
                        )
                        refreshSlots()
                    } else {
                        _ui.value = _ui.value.copy(
                            writeMsg = "写入失败：连「原样写回」都被拒（${
                                all.joinToString(" / ") { "0x%04X".format(it.responseCode) }
                            }）。\n" +
                                "结论：Z30 固件封着 0x90CD 写入通道，改参数形态没有意义。\n" +
                                "请点「① 审计机身」——里面会试 0x90C5 曲线通道和 0x9435 应用模式。",
                            toast = "0x90CD 通道被封",
                        )
                    }
                } else {
                    _ui.value = _ui.value.copy(
                        writing = false,
                        writeMsg = "写入失败：${t.message}",
                        toast = "写入失败：${t.message}",
                    )
                }
            }
        }
    }

    /** 批量刷入多个槽位。 */
    fun batchWrite(targets: List<Int>, recipe: Recipe) {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writing = true, writeMsg = "批量写入 ${targets.size} 个槽位…")
            var ok = 0
            for (idx in targets) {
                val ptpSlot = Ptp.CUSTOM_SLOTS[idx - 1]
                val template = templateCache[idx] ?: NcpParserAndBuilder.defaultTemplate()
                val payload = NcpParserAndBuilder.buildPayload(template, recipe, idx)
                if (driver.setPicCtrlData(ptpSlot, payload).isSuccess) ok++
            }
            _ui.value = _ui.value.copy(
                writing = false,
                writeMsg = "批量完成 $ok/${targets.size}",
                toast = "批量写入 $ok/${targets.size} 成功",
            )
            refreshSlots()
        }
    }

    fun clearSlot(index: Int) {
        if (!driver.isOpen) return
        viewModelScope.launch {
            val ptpSlot = Ptp.CUSTOM_SLOTS[index - 1]
            val r = driver.deleteCustomPicCtrl(ptpSlot)
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "C-$index 已清空" else "清空失败：${r.exceptionOrNull()?.message}",
            )
            refreshSlots()
        }
    }

    // ---------------- 图像与预览 ----------------

    fun pickReference(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                ImageLoader.load(getApplication(), uri, 1600)
            }
            if (bmp == null) {
                _ui.value = _ui.value.copy(toast = "图片读取失败")
                return@launch
            }
            val feats = withContext(Dispatchers.Default) { ImageFeatureExtractor.extract(bmp) }
            _ui.value = _ui.value.copy(referenceBitmap = bmp, features = feats, lastAnalysis = "")
            renderPreview()
        }
    }

    fun setShowOriginal(show: Boolean) {
        _ui.value = _ui.value.copy(showOriginal = show)
    }

    fun setUserHint(hint: String) {
        _ui.value = _ui.value.copy(userHint = hint)
    }

    /** 拖动曲线控制点后更新 recipe 并重渲染（带 debounce）。 */
    fun updateCurvePoints(points: List<CubicSplineLut.Point>) {
        val clean = AiColorGradingRepository.sanitizeCurve(points)
        val lut = CubicSplineLut.buildLut(clean)
        _ui.value = _ui.value.copy(
            recipe = _ui.value.recipe.copy(curvePoints = clean, lut = lut, useCustomCurve = true),
        )
        renderPreview()
    }

    fun updateRecipeParam(
        sharpening: Float? = null,
        clarity: Float? = null,
        contrast: Float? = null,
        brightness: Float? = null,
        saturation: Float? = null,
        hue: Float? = null,
        name: String? = null,
    ) {
        val r = _ui.value.recipe
        val nr = r.copy(
            sharpening = sharpening ?: r.sharpening,
            clarity = clarity ?: r.clarity,
            contrast = contrast ?: r.contrast,
            brightness = brightness ?: r.brightness,
            saturation = saturation ?: r.saturation,
            hue = hue ?: r.hue,
            name = name ?: r.name,
        )
        _ui.value = _ui.value.copy(recipe = nr)
        if (contrast != null || brightness != null || saturation != null || hue != null) {
            renderPreview()
        }
    }

    /** 256 LUT 套到参考图上生成预览。debounce 180ms 避免拖动时卡顿。 */
    fun renderPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(180)
            val st = _ui.value
            val src = st.referenceBitmap ?: return@launch
            val r = st.recipe
            val bmp = withContext(Dispatchers.Default) {
                ImagePipeline.render(
                    src = src,
                    lut = r.lut,
                    contrast = r.contrast,
                    brightness = r.brightness,
                    saturation = r.saturation,
                    hue = r.hue,
                )
            }
            _ui.value = _ui.value.copy(previewBitmap = bmp)
        }
    }

    // ---------------- AI ----------------

    fun analyzeWithAi() {
        val st = _ui.value
        val bmp = st.referenceBitmap ?: run {
            _ui.value = st.copy(toast = "请先选择参考图")
            return
        }
        val account = st.activeAccount
        if (account.apiKey.isBlank()) {
            _ui.value = st.copy(toast = "${account.provider.displayName} 未配置 API Key，请到「本地预设库」填写")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                analyzing = true,
                analyzingMsg = "上传参考图 → ${account.provider.displayName}",
                lastAnalysis = "",
            )
            val (recipe, result) = AiColorGradingRepository.analyzeBitmap(bmp, account, _ui.value.userHint)
            store.recordUsage(
                providerId = account.providerId,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                error = if (result.success) "" else result.error,
            )
            if (recipe != null) {
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    recipe = recipe,
                    analyzingMsg = "",
                    lastAnalysis = recipe.styleAnalysis,
                    toast = "分析完成 · 用时 ${result.elapsedMs}ms · ${result.promptTokens}+${result.completionTokens} tokens",
                )
                renderPreview()
            } else {
                _ui.value = _ui.value.copy(
                    analyzing = false,
                    analyzingMsg = "",
                    lastAnalysis = "失败：${result.error}",
                    toast = "AI 分析失败：${result.error.take(60)}",
                )
            }
        }
    }

    fun saveCurrentPreset() {
        val r = _ui.value.recipe
        viewModelScope.launch {
            val preset = SavedPreset(
                id = UUID.randomUUID().toString(),
                name = r.name,
                source = if (r.styleAnalysis.isNotBlank()) "AI" else "MANUAL",
                recipe = r,
            )
            store.savePreset(preset)
            _ui.value = _ui.value.copy(toast = "已保存：${r.name}")
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { store.deletePreset(id) }
    }

    fun loadPreset(preset: SavedPreset) {
        _ui.value = _ui.value.copy(recipe = preset.recipe, lastAnalysis = preset.recipe.styleAnalysis)
        renderPreview()
        _ui.value = _ui.value.copy(toast = "已载入 ${preset.name}")
    }

    // ---------------- 内置 200 套预设 ----------------

    fun setBuiltinCategory(categoryId: String?) {
        _ui.value = _ui.value.copy(builtinCategory = categoryId)
    }

    fun setBuiltinQuery(q: String) {
        _ui.value = _ui.value.copy(builtinQuery = q)
    }

    fun setBuiltinPreview(id: String?) {
        _ui.value = _ui.value.copy(builtinPreviewId = if (_ui.value.builtinPreviewId == id) null else id)
    }

    /** 载入内置配方到「当前配方」，之后可继续在曲线编辑器里微调。 */
    fun loadBuiltIn(preset: BuiltInPreset) {
        _ui.value = _ui.value.copy(
            recipe = preset.recipe,
            lastAnalysis = "${preset.displayName}（${preset.categoryLabel}）— ${preset.note}",
        )
        renderPreview()
        _ui.value = _ui.value.copy(toast = "已载入「${preset.displayName}」")
    }

    /** 把内置配方直接刷进指定槽位（不经当前配方，避免覆盖用户手上的编辑）。 */
    fun writeBuiltInToSlot(preset: BuiltInPreset, slotIndex: Int) {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writing = true, writeMsg = "写入「${preset.displayName}」→ C-$slotIndex…")
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIndex - 1]
            val template = templateCache[slotIndex] ?: NcpParserAndBuilder.defaultTemplate()
            val payload = NcpParserAndBuilder.buildPayload(template, preset.recipe, slotIndex)
            val r = driver.setPicCtrlData(ptpSlot, payload)
            r.onSuccess {
                _ui.value = _ui.value.copy(writing = false, toast = "${preset.displayName} → C-$slotIndex 成功")
                refreshSlots()
            }.onFailure {
                _ui.value = _ui.value.copy(writing = false, toast = "写入失败：${it.message}")
            }
        }
    }

    /** 内置配方 → 存进本地预设库（用户想混着自己的配方一起管理时用）。 */
    fun saveBuiltInToLibrary(preset: BuiltInPreset) {
        viewModelScope.launch {
            store.savePreset(
                SavedPreset(
                    id = UUID.randomUUID().toString(),
                    name = preset.displayName,
                    source = "BUILTIN",
                    recipe = preset.recipe,
                )
            )
            _ui.value = _ui.value.copy(toast = "已收藏「${preset.displayName}」到预设库")
        }
    }

    fun toggleAiPanel() {
        _ui.value = _ui.value.copy(aiPanelExpanded = !_ui.value.aiPanelExpanded)
    }

    fun writePresetToSlot(preset: SavedPreset, slotIndex: Int) {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val ptpSlot = Ptp.CUSTOM_SLOTS[slotIndex - 1]
            val template = templateCache[slotIndex] ?: NcpParserAndBuilder.defaultTemplate()
            val payload = NcpParserAndBuilder.buildPayload(template, preset.recipe, slotIndex)
            val r = driver.setPicCtrlData(ptpSlot, payload)
            r.onSuccess {
                store.markPresetSlot(preset.id, slotIndex)
                _ui.value = _ui.value.copy(toast = "${preset.name} → C-$slotIndex 成功")
                refreshSlots()
            }.onFailure {
                _ui.value = _ui.value.copy(toast = "写入失败：${it.message}")
            }
        }
    }

    /**
     * 导入 .NCP 文件。
     *
     * 排障要点：这里的每条失败路径都必须给出**具体原因**（打不开 / 空文件 / 结构不认识 / 多大），
     * 因为用户在手机上点一次导入失败，我们没有 adb 就没法复现。以前只报「读取文件失败」，
     * 结果连「UI 的 OpenDocument 回调到底有没有触发」都判断不了。
     */
    fun importNcp(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            HexLog.info("NCP 导入", "开始读取 $uri")

            val name = withContext(Dispatchers.IO) { runCatching { ImageLoader.displayName(ctx, uri) }.getOrDefault("import.NCP") }

            val bytes = withContext(Dispatchers.IO) {
                runCatching { ImageLoader.readBytesOrThrow(ctx, uri) }
            }
            val data = bytes.getOrElse { t ->
                val msg = t.message ?: t.javaClass.simpleName
                HexLog.error("NCP 导入失败", "读取 $name 失败：$msg")
                _ui.value = _ui.value.copy(
                    toast = "读取失败：$msg",
                    lastAnalysis = "导入 $name 失败：$msg",
                )
                return@launch
            }
            HexLog.info("NCP 导入", "读到 $name，${data.size} 字节")

            val parsed = NcpParserAndBuilder.parseNcpFile(name, data)
            parsed.onSuccess { ncp ->
                HexLog.info("NCP 导入成功", "$name → ${ncp.recipe.name}（${ncp.layout}，源 ${ncp.rawSize}B）")
                _ui.value = _ui.value.copy(
                    recipe = ncp.recipe,
                    lastAnalysis = "从 ${ncp.fileName} 导入（${ncp.layout}，${ncp.rawSize}B）",
                    toast = "解析成功：${ncp.recipe.name}（${ncp.layout}）",
                )
                renderPreview()
                store.savePreset(
                    SavedPreset(
                        id = UUID.randomUUID().toString(),
                        name = ncp.recipe.name,
                        source = "NCP",
                        recipe = ncp.recipe,
                    )
                )
            }.onFailure { t ->
                val msg = t.message ?: t.javaClass.simpleName
                HexLog.error("NCP 导入失败", "$name（${data.size}B）解析不了：$msg")
                _ui.value = _ui.value.copy(
                    toast = "解析失败：$msg",
                    lastAnalysis = "导入 $name 失败（${data.size} 字节）：$msg",
                )
            }
        }
    }

    // ---------------- 设置 ----------------

    fun setApiKey(providerId: String, key: String) {
        viewModelScope.launch { store.setApiKey(providerId, key) }
    }

    fun setModelOverride(providerId: String, model: String) {
        viewModelScope.launch { store.setModelOverride(providerId, model) }
    }

    fun setActiveProvider(providerId: String) {
        viewModelScope.launch {
            store.setActiveProvider(providerId)
            _ui.value = _ui.value.copy(toast = "已切换到 ${LlmProvider.byId(providerId).displayName}")
        }
    }

    fun resetUsage(providerId: String) {
        viewModelScope.launch {
            store.resetUsage(providerId)
            _ui.value = _ui.value.copy(toast = "用量已清零")
        }
    }

    // ---------------- 常规设置 ----------------

    /**
     * 改一项常规设置。
     *
     * 统一走 `updateSettings { it.copy(...) }`：DataStore 里存的是一个 JSON，
     * 并发改两个开关时各自 copy 再写会互相覆盖，所以 store 里是先 first() 再写，
     * 这里只负责把用户动作翻译成一次 copy。
     */
    private fun editSettings(transform: (com.zcloud.ai.data.AppSettings) -> com.zcloud.ai.data.AppSettings) {
        viewModelScope.launch {
            store.updateSettings(transform)
            // 改完立刻回灌一次，避免开关视觉上慢半拍
            _ui.value = _ui.value.copy(settings = store.settings.first())
        }
    }

    fun setGalleryLimit(v: Int) = editSettings { it.copy(galleryLimit = v) }

    fun setLiveViewInterval(v: Int) = editSettings { it.copy(liveViewIntervalMs = v) }

    fun setBackupBeforeWrite(v: Boolean) = editSettings { it.copy(backupBeforeWrite = v) }

    fun setVerifyAfterWrite(v: Boolean) = editSettings { it.copy(verifyAfterWrite = v) }

    fun setAutoSaveCapture(v: Boolean) = editSettings { it.copy(autoSaveCapture = v) }

    fun setLogToFile(v: Boolean) = editSettings { it.copy(logToFile = v) }

    fun setHexInLog(v: Boolean) = editSettings { it.copy(hexInLog = v) }

    fun setDefaultTargetSlot(v: Int) = editSettings { it.copy(defaultTargetSlot = v) }

    fun setKeepScreenOn(v: Boolean) = editSettings { it.copy(keepScreenOn = v) }

    /** 恢复默认设置。只动设置，不碰预设库与 API Key。 */
    fun resetSettings() {
        viewModelScope.launch {
            store.updateSettings { com.zcloud.ai.data.AppSettings() }
            _ui.value = _ui.value.copy(
                settings = store.settings.first(),
                toast = "设置已恢复默认",
            )
        }
    }

    // ---------------- 调试台 ----------------

    /** 打开/关闭 USB 调试台覆盖层（入口在设置页，主界面不再占一个 tab）。 */
    fun openConsole(open: Boolean = true) {
        _ui.value = _ui.value.copy(consoleOpen = open)
        if (open) HexLog.info("调试台", "以覆盖层方式打开（入口：设置 → 高级 → USB 调试台）")
    }

    /** 打开/关闭设置覆盖层。 */
    fun openSettings(open: Boolean = true) {
        _ui.value = _ui.value.copy(settingsOpen = open)
    }

    /** 切换优化校准操作码的参数形态并重新探测。两参数 (slot,0) ↔ 单参数 (slot) */
    fun setOpcodeProfile(alt: Boolean) {
        driver.useAltOpcodeProfile = alt
        _ui.value = _ui.value.copy(useAltOpcode = alt)
        HexLog.info(
            "参数形态切换",
            if (alt) "0x90CC/0x90CD/0x90CE 单参数 (slot)"
            else "0x90CC/0x90CD/0x90CE 双参数 (slot, 0)",
        )
        if (driver.isOpen) {
            templateCache.clear()
            refreshSlots()
        }
    }

    fun probeProfile() {
        viewModelScope.launch {
            val r = driver.probeOpcodeProfile()
            _ui.value = _ui.value.copy(useAltOpcode = driver.useAltOpcodeProfile)
            _ui.value = _ui.value.copy(
                toast = r.fold(
                    onSuccess = { ok -> if (ok) "操作码可用：0x%04X".format(driver.opGetPicCtrl) else "两种操作码族都无响应" },
                    onFailure = { it.message ?: "探测失败" },
                )
            )
            if (driver.isOpen) refreshSlots()
        }
    }

    /** 主动进/出 PC 控制模式（0x90C2），有些机身只有在这个模式下才吐自定义槽位。 */
    fun enterPcControlMode(on: Boolean = true) {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val r = driver.changeCameraMode(on)
            HexLog.info(
                "ChangeCameraMode 0x90C2",
                if (r.isSuccess) "已切到 ${if (on) "PC 控制" else "普通"} 模式" else "失败：${r.exceptionOrNull()?.message}",
            )
            driver.deviceReadyBlocking()
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "已切 PC 控制模式，正在重读槽位" else "切换失败",
            )
            templateCache.clear()
            refreshSlots()
        }
    }

    /** 一键诊断：分 4 层定位卡在哪一步，结果进日志和 toast。 */
    fun runDiagnose() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(writeMsg = "诊断中…")
            val r = driver.diagnose()
            _ui.value = _ui.value.copy(
                writeMsg = r.getOrElse { "诊断失败：${it.message}" },
                toast = if (r.isSuccess) "诊断完成，见日志" else "诊断失败",
            )
        }
    }

    /**
     * 问机身：这 8 条属性到底哪些是「只读」。
     *
     * 真机上 `0x500E` 回 AccessDenied、光圈 ISO 却能写，光看错误码分不清是
     * 「属性本身只读」还是「当前状态被锁」。`0x1014` 描述符里第 5 字节就是
     * GetSet 标志，是机身自己给的权威答案。全程只读，不改任何机身状态。
     */
    fun probePropWritable() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(probingWrite = true, writeMsg = "正在读属性描述符…")
            val list = runCatching { driver.probeWritableProps() }.getOrElse {
                _ui.value = _ui.value.copy(probingWrite = false, writeMsg = "读属性失败：${it.message}")
                return@launch
            }
            val lines = list.map { p ->
                val flag = when (p.writable) {
                    true -> "可写"
                    false -> "只读"
                    null -> "未知"
                }
                "%-18s %s  当前=%s  %s".format(p.label, flag, p.currentHex, p.note)
            }
            val rw = list.count { it.writable == true }
            val ro = list.count { it.writable == false }
            val head = "属性可写性（0x1014 描述符）：可写 $rw 条 / 只读 $ro 条 / 未知 ${list.size - rw - ro} 条"
            HexLog.info("属性可写性", (listOf(head) + lines).joinToString(" ⏎ "))
            _ui.value = _ui.value.copy(
                probingWrite = false,
                probeLines = lines,
                writeMsg = head,
                // 把「机身自己说只读」的那些记下来：下发时直接跳过，不再白发一次 0x1016
                readOnlyProps = list.filter { it.writable == false }.map { it.code }.toSet(),
                toast = "可写 $rw 条 / 只读 $ro 条，详见日志",
            )
        }
    }

    /** 重新读一个槽位并把 84 字节原始载荷写进 Hex 日志，方便联调对偏移。 */
    fun dumpSlotRaw(index: Int) {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val ptpSlot = Ptp.CUSTOM_SLOTS[index - 1]
            val r = driver.getPicCtrlData(ptpSlot)
            r.onSuccess { bytes ->
                templateCache[index] = bytes
                HexLog.log(
                    HexLogEntry.Direction.RX, -1,
                    "GetPicCtrlData C-$index 原始载荷 ${bytes.size}B", bytes,
                    note = "槽位 0x%04X".format(ptpSlot),
                )
                _ui.value = _ui.value.copy(toast = "C-$index 读回 ${bytes.size} 字节，见日志")
            }.onFailure { t ->
                _ui.value = _ui.value.copy(toast = "读取失败：${t.message}")
            }
        }
    }

    /** 把指定槽位的模板塞进当前 recipe 的曲线段（用于“基于机身模板微调”）。 */
    suspend fun captureTemplateFor(index: Int): ByteArray? {
        if (!driver.isOpen) return templateCache[index] ?: _ui.value.templatePayload
        val ptpSlot = Ptp.CUSTOM_SLOTS[index - 1]
        val bytes = driver.getPicCtrlData(ptpSlot).getOrNull() ?: return templateCache[index]
        templateCache[index] = bytes
        return bytes
    }

    fun clearLog() = HexLog.clear()

    /**
     * 把 Hex 日志写成文件，路径回显在 toast 里。
     *
     * 设计动机：真机上只有一个 USB-C 口，接了电脑做 adb 就没法接相机 —— 用户没法一边联调一边取日志。
     * 让 App 自己落盘到 `Android/data/com.zcloud.ai/files/`，拔线接相机跑一轮，之后用任意文件管理器
     * 或插回电脑取文件即可。这个目录不需要任何存储权限。
     */
    fun exportLogToFile() {
        val ctx = getApplication<Application>()
        val f = HexLog.saveToFile(ctx)
        if (f == null) {
            _ui.value = _ui.value.copy(toast = "日志导出失败（目录不可写）")
        } else {
            HexLog.info("日志已导出", f.absolutePath)
            _ui.value = _ui.value.copy(
                toast = "已导出：${f.absolutePath}（${f.length() / 1024}KB）",
                writeMsg = "日志文件：\n${f.absolutePath}",
            )
        }
    }

    /** 手机当前是否枚举到了任何 USB 外设。false 说明 OTG 主机没起来，压根没到 PTP 层。 */
    fun usbDeviceCount(): Int = runCatching { usbManager().deviceList.size }.getOrDefault(0)

    /**
     * 把最新日志用系统分享发出去（微信/QQ/邮件/网盘都行）。
     *
     * 为什么不只给路径：Android 11+ 之后 `/sdcard/Android/data/<pkg>/` 对文件管理器不可见，
     * 普通用户根本进不去那个目录。走 FileProvider + ACTION_SEND，一键就能把文件发到电脑上。
     */
    fun shareLatestLog() {
        val ctx = getApplication<Application>()
        val src = HexLog.currentLogFile() ?: HexLog.saveToFile(ctx)
        if (src == null || !src.exists()) {
            _ui.value = _ui.value.copy(toast = "还没有日志文件")
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", src
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Z-Cloud AI 日志 ${src.name}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(intent, "发送日志文件").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            _ui.value = _ui.value.copy(
                toast = "已调起分享：${src.name}（${src.length() / 1024}KB）",
                writeMsg = "日志文件：${src.absolutePath}（${src.length() / 1024}KB）",
            )
        }.onFailure { t ->
            _ui.value = _ui.value.copy(toast = "分享失败：${t.message}")
        }
    }

    // ---------------- 实时取景（Live View） ----------------

    private var liveViewJob: Job? = null

    /** 开/关实时取景。Z30 的 0x9201 只负责进 LV 模式，画面靠 0x9203 轮询拉 JPEG。 */
    fun toggleLiveView() {
        if (_ui.value.liveViewActive) stopLiveView() else startLiveView()
    }

    fun startLiveView() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (liveViewJob != null) return
        _ui.value = _ui.value.copy(liveViewActive = true, liveViewError = "", liveViewFrames = 0)
        liveViewJob = viewModelScope.launch {
            val started = driver.startLiveView()
            if (started.isFailure) {
                val msg = started.exceptionOrNull()?.message ?: "0x9201 无响应"
                HexLog.warn("实时取景开启失败", msg)
                _ui.value = _ui.value.copy(liveViewActive = false, liveViewError = msg)
                liveViewJob = null
                return@launch
            }
            HexLog.info("实时取景", "0x9201 StartLiveView 成功，开始轮询 0x9203")
            var frames = 0
            var missStreak = 0
            while (_ui.value.liveViewActive) {
                val r = driver.getLiveViewFrame()
                val bytes = r.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = withContext(Dispatchers.Default) {
                        runCatching {
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        frames++
                        missStreak = 0
                        _ui.value = _ui.value.copy(liveViewBitmap = bmp, liveViewFrames = frames)
                    } else {
                        missStreak++
                    }
                } else {
                    missStreak++
                    if (missStreak == 8) {
                        HexLog.warn("实时取景", r.exceptionOrNull()?.message ?: "连续 8 帧无数据")
                    }
                }
                // 取景是预览，不必榨满帧率：默认 150ms（约 6fps）够对焦构图，
                // 想看清运动瞬间可以在设置里调到 60ms
                delay(if (missStreak > 3) 400L else _ui.value.settings.liveViewIntervalMs.toLong())
            }
        }
    }

    fun stopLiveView() {
        if (!_ui.value.liveViewActive && liveViewJob == null) return
        _ui.value = _ui.value.copy(liveViewActive = false)
        val job = liveViewJob
        liveViewJob = null
        viewModelScope.launch {
            job?.cancel()
            driver.endLiveView()
            _ui.value = _ui.value.copy(liveViewBitmap = null)
            HexLog.info("实时取景", "0x9202 EndLiveView")
        }
    }

    /**
     * 停取景并**等它真的停掉**再返回。
     *
     * `stopLiveView()` 是 fire-and-forget（内部 launch），拍摄/下发参数前如果只调它，
     * 0x9202 还在飞的时候 0x9207 / 0x1016 就发出去了，机身一律回 AccessDenied。
     * 这是「点下发报 0x500E AccessDenied」「拍完拿不到照片」的共同原因。
     */
    private suspend fun stopLiveViewAwait() {
        val job = liveViewJob
        liveViewJob = null
        _ui.value = _ui.value.copy(liveViewActive = false, liveViewBitmap = null)
        job?.cancelAndJoin()
        runCatching { driver.endLiveView() }
        // 给机身一点时间真正退出 LV 模式，否则紧接着的属性写入仍会被判无权限
        delay(200)
        HexLog.info("实时取景", "0x9202 EndLiveView（已确认停止）")
    }

    // ---------------- 拍摄参数（0x1015 / 0x1016） ----------------

    /** 从机身读回当前曝光参数，填充 UI。读不到的项保持默认值。 */
    fun loadShootingParams() {
        if (!driver.isOpen) return
        viewModelScope.launch {
            val iso = driver.getDeviceProp(Ptp.DPC_ISO).getOrNull()?.let { CameraStorage.u16(it, 0) }
            val fnum = driver.getDeviceProp(Ptp.DPC_F_NUMBER).getOrNull()?.let { CameraStorage.u16(it, 0) }
            val prog = driver.getDeviceProp(Ptp.DPC_EXPOSURE_PROGRAM_MODE).getOrNull()
                ?.let { CameraStorage.u16(it, 0) }
            val ec = driver.getDeviceProp(Ptp.DPC_EXPOSURE_BIAS_COMPENSATION).getOrNull()
                ?.let { CameraStorage.u16(it, 0).toShort().toInt() }
            val wb = driver.getDeviceProp(Ptp.DPC_WHITE_BALANCE).getOrNull()
                ?.let { CameraStorage.u16(it, 0) }
            val pc = driver.getDeviceProp(Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM).getOrNull()
                ?.let { CameraStorage.u16(it, 0) }
            val shutter = driver.getDeviceProp(Ptp.DPC_NIKON_EXPOSURE_TIME).getOrNull()?.let { d ->
                if (d.size >= 4) {
                    val packed = CameraStorage.u32(d, 0)
                    val num = (packed shr 16) and 0xFFFF
                    val den = packed and 0xFFFF
                    if (num in 1..0xFFFF && den in 1..0xFFFF) num.toFloat() / den.toFloat() else null
                } else null
            }
            val battery = driver.getDeviceProp(Ptp.DPC_BATTERY_LEVEL).getOrNull()
                ?.let { if (it.isEmpty()) -1 else (it[0].toInt() and 0xFF) }
            _ui.value = _ui.value.copy(
                iso = (iso ?: _ui.value.iso).coerceIn(32, 409600),
                aperture = fnum?.let { it / 100f } ?: _ui.value.aperture,
                shutterSeconds = shutter ?: _ui.value.shutterSeconds,
                exposureComp = ec?.let { it / 1000f } ?: _ui.value.exposureComp,
                exposureProgram = if (prog != null && prog in 1..4) prog else _ui.value.exposureProgram,
                whiteBalance = if (wb != null) wb else _ui.value.whiteBalance,
                picCtrlItem = if (pc != null && pc in 1..8) pc else _ui.value.picCtrlItem,
                paramsLoaded = true,
                batteryPercent = battery ?: _ui.value.batteryPercent,
            )
            HexLog.info(
                "参数读回",
                "ISO=${iso ?: "-"} F=${fnum?.let { it / 100f } ?: "-"} 程序=${prog ?: "-"} " +
                    "EC=${ec ?: "-"} WB=0x%04X PC=%d".format(wb ?: 0, pc ?: 0),
            )
        }
    }

    /**
     * 把 UI 上的曝光参数下发到机身。
     *
     * 四条修正（都来自真机失败与 ZENCHE 的对照）：
     * 1. **先真正停掉取景**，再一次性写 7 个属性，写完统一恢复 —— 之前是「取景开着直接发」，
     *    第一个 `0x500E` 就被 `0x200F AccessDenied` 挡掉。
     * 2. **按拍摄模式决定写哪几个**（ZENCHE `canAdjustExposureParameter`）：
     *    M 档才写快门、A/S 档才写 EV、P/A/S 不写快门 —— 写机身此刻不接管的属性只会拿到 AccessDenied。
     * 3. 每条都是 `setDeviceProp`（内部自带 5 次瞬时重试），批量期间关掉自动恢复取景。
     * 4. **同值不发**：尼康对「值没变」的写入也回 `0x200F`。下发前先读回机身现值，
     *    已经等于目标的直接跳过 —— 这是「点下发就报错」最常见的一种，用户只是没改任何东西。
     */
    fun applyShootingParams() {
        val st = _ui.value
        if (!driver.isOpen) {
            _ui.value = st.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            val wasLive = st.liveViewActive
            val errs = ArrayList<String>()
            val skipped = ArrayList<String>()
            if (wasLive) stopLiveViewAwait()
            driver.autoResumeLiveView = false
            try {
                driver.waitUntilDeviceReady(4000)

                val mode = st.exposureProgram
                val canShutter = mode == 1 || mode == 4
                val canEv = mode != 1
                // 机身自报只读的属性：发了也是 AccessDenied，直接跳过并提示去相机上拨
                val ro = st.readOnlyProps

                // 机身现值（读不到就当未知，照常下发）
                val curProg = leU16(driver.getDeviceProp(Ptp.DPC_EXPOSURE_PROGRAM_MODE).getOrNull())
                val curIso = leU16(driver.getDeviceProp(Ptp.DPC_ISO).getOrNull())
                val curF = leU16(driver.getDeviceProp(Ptp.DPC_F_NUMBER).getOrNull())
                val curEv = leI16(driver.getDeviceProp(Ptp.DPC_EXPOSURE_BIAS_COMPENSATION).getOrNull())
                val curWb = leU16(driver.getDeviceProp(Ptp.DPC_WHITE_BALANCE).getOrNull())
                val curPc = leU16(driver.getDeviceProp(Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM).getOrNull())

                // 顺序有讲究：先定模式，再定曝光三要素，最后是风格类
                if (Ptp.DPC_EXPOSURE_PROGRAM_MODE in ro) {
                    skipped.add("曝光程序（0x500E 机身只读，请在相机上拨）")
                } else if (curProg == mode) {
                    skipped.add("曝光程序（已是 ${programShort(mode)} 档）")
                } else {
                    driver.setExposureProgram(mode).onFailure { errs.add("程序:${it.message}") }
                    delay(60)
                }
                if (curIso == st.iso) {
                    skipped.add("ISO（已是 ${st.iso}）")
                } else {
                    driver.setIso(st.iso).onFailure { errs.add("ISO:${it.message}") }
                    delay(60)
                }
                if (mode == 4) {
                    skipped.add("光圈（S 档由机身决定）")
                } else if (curF == Math.round(st.aperture * 100)) {
                    skipped.add("光圈（已是 F${fmtOneDecimal(st.aperture)}）")
                } else {
                    driver.setAperture(st.aperture.toDouble()).onFailure { errs.add("光圈:${it.message}") }
                    delay(60)
                }
                if (Ptp.DPC_NIKON_EXPOSURE_TIME in ro && Ptp.DPC_EXPOSURE_TIME in ro) {
                    skipped.add("快门（0xD100/0x500D 机身只读，请在相机上拨）")
                } else if (!canShutter) {
                    skipped.add("快门（${programShort(mode)} 档由机身决定）")
                } else {
                    driver.setShutterSeconds(st.shutterSeconds.toDouble()).onFailure { errs.add("快门:${it.message}") }
                    delay(60)
                }
                if (!canEv) {
                    skipped.add("EV（M 档无效）")
                } else if (curEv == Math.round(st.exposureComp * 1000)) {
                    skipped.add("曝光补偿（已是 ${fmtSigned(st.exposureComp)}）")
                } else {
                    driver.setExposureCompensation(st.exposureComp.toDouble()).onFailure { errs.add("EV:${it.message}") }
                    delay(60)
                }
                if (curWb == st.whiteBalance) {
                    skipped.add("白平衡（已是 0x%04X）".format(st.whiteBalance))
                } else {
                    driver.setWhiteBalance(st.whiteBalance).onFailure { errs.add("WB:${it.message}") }
                    delay(60)
                }
                if (curPc == st.picCtrlItem) {
                    skipped.add("优化校准（已是 #${st.picCtrlItem}）")
                } else {
                    driver.setActivePictureControl(st.picCtrlItem).onFailure { errs.add("PC:${it.message}") }
                }
            } finally {
                driver.autoResumeLiveView = true
                if (wasLive) {
                    delay(150)
                    startLiveView()
                }
            }
            // 写完后把机身真实值读回来，UI 显示的就是机身状态，不是我们以为的状态
            runCatching { loadShootingParams() }

            // 7 类参数 = 程序/ISO/光圈/快门/EV/WB/PC；跳过的既不算成功也不算失败
            val attempted = 7 - skipped.size
            val okCount = (attempted - errs.size).coerceAtLeast(0)
            val summary = buildString {
                append(
                    if (errs.isEmpty()) "参数已下发（$okCount 项写入，${skipped.size} 项跳过）"
                    else "部分参数失败：" + errs.joinToString("；"),
                )
                if (skipped.isNotEmpty()) append("\n跳过：").append(skipped.joinToString("、"))
            }
            _ui.value = _ui.value.copy(
                toast = if (errs.isEmpty()) "参数已下发到机身" else "部分参数失败：${errs.joinToString("；")}",
                captureMsg = summary,
            )
            HexLog.info("参数下发", if (errs.isEmpty()) "全部成功（跳过 ${skipped.size} 项）" else errs.joinToString(" | "))
        }
    }

    private fun programShort(p: Int): String = when (p) {
        1 -> "M"
        2 -> "P"
        3 -> "A"
        4 -> "S"
        else -> "当前"
    }

    /** 属性值小端 u16 解码（读回比对用，读不到返回 null 表示未知）。 */
    private fun leU16(b: ByteArray?): Int? =
        if (b != null && b.size >= 2) (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) else null

    /** 属性值小端 i16 解码（曝光补偿是带符号的）。 */
    private fun leI16(b: ByteArray?): Int? {
        if (b == null || b.size < 2) return null
        val raw = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun fmtOneDecimal(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

    private fun fmtSigned(v: Float): String = (if (v > 0f) "+" else "") + fmtOneDecimal(v)

    fun setIso(v: Int) { _ui.value = _ui.value.copy(iso = v) }
    fun setAperture(v: Float) { _ui.value = _ui.value.copy(aperture = v) }
    fun setShutterSeconds(v: Float) { _ui.value = _ui.value.copy(shutterSeconds = v) }
    fun setExposureComp(v: Float) { _ui.value = _ui.value.copy(exposureComp = v) }
    fun setExposureProgram(v: Int) { _ui.value = _ui.value.copy(exposureProgram = v) }

    /**
     * 选档位后立刻把 `0x500E` 发到机身（不用再点「下发」）。
     *
     * 只发档位这一个属性：切档会改变机身对其它参数的控制权，把旧的光圈/快门一起写
     * 反而会被拒（真机 `0x500E` 报 AccessDenied 的那次就是这么来的）。
     */
    fun applyExposureProgram(v: Int) {
        _ui.value = _ui.value.copy(exposureProgram = v)
        if (!driver.isOpen) return
        // 机身自报 0x500E 只读（Z30 实测如此）：拨档只能在相机上做，这里只更新界面预期
        if (Ptp.DPC_EXPOSURE_PROGRAM_MODE in _ui.value.readOnlyProps) {
            _ui.value = _ui.value.copy(
                toast = "机身不允许远程拨档，请在 Z30 上转模式盘（界面已按 ${programShort(v)} 档显示）",
            )
            HexLog.warn("曝光程序", "0x500E 是只读属性，跳过写入（真机 0x1014 描述符结论）")
            return
        }
        viewModelScope.launch {
            val wasLive = _ui.value.liveViewActive
            if (wasLive) stopLiveViewAwait()
            val r = driver.setExposureProgram(v)
            // 档位变了，机身重新接管的光圈/快门要读回来才对得上现实
            runCatching { loadShootingParams() }
            if (wasLive) {
                driver.waitUntilDeviceReady(3000)
                startLiveView()
            }
            val name = programShort(v)
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "已切到 $name 档" else "切 $name 档失败：${r.exceptionOrNull()?.message}",
            )
            HexLog.info("曝光程序", if (r.isSuccess) "0x500E = $v ($name) OK" else "0x500E = $v 失败")
        }
    }
    fun setWhiteBalance(v: Int) { _ui.value = _ui.value.copy(whiteBalance = v) }
    fun setPicCtrlItem(v: Int) { _ui.value = _ui.value.copy(picCtrlItem = v) }

    // ---------------- 拍摄 / 录像 ----------------

    /**
     * 从机身存储卡直接取最新一张，显示到「刚拍的这一张」。
     *
     * 为什么需要这个按钮：真机日志（ptp-20261027(2).log）里整段会话 **0 次 0x9207**，
     * 说明用户是用相机机身上的快门按键拍的 —— 那种照片不会走 `CaptureToSdram`，
     * App 侧的 [capturePhoto] 根本不会被触发，`lastCapture` 永远是空。
     * 之前把它当成「回传失败」来修，方向就错了：照片在卡里，只是没人去取。
     *
     * 做法：停取景 → 读每个存储卷的对象句柄表尾部几个 → 取最新且能下载的那个 → 解码 → 恢复取景。
     */
    fun loadNewestFromCamera() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.capturing) return
        viewModelScope.launch {
            val wasLive = _ui.value.liveViewActive
            _ui.value = _ui.value.copy(capturing = true, captureMsg = "正在从机身取最新一张…")
            if (wasLive) stopLiveViewAwait()
            driver.waitUntilDeviceReady(4000)
            val r = driver.downloadNewestObject(tries = 4, timeoutMs = 60_000)
            val data = r.getOrNull()
            if (data == null) {
                _ui.value = _ui.value.copy(
                    capturing = false,
                    captureMsg = "机身存储卡里没取到可用的照片：${r.exceptionOrNull()?.message ?: "空数据"}",
                    toast = "没取到照片",
                )
                if (wasLive) {
                    driver.waitUntilDeviceReady(3000)
                    startLiveView()
                }
                return@launch
            }
            val bmp = withContext(Dispatchers.Default) { decodeJpegLoose(data) }
            if (bmp == null) {
                _ui.value = _ui.value.copy(
                    capturing = false,
                    captureMsg = "取回 %.1f MB 但不是可解码的 JPEG（前 4 字节 %s）".format(
                        data.size / 1024.0 / 1024.0,
                        data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    ),
                    toast = "解码失败",
                )
            } else {
                _ui.value = _ui.value.copy(
                    capturing = false,
                    lastCapture = bmp,
                    lastCaptureHandle = -1L,
                    captureMsg = "已取回机身最新一张 · %d×%d · %.1f MB".format(
                        bmp.width, bmp.height, data.size / 1024.0 / 1024.0,
                    ),
                    toast = "已取回机身最新一张",
                )
                HexLog.info("拍摄", "从存储卡取回最新一张：${data.size}B，解码 ${bmp.width}×${bmp.height}")
            }
            lastCaptureDirty = true
            if (wasLive) {
                driver.waitUntilDeviceReady(4000)
                startLiveView()
            }
        }
    }

    /** 宽松解码：先当整块 JPEG 解，失败再从二进制里找 `FF D8` 起点重解一次。 */
    private fun decodeJpegLoose(data: ByteArray): Bitmap? {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }
            .getOrNull()?.let { return it }
        return runCatching {
            var start = -1
            var i = 0
            while (i < data.size - 1) {
                if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                    start = i; break
                }
                i++
            }
            if (start < 0) return@runCatching null
            android.graphics.BitmapFactory.decodeByteArray(data, start, data.size - start)
        }.getOrNull()
    }

    /** 拍一张：停取景 → 0x9207 → 取对象 → 解码预览 → 恢复取景。 */
    fun capturePhoto() {
        if (!driver.isOpen || _ui.value.capturing) {
            if (!driver.isOpen) _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        val wasLive = _ui.value.liveViewActive
        viewModelScope.launch {
            _ui.value = _ui.value.copy(capturing = true, captureMsg = "正在拍摄…")
            // 必须等 0x9202 真正完成再发 0x9207：之前是 fire-and-forget，
            // EndLiveView 还在飞就拍，机身判定「正在切模式」，事件流里拿不到 0xC101。
            if (wasLive) stopLiveViewAwait()
            driver.waitUntilDeviceReady(6000)
            val handleR = driver.captureToSdram()
            val handle = handleR.getOrElse {
                _ui.value = _ui.value.copy(capturing = false, captureMsg = "拍摄失败：${it.message}", toast = "拍摄失败")
                if (wasLive) startLiveView()
                return@launch
            }
            _ui.value = _ui.value.copy(captureMsg = "已拍摄，正在回传…")
            // 回传三级兜底：事件句柄 → 固定 SDRAM 句柄 0xFFFF0001 → 存储卡尾部最新对象
            var dl = driver.downloadCaptured(handle, timeoutMs = 60_000)
            var usedHandle = handle
            if (dl.getOrNull() == null && handle != 0xFFFF0001L) {
                // 有些机身不用 0xC101，SDRAM 里永远只有编号 1 的对象
                HexLog.warn("拍摄", "句柄 0x%08X 下载失败，改试固定 SDRAM 句柄 0xFFFF0001".format(handle))
                val fixed = driver.downloadCaptured(0xFFFF0001L, timeoutMs = 30_000)
                if (fixed.getOrNull() != null) {
                    dl = fixed
                    usedHandle = 0xFFFF0001L
                }
            }
            if (dl.getOrNull() == null) {
                // 事件流没给出真句柄（0xC101 丢事件）时，改用「存储卡尾部最新对象」兜底
                HexLog.warn("拍摄", "句柄 0x%08X 下载失败，尝试存储卡尾部兜底".format(handle))
                _ui.value = _ui.value.copy(captureMsg = "回传失败，正在从存储卡兜底读取…")
                val fb = driver.downloadNewestObject(tries = 4, timeoutMs = 60_000)
                if (fb.getOrNull() != null) {
                    dl = fb
                    usedHandle = -1L
                }
            }
            val data = dl.getOrNull()
            if (data == null || data.isEmpty()) {
                _ui.value = _ui.value.copy(
                    capturing = false,
                    captureMsg = "拍摄成功但回传失败（句柄 0x%08X）：%s".format(
                        handle, dl.exceptionOrNull()?.message ?: "空数据",
                    ),
                    toast = "回传失败",
                )
            } else {
                // 解码放到 Default；先按原样解，失败再从二进制里抠 FFD8..FFD9 解一次。
                // 机身在 GetObject 里偶尔会带上几字节 PTP 头，直接 decodeByteArray 会返回 null，
                // 表现为「拍摄完成但图是空白」——这才是之前那张图不显示的直接原因之一。
                val bmp = withContext(Dispatchers.Default) {
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }
                        .getOrNull() ?: runCatching {
                        var start = -1
                        var i = 0
                        while (i < data.size - 1) {
                            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                                start = i; break
                            }
                            i++
                        }
                        if (start < 0) return@runCatching null
                        android.graphics.BitmapFactory.decodeByteArray(data, start, data.size - start)
                    }.getOrNull()
                }
                if (bmp == null) {
                    _ui.value = _ui.value.copy(
                        capturing = false,
                        captureMsg = "回传了 %.1f MB 但不是可解码的 JPEG（前 4 字节 %s）".format(
                            data.size / 1024.0 / 1024.0,
                            data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                        ),
                        toast = "解码失败",
                    )
                    HexLog.warn("拍摄", "解码失败，句柄 0x%08X 回传 ${data.size}B".format(handle))
                } else {
                    _ui.value = _ui.value.copy(
                        capturing = false,
                        lastCapture = bmp,
                        lastCaptureHandle = usedHandle,
                        captureMsg = "拍摄完成 · %d×%d · %.1f MB".format(
                            bmp.width, bmp.height, data.size / 1024.0 / 1024.0,
                        ),
                        toast = "拍摄完成",
                    )
                    HexLog.info("拍摄", "句柄 0x%08X 回传 ${data.size}B，解码 ${bmp.width}×${bmp.height}".format(handle))
                }
                // 让相册页下次进入时能刷到这张（否则用户会以为没存上）
                lastCaptureDirty = true
                // 设置里打开「拍完自动存手机」时，顺手写进系统相册
                if (_ui.value.settings.autoSaveCapture) saveLastCaptureToPhone()
            }
            // 恢复取景前先等机身把文件落盘收尾，否则 0x9201 会被 0x200F 挡掉
            if (wasLive) {
                driver.waitUntilDeviceReady(4000)
                startLiveView()
            }
        }
    }

    /** 把上一次拍摄的照片存进手机相册（MediaStore，无需存储权限）。 */    fun saveLastCaptureToPhone() {
        val bmp = _ui.value.lastCapture
        if (bmp == null) {
            _ui.value = _ui.value.copy(toast = "还没有可保存的照片")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val name = "ZCloudAI_${System.currentTimeMillis()}.jpg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/ZCloudAI",
                    )
                }
                val uri = getApplication<Application>().contentResolver
                    .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            _ui.value = _ui.value.copy(toast = if (ok) "已保存到相册/Pictures/ZCloudAI" else "保存失败")
        }
    }

    fun toggleMovieRecording() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        viewModelScope.launch {
            if (_ui.value.movieRecording) {
                val r = driver.endMovieRecording()
                _ui.value = _ui.value.copy(
                    movieRecording = false,
                    captureMsg = if (r.isSuccess) "录像已停止" else "停止失败：${r.exceptionOrNull()?.message}",
                )
            } else {
                val r = driver.startMovieRecording()
                _ui.value = _ui.value.copy(
                    movieRecording = r.isSuccess,
                    captureMsg = if (r.isSuccess) "录像中…" else "开始失败：${r.exceptionOrNull()?.message}",
                )
            }
        }
    }

    fun triggerAutoFocusNow() {
        if (!driver.isOpen) return
        viewModelScope.launch {
            val r = driver.afDrive()
            _ui.value = _ui.value.copy(
                toast = if (r.isSuccess) "AF 已触发" else "AF 失败：${r.exceptionOrNull()?.message}",
            )
        }
    }

    fun moveFocus(step: Int) {
        if (!driver.isOpen) return
        viewModelScope.launch { driver.manualFocus(step) }
    }

    /** 把刚拍的这张照片直接当成 AI 还原的参考图。 */
    fun useLastCaptureAsReference() {
        val bmp = _ui.value.lastCapture
        if (bmp == null) {
            _ui.value = _ui.value.copy(toast = "还没有可用的照片")
            return
        }
        viewModelScope.launch {
            val feats = withContext(Dispatchers.Default) { ImageFeatureExtractor.extract(bmp) }
            _ui.value = _ui.value.copy(
                referenceBitmap = bmp,
                features = feats,
                selectedTab = 0,
                toast = "已设为参考图，去「AI 炼丹炉」还原影调",
            )
            renderPreview()
        }
    }

    // ---------------- 相册（照片管理） ----------------

    fun refreshGallery() {
        if (!driver.isOpen) {
            _ui.value = _ui.value.copy(toast = "请先连接 Z30")
            return
        }
        if (_ui.value.galleryLoading) return
        viewModelScope.launch {
            val limit = _ui.value.settings.galleryLimit
            _ui.value = _ui.value.copy(
                galleryLoading = true,
                galleryProgress = 0,
                downloadMsg = "正在读取最近 $limit 张…",
            )
            val r = driver.listStorage(limit = limit) { n ->
                _ui.value = _ui.value.copy(galleryProgress = n)
            }
            r.onSuccess { snap ->
                _ui.value = _ui.value.copy(
                    galleryVolumes = snap.volumes,
                    galleryItems = snap.items,
                    galleryLoading = false,
                    thumbnails = emptyMap(),
                    downloadMsg = "最近 ${snap.items.size} 张 · 照片 ${snap.photoCount} / 视频 ${snap.videoCount}",
                )
                loadThumbnails(snap.items)
            }.onFailure { t ->
                _ui.value = _ui.value.copy(
                    galleryLoading = false,
                    downloadMsg = "读取失败：${t.message}",
                    toast = "相册读取失败",
                )
            }
        }
    }

    /**
     * 批量拉缩略图。
     *
     * 每张都 `copy()` 一次会触发整棵树重组，100 张就是 100 次全量刷新，手机上肉眼可见地卡。
     * 这里攒够 5 张再刷一次 UI，既能看到进度条式增长，又不至于把主线程挤爆。
     */
    private fun loadThumbnails(items: List<CameraStorage.Item>) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = HashMap<Long, ByteArray>(_ui.value.thumbnails)
            var sinceFlush = 0
            for (it in items) {
                if (map.containsKey(it.handle)) continue
                val bytes = driver.getThumb(it.handle).getOrNull()
                if (bytes == null || bytes.isEmpty()) continue
                map[it.handle] = bytes
                sinceFlush++
                if (sinceFlush >= 5) {
                    sinceFlush = 0
                    _ui.value = _ui.value.copy(thumbnails = HashMap(map))
                }
            }
            _ui.value = _ui.value.copy(thumbnails = HashMap(map))
        }
    }

    /** 下载一张到手机相册。 */
    fun downloadItem(item: CameraStorage.Item) {
        if (!driver.isOpen) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(downloadingHandle = item.handle, downloadMsg = "正在下载 ${item.filename}…")
            val bytes = driver.getObject(item.handle, timeoutMs = 300_000).getOrNull()
            if (bytes == null) {
                _ui.value = _ui.value.copy(downloadingHandle = 0L, downloadMsg = "下载失败：${item.filename}", toast = "下载失败")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { saveToPhoneGallery(item, bytes) }
            _ui.value = _ui.value.copy(
                downloadingHandle = 0L,
                downloadMsg = if (ok) "已保存 ${item.filename}（%.1f MB）".format(bytes.size / 1024.0 / 1024.0)
                else "保存失败：${item.filename}",
                toast = if (ok) "已保存到相册" else "保存失败",
            )
        }
    }

    private fun saveToPhoneGallery(item: CameraStorage.Item, bytes: ByteArray): Boolean = runCatching {
        val cr = getApplication<Application>().contentResolver
        val isVideo = item.isVideo
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, item.filename)
            put(
                android.provider.MediaStore.MediaColumns.MIME_TYPE,
                if (isVideo) (if (item.filename.lowercase().endsWith(".mov")) "video/quicktime" else "video/mp4")
                else "image/jpeg",
            )
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES) +
                    "/ZCloudAI",
            )
        }
        val collection = if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = cr.insert(collection, values) ?: return false
        cr.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

    fun deleteItem(item: CameraStorage.Item) {
        if (!driver.isOpen) return
        viewModelScope.launch {
            val r = driver.deleteObject(item.handle)
            if (r.isSuccess) {
                _ui.value = _ui.value.copy(
                    galleryItems = _ui.value.galleryItems.filterNot { it.handle == item.handle },
                    downloadMsg = "已删除 ${item.filename}",
                    toast = "已从机身删除",
                )
            } else {
                _ui.value = _ui.value.copy(toast = "删除失败：${r.exceptionOrNull()?.message}")
            }
        }
    }

    fun setGalleryFilter(f: Int) {
        _ui.value = _ui.value.copy(galleryFilter = f)
    }

    // ---------------- 配方快传 ----------------

    /** 生成当前配方的快传码。 */
    fun generateShareCode() {
        val code = RecipeShareCode.encode(_ui.value.recipe)
        _ui.value = _ui.value.copy(shareCode = code, toast = "快传码已生成（${code.length} 字符）")
        HexLog.info("配方快传", "已生成 ${code.length} 字符")
    }

    fun setShareCodeInput(text: String) {
        _ui.value = _ui.value.copy(shareCodeInput = text)
    }

    /** 复制快传码到系统剪贴板。 */
    fun copyShareCodeToClipboard() {
        val code = _ui.value.shareCode
        if (code.isBlank()) return
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Z-Cloud AI 配方快传码", code))
        _ui.value = _ui.value.copy(toast = "快传码已复制，粘贴到微信/评论区发给朋友")
    }

    /** 调系统分享面板把快传码发出去。 */
    fun shareShareCode() {
        val code = _ui.value.shareCode
        if (code.isBlank()) return
        val r = _ui.value.recipe
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                android.content.Intent.EXTRA_TEXT,
                "【Z-Cloud AI 配方】${r.name}\n${r.styleAnalysis}\n\n$code",
            )
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Z-Cloud AI 优化校准配方")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            getApplication<Application>().startActivity(
                android.content.Intent.createChooser(send, "分享配方快传码").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { _ui.value = _ui.value.copy(toast = "没有可用的分享目标") }
    }

    /** 从粘贴的文本还原配方。 */
    fun applyShareCode() {
        val r = RecipeShareCode.decode(_ui.value.shareCodeInput)
        if (r == null) {
            _ui.value = _ui.value.copy(toast = "无法识别这段快传码")
            return
        }
        _ui.value = _ui.value.copy(recipe = r, lastAnalysis = r.styleAnalysis, toast = "已导入配方：${r.name}")
        renderPreview()
    }

    fun consumeToast() {
        if (_ui.value.toast.isNotEmpty()) _ui.value = _ui.value.copy(toast = "")
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { driver.close() }
    }

    /** 判断曲线是否接近线性（用于 UI 提示）。 */
    fun isCurveLinear(r: Recipe): Boolean {
        val lut = r.lut
        for (i in 0..255 step 8) if (abs(lut[i] - i) > 4) return false
        return true
    }
}
