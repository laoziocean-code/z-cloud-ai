# Z-Cloud AI v1.6.2 · 修「刚拍的这张」与「参数下发无效」

安装包：`Z-Cloud-AI-v1.6.2.apk`（11,842,743 字节）
versionCode **13** / versionName **1.6.2** / minSdk 26 / targetSdk 34
SHA-256：`ebd010f6b8fcdd1c914d8978b429819b9f29b47373685103aaa12ea021713bce`

---

## 一、你这轮报的两个问题，根因分别是什么

### 1. 「曝光参数下发没用」→ `SetDeviceProp 0x500E 失败: AccessDenied`

你截图里那句话是准的：**通道是通的，只是这一个属性被拒**。证据是同一批下发里光圈从 F5.6 变成了 F11、ISO 变成了 800 —— 后面几个都写成功了，只有第一个 `0x500E`（曝光程序）失败。

对照 ZENCHE 的实现（`ref/zenche/PtpCamera.java:790` `setParameter()`），我们缺了三件事：

| 缺的东西 | ZENCHE 怎么做 | 我们之前 |
| --- | --- | --- |
| 停实时取景 | 写属性前先 `stopLiveView()`，写完 `finally` 里恢复 | 取景开着就直接发 → 机身把控制面锁给取景流 → 判无权限 |
| 瞬时错误重试 | `0x200F`/`0x201C` 当瞬时错误，最多 5 次，退避 `200*(n+1) ms` | 发一次，失败立刻返回 |
| 按档位挑参数 | `canAdjustExposureParameter()`：M 才写快门、A/S 才写 EV | 7 个属性无脑全写 |

**v1.6.2 的改法**：
- `NikonPtpDriver.setDeviceProp()` 重写：先 `endLiveView()` → 最多 5 次（`0x200F`/`0x2019`/`0x201C`/DeviceBusy/InvalidObjectHandle 才重试，退避 `200*(n+1) ms`，每次前打一次 `DeviceReady`）→ `finally` 里恢复取景。
- 新增 `waitUntilDeviceReady(timeoutMs)`：对齐 ZENCHE 的 `waitUntilDeviceReady(6000)`。
- 新增 `autoResumeLiveView` 开关：批量下发时由外层统一停/统一恢复，不再 7 次停开取景。
- `applyShootingParams()` 重写：
  - 先 `stopLiveViewAwait()`（新增，见下）真正停掉取景；
  - 按档位跳过机身自己接管的参数（S 档不写光圈、P/A 档不写快门、M 档不写 EV），并把跳过项显示给用户；
  - 顺序改成 程序 → ISO → 光圈 → 快门 → EV → WB → PC，每条间隔 60 ms；
  - 结束后 `loadShootingParams()` 把机身真实值读回来 —— UI 显示的是机身状态，不是我们以为的状态。
- `LiveMonitorScreen` 的 M/P/A/S 档位点一下就直接发 `0x500E` 到机身（不用再点「下发」），并把「当前档位下由机身决定的参数」用灰字标出来、滑杆也置灰。
- 新增 `programShort()` 用于 toast 文案。

### 2. 「刚拍的这一张还是不显示」

三层原因，v1.6.1 修了两层半，这一版补完并加了兜底：

1. **`0x90C7 GetEvent` 格式**（v1.6.1 已修）：Nikon 返回的是 `u16 事件个数 + N × {u16 code, u32 param1}`，不是 PTP 标准容器。已按 libgphoto2 `ptp_unpack_Nikon_EC` + ZENCHE `findSdramObject`（真机跑通）对齐。
2. **停取景竞态**（这一版新修）：`stopLiveView()` 是 `viewModelScope.launch` 的 fire-and-forget，`0x9202` 还在飞的时候 `0x9207` 就发出去了，机身判定「正在切模式」，事件流里根本不会出现 `0xC101`。
   → 新增 `stopLiveViewAwait()`：`job?.cancelAndJoin()` + `driver.endLiveView()` + `delay(200)`，**确认停了才返回**；`capturePhoto()` 与 `applyShootingParams()` 都改用它，并在拍摄前补 `waitUntilDeviceReady(6000)`。
3. **句柄丢了没有后备**（这一版新修）：`0x9207` 成功后若事件流里没拿到 `0xC101`，手里只剩假句柄 `0xFFFF0001`，`GetObject` 只会回 `0x2009`。
   → 新增 `downloadNewestObject(tries=4)`：按「ObjectHandle 递增 ⇒ 最新的在尾部」这个 PTP 约定，直接从存储卷尾部的 4 个句柄里试下载。`capturePhoto()` 在 `downloadCaptured()` 失败后自动走这条兜底。
   → 拍摄结束、恢复取景前补 `waitUntilDeviceReady(4000)`，避免 `0x9201` 被挡。
4. **事件流原样落盘**（新增诊断）：`captureToSdram()` 首次拿到 `GetEvent` 就把原始字节 HexDump 进日志（标题「GetEvent 0x90C7 原始事件流」）。万一 Z30 的事件格式又和预期不同，你把日志发回来我一眼能看出来。

---

## 二、装法

```bash
adb install --user 0 -r "C:\Users\laoziocean\Desktop\Z30刷机\Z-Cloud-AI-v1.6.2.apk"
```
魅族必须带 `--user 0`，否则报 `INSTALL_FAILED_USER_RESTRICTED`。

## 三、测试顺序（关键是第 3 步要接着 Z30 导出日志）

1. 拔掉电脑数据线，只把 Z30 接到手机（相机「设定 → USB」选 **MTP/PTP**）。
2. 进「监看」页 → 开启取景 → 点 **M / P / A / S** 任意一档。应当立刻弹出「已切到 X 档」。
3. 拨光圈/ISO/EV → 点「下发」。期望：toast「参数已下发到机身」，且各项数值在 1 秒内被机身真实值覆盖（不会跳回原值）。
4. 点黄色快门圆钮拍一张。期望：「刚拍的这一张」出大图，右下角标分辨率，点图可全屏双指放大。
   - 如果还是空：**先别拔相机**，直接去「调试台 → 分享日志」，把 `ptp-YYYYMMDD.log` 发我。这一版日志里会有 `GetEvent 0x90C7 原始事件流`、`句柄 0x%08X 下载失败，尝试存储卡尾部兜底`、`兜底下载成功/兜底下载也没拿到` 三条关键记录。
5. 点大图 → 全屏 → 双指放大确认细节（这一版支持 1~6 倍缩放 + 双击回正）。

## 四、仍未经验证的点（诚实标注）

- `0x500E` 在「停取景 + 5 次重试」后是否一定成功 —— 依据是 ZENCHE 在 Z30 同代机身上真机跑通，本机未复测。
- `downloadNewestObject()` 的尾部句柄兜底：Z30 的 `GetObjectHandles` 是否把最新对象排在最后，是按 PTP 规范与 digiCamControl 的实践推断，本机未复测。这条只是兜底，不影响主路径。
- 档位切换后机身会重新接管光圈/快门，具体接管哪一项由固件决定；我们按 ZENCHE 的 `canAdjustExposureParameter` 表实现，个别档位可能有出入。

## 五、本轮改动的文件

| 文件 | 改动 |
| --- | --- |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ptp/NikonPtpDriver.kt` | `setDeviceProp` 重写（停取景+5 次重试+恢复）；新增 `waitUntilDeviceReady`、`isTransientPropError`、`autoResumeLiveView`、`downloadNewestObject`；`captureToSdram` 补 6s 等待就绪 + 首次事件流 HexDump + 落盘后再补读一次事件 |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ui/MainViewModel.kt` | 新增 `stopLiveViewAwait()`、`applyExposureProgram()`、`programShort()`；重写 `applyShootingParams()`；`capturePhoto()` 改 await 停取景 + 兜底下载 + 恢复取景前等就绪 |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ui/LiveMonitorScreen.kt` | 档位点击直发机身；按档位禁用光圈/快门/EV 滑杆并显示原因说明 |
| `ZCloudAI/app/build.gradle.kts` | versionCode 12→13，versionName 1.6.1→1.6.2 |

## 六、重打包命令

```bash
cd /c/Users/laoziocean/Desktop/Z30刷机/ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain -q
cp app/build/outputs/apk/release/app-release.apk ../Z-Cloud-AI-v1.6.2.apk
```
