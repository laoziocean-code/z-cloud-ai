# Z-Cloud AI v1.6.1 — 修好「刚拍的这一张」+ 大图查看

## 一句话结论

监看页拍完不显示，不是 UI 的问题，是**事件流的解析格式写错了** —— 导致每次都拿到一个假句柄，
相机当然回「这个对象不存在」。这一版修了三层，并把预览区改成大图可缩放。

---

## 一、根因（三层，逐层都会让图出不来）

### 1. Nikon `GetEvent (0x90C7)` 的返回格式不是 PTP 标准容器

我们原来按 PTP 容器流解析事件：

```
u32 Length | u16 Type | u16 Code | ...（我们现在用的，错的）
```

Nikon 实际返回的是：

```
u16 事件个数 | N × { u16 Code, u32 Param1 }   ← 每事件固定 6 字节
```

依据两处真实实现：

- libgphoto2 `ptp_unpack_Nikon_EC()`（`ptp-pack.c`，注释写明 Nikon 专用）
- ZENCHE `PtpCamera.findSdramObject()`（已在 Z30 真机跑通）

**后果链条**：解析错位 → 永远读不到 `0xC101 ObjectAddedInSDRAM` → `captureToSdram()` 走兜底
`0xFFFF0001` → `GetObject(0xFFFF0001)` 回 `0x2009 InvalidObjectHandle` → `lastCapture = null` →
界面显示「还没有拍摄记录」。

修复：`NikonPtpDriver.findSdramObject()` 重写为「先读 u16 个数，再按 6 字节步进」。

### 2. 拍完立刻下载会被拒（写卡窗口）

按下快门到文件真正可读之间有几十到几百毫秒。这段时间 `GetObject` 一定回 `0x2009`，
只试一次就判失败，用户看到的就是「拍了但没存」。

修复：新增 `NikonPtpDriver.downloadCaptured(handle, timeoutMs)`：

- 最多 9 次，退避 `min(300ms << n, 4000ms)`，总预算 20 秒
- 每次重试前先 `DeviceReady` + `GetEvent`，若句柄变了就换新句柄（部分机型会替换临时句柄）
- 只对 `0x2009` 重试，其它错误立刻返回，不掩盖真问题

### 3. 解码失败被静默吞掉

原来只做一次 `BitmapFactory.decodeByteArray`，返回 null 就直接把「图」设成 null，
界面上和「没拍到」长得一模一样。

修复：一次解码失败后，再扫 `FF D8 … FF D9` 从中间截一段重解；
两次都失败才报错，并且把**前 4 个字节**和实际字节数打进 `captureMsg`，方便判断是协议头还是格式问题。

---

## 二、监看页「刚拍的这一张」改版

| 项目 | 旧 | 新 |
|---|---|---|
| 显示高度 | 固定 180dp，`ContentScale.Crop`（裁切） | **按原始宽高比铺满整宽**，`Fit` 不裁切 |
| 细节查看 | 没有 | **点图 / 点「全屏查看原图」→ 全屏，双指缩放 1×–6×、拖动、双击 2.2× 复位** |
| 信息 | 只有一张图 | 右下角标 `4032×3024 · 点开看细节`；全屏左上角实时显示 `4032×3024 220%` |
| 拍完状态 | `拍摄完成 · 12.3 MB` | `拍摄完成 · 4032×3024 · 12.3 MB`（带上真实分辨率） |
| 空态高度 | 80dp | 110dp（和大图时的占位感一致，不会跳） |

另外：拍完一张会给相册列表打「已过期」标记，下次进相册页自动重拉，
不用手动点刷新就能看到新照片。

---

## 三、改动的文件

| 文件 | 改动 |
|---|---|
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ptp/NikonPtpDriver.kt` | `findSdramObject()` 按 6 字节事件解析重写；新增 `downloadCaptured()` 退避重试 |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ui/MainViewModel.kt` | `capturePhoto()` 改用 `downloadCaptured()`；解码加 SOI 扫描兜底；失败时报字节数与首 4 字节；新增 `lastCaptureDirty` 与 `selectTab` 自动刷相册 |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ui/LiveMonitorScreen.kt` | `LastCaptureCard` 改大图；新增 `FullScreenPhoto`（Dialog + `detectTransformGestures`） |
| `ZCloudAI/app/build.gradle.kts` | `versionCode 11→12`、`versionName 1.6.0→1.6.1` |

---

## 四、验证

- `:app:compileReleaseKotlin` → exit 0，无警告无错误
- `:app:assembleRelease` → exit 0，11,826,359 字节
- `aapt2 dump badging` → `versionCode='12' versionName='1.6.1'`，sdk 26 / target 34
- `apksigner verify` → `Verifies`，v2 scheme true，1 signer
- dex 字符串核对（解包 `classes2.dex`）：`downloadCaptured` 4 处、`点开看细节` 1、`全屏查看原图` 1、`句柄刷新` 1、`还在写卡` 1 —— 新逻辑确实进包
- SHA-256：`1fdfc678febebc6ca5ba8e9b9dab24fedd92f7fb286096d662ad41050fc5e115`

**离线可验证的部分**（解析与重试逻辑）已用编译 + dex 核对确认；
**真机行为**（0xC101 句柄能不能拿到、大图比例对不对）需要你接上 Z30 拍一张确认。

---

## 五、装法

```powershell
adb install --user 0 -r "C:\Users\laoziocean\Desktop\Z30刷机\Z-Cloud-AI-v1.6.1.apk"
```

魅族必须带 `--user 0`，否则报 `INSTALL_FAILED_USER_RESTRICTED`。

## 六、测试建议顺序

1. 拔掉电脑数据线，只接 Z30（同一个 C 口不能既做 adb 又做 OTG 主机）
2. 相机「设定」→「USB」→ 选 **MTP/PTP**
3. App → 监看页 → 开启取景 → 按快门
4. 看 `captureMsg`：
   - `拍摄完成 · 4032×3024 · 12.3 MB` → 成功
   - `拍摄成功但回传失败（句柄 0x????????）：...回 0x2009...` → 事件还没解析对，把调试台日志发我
   - `回传了 X MB 但不是可解码的 JPEG（前 4 字节 ?? ?? ?? ??）` → 拿到的是别的对象，我按字节调
5. 点大图 → 双指放大到 200% 看对焦细节

---

## 七、还没验证的点（诚实说明）

- Z30 的 `0x90C7` 返回是否真的和 libgphoto2/ZENCHE 一致 —— 我没设备，只能按两份成熟实现来。
  若仍拿不到 `0xC101`，日志里会明确打印「未收到 0xC101 事件，回退到默认句柄」，那就是要换 `0x941C GetEventEx`。
- 全屏缩放的边界（拖到画外）没有做回弹，只用 `scale<=1` 时归零。够用，但不是成品级手感。
