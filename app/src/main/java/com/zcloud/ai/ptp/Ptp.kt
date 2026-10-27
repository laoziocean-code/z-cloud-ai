package com.zcloud.ai.ptp

/**
 * PTP / ISO 15740 + Nikon 私有操作码常量。
 *
 * ── 权威来源（重要，别再改错）──────────────────────────────
 * libgphoto2 `ptp.h` 第 575-660 行的 Nikon opcode 表：
 *   0x90C0 InitiateCaptureRecInSdram  (1 param, no data)
 *   0x90C1 AfDrive                    (no params)
 *   0x90C2 ChangeCameraMode           (1 param)
 *   0x90C7 GetEvent                   (0 params, data in)
 *   0x90C8 DeviceReady                (no params)
 *   0x90CC GetPictCtrlData            (2 params, data in)   ← 读优化校准
 *   0x90CD SetPictCtrlData            (2 params, data out)  ← 写优化校准
 *   0x90CE DelCstPicCtrl              (1 param, no data)    ← 删自定义槽位
 *   0x90CF GetPicCtrlCapability       (1 param, data in)
 *   0x9200 GetPreviewImg / 0x9201 StartLiveView
 *   0x9202 EndLiveView  / 0x9203 GetLiveViewImg / 0x9204 MfDrive
 *
 * 调用约定另有第三方印证：digiCamControl `NikonBase.cs`
 *   `ExecuteReadDataEx(0x90CC, slotnum, 0)` —— 确凿的两个参数 (slot, 0)。
 *
 * ⚠ 旧版本这里写的是 0x90C0/0x90C1/0x90C2，那三个根本不是优化校准，
 *   是「拍进 SDRAM / 自动对焦 / 切换相机模式」，所以真机上永远读不到槽位。
 */
object Ptp {
    const val NIKON_VID = 0x04B0

    /** Z30 的 PID（ZENCHE `CameraProfile` 明确列出）。仅用于优先匹配，不作为硬性条件。 */
    const val NIKON_Z30_PID = 0x0452

    // ---- 容器类型 ----
    const val TYPE_COMMAND = 0x0001
    const val TYPE_DATA = 0x0002
    const val TYPE_RESPONSE = 0x0003
    const val TYPE_EVENT = 0x0004

    // ---- 响应码 ----
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_INCOMPLETE_TRANSFER = 0x2007
    const val RC_INVALID_STORAGE_ID = 0x2008
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RC_ACCESS_DENIED = 0x200F
    const val RC_STORE_FULL = 0x2010
    const val RC_STORE_NOT_AVAILABLE = 0x2011
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_INVALID_PARENT_OBJECT = 0x201A
    /**
     * 0x201D InvalidParameter —— 参数个数/取值不合法。真机实测 0x90CD 大量返回它。
     */
    const val RC_INVALID_PARAMETER = 0x201D

    /**
     * 本地伪响应码（非 PTP 协议值，仅用于把「空槽位」和「读取失败」区分开）。
     * 取 0xF001 这种绝不会出现在协议里的值，避免和真实响应码混淆。
     */
    const val RC_LOCAL_EMPTY_SLOT = 0xF001
    const val RC_SESSION_ALREADY_OPEN = 0x201E

    // ---- 标准操作 ----
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_DELETE_OBJECT = 0x100B
    const val OP_SEND_OBJECT_INFO = 0x100C
    const val OP_SEND_OBJECT = 0x100D
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016

    // ---- 尼康私有：优化校准（真正可用的那三个） ----
    /** GetPictCtrlData：params = (slot, 0)，data-in = 84/85 字节载荷 */
    const val OP_NIKON_GET_PICCTRL = 0x90CC
    /** SetPictCtrlData：params = (slot, 0)，data-out = 84 字节载荷 */
    const val OP_NIKON_SET_PICCTRL = 0x90CD
    /** DelCstPicCtrl：params = (slot)，无数据 */
    const val OP_NIKON_DEL_CUSTOM_PICCTRL = 0x90CE
    /** GetPicCtrlCapability：params = (slot)，data-in */
    const val OP_NIKON_GET_PICCTRL_CAPS = 0x90CF

    /** 旧版误用的三个（保留常量仅为兼容旧日志，勿再调用） */
    @Deprecated("0x90C0 是 InitiateCaptureRecInSdram，不是读优化校准")
    const val OP_NIKON_LEGACY_0x90C0 = 0x90C0
    @Deprecated("0x90C1 是 AfDrive，不是写优化校准")
    const val OP_NIKON_LEGACY_0x90C1 = 0x90C1
    @Deprecated("0x90C2 是 ChangeCameraMode，不是删槽位")
    const val OP_NIKON_LEGACY_0x90C2 = 0x90C2

    // ---- 尼康私有：色调曲线专用读写对（libgphoto2 ptp.h:586-587，各 1 参数） ----
    /** CurveDownload：params = (1)，data-in = 曲线数据。尼康真正的「读曲线」。 */
    const val OP_NIKON_CURVE_DOWNLOAD = 0x90C5
    /** CurveUpload：params = (1)，data-out = 曲线数据。尼康真正的「写曲线」。 */
    const val OP_NIKON_CURVE_UPLOAD = 0x90C6

    // ---- 尼康私有：应用模式 ----
    /**
     * ChangeApplicationMode（libgphoto2 ptp.h:653，1 参数）。
     * 0 = 普通拍摄模式，1 = PC/应用模式。部分机身只有在应用模式下才给写 PC 数据。
     */
    const val OP_NIKON_CHANGE_APPLICATION_MODE = 0x9435

    // ---- 尼康私有：会话/事件 ----
    const val OP_NIKON_GET_EVENT = 0x90C7
    const val OP_NIKON_DEVICE_READY = 0x90C8
    const val OP_NIKON_CHANGE_CAMERA_MODE = 0x90C2
    const val OP_NIKON_GET_LARGE_THUMB = 0x90C4
    const val OP_NIKON_GET_VENDOR_PROP_CODES = 0x90CA

    // ---- 尼康私有：实时取景 ----
    const val OP_NIKON_GET_PREVIEW_IMG = 0x9200
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMG = 0x9203
    const val OP_NIKON_MF_DRIVE = 0x9204
    const val OP_NIKON_CAPTURE_TO_MEDIA = 0x9207

    // ---- 尼康私有：拍摄 / 摄像 / 对焦 ----
    /** CaptureToSdram：params = (0xFFFFFFFF, 1)，拍进机身 SDRAM 再取回 */
    const val OP_NIKON_CAPTURE_SDRAM = 0x9207
    /** TerminateCapture：params = (0, 0)，用于 B 门结束曝光 */
    const val OP_NIKON_TERMINATE_CAPTURE = 0x920C
    const val OP_NIKON_START_MOVIE = 0x920A
    const val OP_NIKON_END_MOVIE = 0x920B
    /** AfDrive：无参数，触发一次自动对焦 */
    const val OP_NIKON_AF_DRIVE = 0x90C1
    const val OP_NIKON_MF_DRIVE_9204 = 0x9204

    // ---- 事件码 ----
    const val EC_NIKON_OBJECT_ADDED_IN_SDRAM = 0xC101

    // ---- 后端兼容族（老固件/其他机型，UI 可切） ----
    /** 单参数模式：部分固件 0x90CC 只吃 1 个参数 */
    const val OP_NIKON_GET_PICCTRL_ALT = 0x90CC
    const val OP_NIKON_SET_PICCTRL_ALT = 0x90CD
    const val OP_NIKON_DEL_CUSTOM_PICCTRL_ALT = 0x90CE

    // ---- 属性 ----
    const val DPC_NIKON_ACTIVE_PICCTRL_ITEM = 0xD200
    const val DPC_NIKON_CHANGE_PICCTRL_ITEM = 0xD201

    /** 标准 DevicePropCode */
    const val DPC_BATTERY_LEVEL = 0x5001
    const val DPC_WHITE_BALANCE = 0x5005
    const val DPC_F_NUMBER = 0x5007
    const val DPC_FOCUS_MODE = 0x500A
    const val DPC_EXPOSURE_METERING_MODE = 0x500B
    const val DPC_EXPOSURE_BIAS_COMPENSATION = 0x5010
    const val DPC_EXPOSURE_TIME = 0x500D
    const val DPC_EXPOSURE_PROGRAM_MODE = 0x500E
    const val DPC_ISO = 0x500F
    const val DPC_IMAGE_SIZE = 0x5003

    /** 尼康私有 DevicePropCode */
    const val DPC_NIKON_EXPOSURE_TIME = 0xD100
    const val DPC_NIKON_MOVIE_EXPOSURE_TIME = 0xD1A8
    const val DPC_NIKON_LIVE_VIEW_FOCUS_MODE = 0xD061
    const val DPC_NIKON_AF_LOCK = 0xD0AA

    // ---- 优化校准槽位 ----
    /**
     * C-1 .. C-9 自定义槽位 = **0x00C9 .. 0x00D1**。
     *
     * 别用 0x00CB 起步 —— 那是错的。真机 `0x90CF GetPicCtrlCapability` 的实测边界
     * （ptp-20261027.log 会话 12:38:38 的审计输出）：
     * ```
     *   0x00C9 → 支持     0x00D0 → 支持
     *   0x00CA → 支持     0x00D1 → 支持
     *   0x00CB → 支持     0x00D2 → InvalidParameter
     *   0x00CC → 支持     0x00D3 → InvalidParameter
     *   0x00CD → 支持     小结：支持 9 项
     *   0x00CE → 支持
     *   0x00CF → 支持
     * ```
     * 正好 9 项、且端点 0xC9/0xD1 对齐。用 0xCB 起步会整体偏 2：C-1 指到真正的 C-3，
     * C-8/C-9 指到根本不存在的 0xD2/0xD3（固定回 0x201D InvalidParameter）。
     *
     * 用户自己的三个富士配方也印证了这一点：它们落在 0xC9 / 0xCA / 0xCB，
     * 机身上就是 C-1 / C-2 / C-3。
     */
    val CUSTOM_SLOTS: IntArray = IntArray(9) { 0x00C9 + it }
    /** 标准槽位 1 .. 6（AUTO/STANDARD/NEUTRAL/VIVID/MONOCHROME/PORTRAIT…） */
    val STANDARD_SLOTS: IntArray = intArrayOf(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006)

    const val CUSTOM_SLOT_FIRST = 0x00C9
    const val CUSTOM_SLOT_LAST = 0x00D1

    fun isCustomSlot(slot: Int) = slot in CUSTOM_SLOT_FIRST..CUSTOM_SLOT_LAST
    fun customSlotIndex(slot: Int) = if (isCustomSlot(slot)) slot - CUSTOM_SLOT_FIRST + 1 else 0
    fun customSlotFromIndex(indexFrom1: Int) = CUSTOM_SLOT_FIRST + (indexFrom1 - 1).coerceIn(0, 8)
    fun slotLabel(slot: Int): String =
        if (isCustomSlot(slot)) "C-${customSlotIndex(slot)}" else "#$slot"

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "GeneralError"
        RC_SESSION_NOT_OPEN -> "SessionNotOpen"
        RC_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RC_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        RC_INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        RC_INVALID_STORAGE_ID -> "InvalidStorageID"
        RC_INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        RC_DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        RC_ACCESS_DENIED -> "AccessDenied"
        RC_STORE_FULL -> "StoreFull"
        RC_STORE_NOT_AVAILABLE -> "StoreNotAvailable"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_INVALID_PARENT_OBJECT -> "InvalidParentObject"
        RC_INVALID_PARAMETER -> "InvalidParameter"
        RC_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        RC_LOCAL_EMPTY_SLOT -> "EmptySlot"
        else -> "0x%04X".format(code)
    }
}
