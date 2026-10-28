# Z-Cloud AI v1.4.3 — 优化校准写入诊断重构

**APK**：`Z-Cloud-AI-v1.4.3.apk`（11,793,591 字节）
**SHA-256**：`2f501aedb960bd1938d2aab58e2dc4eeae2b7e01a2cdbbabf5bbab6e9086585a`
**versionCode 9 / versionName 1.4.3** · minSdk 26 · targetSdk 34 · v2 签名已验证

---

## 一、先说一件必须说清的事：C-1 的「Konica Ce」是被 v1.4.0 删掉的

你上一轮截图里 C-1 从 `Konica Ce` 变成「读取失败，读回 36B 全零」，这个不是固件问题，是 v1.4.0 的 bug。

v1.4.0 的「自动探测写入机制」把 `0x90CE DelCstPicCtrl`（删槽位）当成了无害的前置动作，跟其它 79 种组合一起跑。它跑完，那个槽位的预设就没了 —— 相机对空槽位回一小段全零空壳，所以读回来是 36 字节零。

**这一版已经把这条前置动作整条删掉了**，并在源码里留了注释说明为什么不能再加回来。破坏性操作不能混进自动探测，这是我的设计错误。

C-1 现在需要你重新刷一次才能恢复 —— 但它能不能刷进去，取决于下面这个问题先解决。

---

## 二、100 种组合全失败，说明方向错了

日志里每一行都是 `→ 0x201D`，混着 40 次 AccessDenied。这两个错误码的语义：

| 码 | 含义 | 说明什么 |
|---|---|---|
| `0x200F` AccessDenied | 权限不足 | 参数能过解析，但机身不让写 |
| `0x201D` InvalidParameter | 参数非法 | 参数形态本身就不被接受 |

问题在于：**尼康不会告诉你「缺什么权限」**。所以继续穷举参数形态 × 载荷形态，只是在同一个信息真空里多转几圈 —— 80 种和 100 种没区别。

真正缺的是「机身自己的说法」。这一版补上了。

---

## 三、这一版新增的三个按钮（AI 调色页 → 刷入按钮下方）

按「信息量 / 破坏性」排序，**请严格按 ①②③ 的顺序点**。

### ① 审计机身（纯只读，绝不动数据）

一次问机身四件事，全部是读操作：

1. **当前优化校准项**：读 `0xD200 ActivePicCtrlItem` 和 `0xD201 ChangePicCtrlItem`，看机身现在选中的是哪一项、可切换项有哪些。
2. **能力表**：对 `0x90CF GetPicCtrlCapability` 逐个问 `0x00C0 ~ 0x00DC` 共 29 个 item，看哪些是真支持、哪些是 AccessDenied。这一层能区分「不支持的 item」和「存在但空」——我们之前一直在瞎猜 C-1 是不是 `0x00CB`。
3. **尼康真正的曲线通道**：`0x90C5 CurveDownload`（1 参数，data-in）。libgphoto2 的头文件里 `0x90C5`/`0x90C6` 标注为 CurveDownload/CurveUpload —— 这是尼康专门的「色调曲线读写对」，跟我们一直在试的 `0x90CC/0x90CD`（GetPictCtrlData/SetPictCtrlData）是两套不同的东西。如果这条通，曲线根本不用走 PC 数据那条路。
4. **应用模式**：`0x9435 ChangeApplicationMode`（1 参数）。部分机身只在「PC/应用模式」下才放开写入权限。

结果会直接显示在按钮下方，同时进 Hex 日志。

### ② 无损回写（决定性判定，内容零变化）

把目标槽位**原样读出来、原样写回去**，一个字节都不改。

- 如果通了 → `0x90CD` 通道是开的，之前失败是载荷内容或目标槽位的问题，接着点「刷入」就行。
- 如果连原样写回都被拒 → `0x90CD` 被固件封着，再试任何参数形态都是浪费。必须走 ① 里发现的曲线通道或应用模式。

这一步的价值在于：它用一次操作把问题**切成两半**，而不是继续在 80 种组合里找。

### ③ 全组合盲探（80 种，最后手段）

保留，但降级为最后手段，且已剔除破坏性的删除动作。只有 ①② 都给不出方向时才点。

---

## 四、空槽位现在会正确显示

相机对没存过预设的槽位返回 36 字节全零，之前被当成「读取失败」。现在识别为 **「空槽位」**，并且 `isEmpty` 标记为真 —— 空槽位本来就是可以写入的目标，不是故障。

槽位状态对照：

| 显示 | 含义 |
|---|---|
| `Konica Ce` 之类的名字 | 有预设，可读 |
| **空槽位** | 机身明确回答「这里没东西」，正常、可写 |
| 参数不支持 | `0x201D`，固件不接受这个 item 号 |
| 读取失败 | 真异常 |

---

## 五、测试顺序（重要）

1. 装 v1.4.3。
2. 拔掉电脑数据线，只接 Z30（**同一个 USB-C 口不能既做 adb 又做 OTG 主机**，这是上一轮 `host_connected=false` 的原因）。
3. 相机「设定」→「USB」→ 选 **MTP/PTP**，不要选「相机控制」或「充电」。
4. 允许 USB 授权 → 等顶部显示「Z30 已连接 · 会话已建立」。
5. 目标槽位选 **C-2**（避开被清空的 C-1，减少变量）。
6. 点 **① 审计机身**，把结果截图或导出日志。
7. 点 **② 无损回写**，看是 ✅ 还是 ❌。
8. 无论结果如何，去调试台点「分享日志」把日志发我 —— 这一次的日志里会有 `0x90CF` 能力表、`0xD200` 当前项、`0x90C5` 应答、`0x9435` 应答，足够定位到底卡在哪一层。

---

## 六、如果 ② 是 ❌（0x90CD 被拒），我们已经有的备选路径

按可能性排序，等 ① 的日志回来再决定走哪条：

1. **`0x90C5`/`0x90C6` 曲线通道** —— 尼康专门的曲线读写对，参数只有 1 个，不涉及整个 84 字节 PC 数据块。
2. **`0x9435` 应用模式** —— 先切 PC 模式再写。
3. **`0x1016` 写 `0xD201 ChangePicCtrlItem` 属性** —— ZENCHE 走的就是属性路径（它切当前优化校准项用 `0xD200`，写用属性而非 vendor opcode）。这条路不碰 `0x90CD`。
4. **SD 卡 recipe 文件路径** —— 你工作区里 `work/out/RECIPE.NIC` 那套，是相机自己解析的路径，绕过 USB 写入。慢，但确定能用。

---

## 七、源码改动

| 文件 | 改动 |
|---|---|
| `ptp/Ptp.kt` | 新增 `OP_NIKON_CURVE_DOWNLOAD=0x90C5`、`OP_NIKON_CURVE_UPLOAD=0x90C6`、`OP_NIKON_CHANGE_APPLICATION_MODE=0x9435`；新增 `RC_INVALID_PARAMETER=0x201D`、`RC_LOCAL_EMPTY_SLOT=0xF001`（本地伪码，仅用于区分空槽位）；`responseName` 补齐 |
| `ptp/NikonPtpDriver.kt` | 新增 `auditPictureControl()`（四段只读审计）；新增 `probeWriteBackSame()`（无损回写判定）；`probeWriteMechanism` 的 `preludes` 删掉「先删槽位(0x90CE)」，100→80 组合；`getPicCtrlData` 识别全零空壳 → 抛 `RC_LOCAL_EMPTY_SLOT` |
| `ui/MainViewModel.kt` | 新增 `auditPicCtrl()`、`probeWriteBack()`；`writeToCamera` 的 AccessDenied 兜底从「跑 80 种盲探」改成「先无损回写判定，通了按固化形态重试一次」；`SlotState` 加 `isEmpty`；`refreshSlots` 区分空槽位/参数不支持/读取失败 |
| `ui/AiLabScreen.kt` | 三个按钮改成 ①②③（审计机身 / 无损回写 / 全组合盲探 80）+ 扫描所有槽位 |

重新打包：

```bash
cd /c/Users/laoziocean/Desktop/Z30刷机/ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain -q
# 产物：app/build/outputs/apk/release/app-release.apk
```
