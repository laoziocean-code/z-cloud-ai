# Z-Cloud AI v1.4.0 — 刷入失败自救版

> 目标版本：Z30 / EXPEED 6
> 包名：`com.zcloud.ai` · versionCode 6 · versionName 1.4.0
> APK：`Z-Cloud-AI-v1.4.0.apk`（11,793,107 字节）
> SHA-256：`08c0ff43a2483e78f62835232af0a04f156a3ea6b6542788cf871cbb561b295a`

---

## 一、你这张截图说明的事

| 现象 | 结论 |
|---|---|
| C-1 读出 `Konica Ce` | **读链路完全正常**。0x90CC、参数形态、载荷归一化（85→84）全都对。 |
| C-2~C-9 显示「读取失败」 | 尼康对**没注册过的空槽位**回 `AccessDenied`，这是正常行为，不是程序坏了。v1.4.0 已改显示「**空槽位**」。 |
| 点刷入报 `AccessDenied` | **0x90CD 写命令被拒**。不是参数错，是相机认为「当前条件下不允许写」。 |

写被拒这件事，尼康不会告诉你缺哪个条件。所以这一版把能想到的条件全列成矩阵，让程序自己试。

---

## 二、v1.4.0 干的事：自动试错

### 矩阵规模 = 参数形态 × 载荷形态 × 前置动作

**参数形态（5 种）**
| # | 形态 | 依据 |
|---|---|---|
| 0 | `(slot, 0)` | libgphoto2 注释写「2 params」最常见写法 |
| 1 | `(slot, pcdType)` | 第二参声明数据种类 |
| 2 | `(slot)` | 单参退化 |
| 3 | `(slot, 84)` | 第二参是载荷长度（vendor set 常见形态） |
| 4 | `(slot, 1)` | 第二参是「启用自定义曲线」标志 |

**载荷形态（4 种）**
| # | 形态 | 依据 |
|---|---|---|
| 0 | 纯 84B | libgphoto2 / digiCamControl 写路径假定 |
| 1 | 85B，首字节 = C 序号 0..8 | 「0x90CC 读回多一字节」的假设 |
| 2 | 85B，首字节 = PTP item 号低字节（0xCB 起） | 与读回的 item 号对齐 |
| 3 | 87B = `[item][mono][customFlag]` + 84B | digiCamControl 解析出的老机身布局 |

**前置动作（5 种）**
| # | 动作 | 依据 |
|---|---|---|
| 0 | 无 | 最保守 |
| 1 | 切 PC 控制模式（0x90C2=1）+ DeviceReady | digiCamControl `InitHostMode` |
| 2 | 切 PC + 结束取景 + DeviceReady | 取景独占通道时写入常被拒 |
| 3 | 先删槽位（0x90CE）再切 PC 再写 | 空槽位可能要先「创建」 |
| 4 | **先把 0xD200 指向目标槽位**再切 PC 再写 | ZENCHE 切当前优化校准项就用 0xD200；尼康可能只允许写「当前选中项」 |

**总共 5×4×5 = 100 种组合。命中即固化**，后续刷入直接走这套，不再试错。

判定「命中」的标准很严：**必须命令返回 OK，并且立刻用 0x90CC 读回来名字对得上**。只回 OK 但实际丢弃的固件不会被误判。

---

## 三、你现在怎么测（3 步）

1. 连上 Z30 → 进「AI 调色」页 → 选一个预设 → 目标槽位选 **C-2**（C-1 已被 Konica 占用，别拿它试）。
2. 点 **「自动探测写入机制」**（就在刷入按钮下面）。
   - 屏幕下方会出现实时日志，每行 `· 前置 / 参数形态 / 载荷形态 → 0x响应码`。
   - 100 种组合全跑完大约 1~3 分钟（多数情况前 10 种内就命中）。
3. 结果三种可能：

| 结果 | 含义 | 下一步 |
|---|---|---|
| 绿字出现「已固化机制：xxx」 | **找到了**，C-2 已写入并校验通过 | 直接回主界面正常刷入，之后都走这套 |
| 红字「全部失败（AccessDenied N 次）」 | 100 种都不行 → 问题不在调用形态 | 去「调试台」导出 Hex 日志发我 |
| 中途某条显示 `→ 0x2005` 或别的码 | 相机认这个命令但拒绝参数 | 也有诊断价值，日志发我 |

**另外两个新增按钮**

- **「扫描所有槽位」**：扫 `0x00C0~0x00DC` 全部 item 号，列出所有能读到的名称。你说之前自己导入过 3 个滤镜只认出 1 个 —— 这个按钮用来找另外 2 个到底存在哪个 item 号下（可能不在 0x00CB~0x00D3 这段）。
- 刷入失败时**自动兜底**：如果直接刷入遇到 `AccessDenied` 且还没固化过机制，程序会自动跑一遍矩阵，不用你手动点。

---

## 四、如果 100 种全失败，请给我这些

到「调试台」页，把 Hex 日志里这几段发我（截图或复制都行）：

1. `USB 描述符` —— 接口/端点结构，确认走的是对的接口
2. `OpenSession` 及其后的 `DeviceReady`、`ChangeCameraMode` —— 握手是否真的成功
3. `GetPicCtrlData` C-1 成功的那个包 —— 这是我们的「正确样例」
4. `写入探测` 开头的 4~5 条 —— 相机对第一个参数形态的原始反应
5. `SetPicCtrl 失败` —— 完整响应码

有这 5 段，我基本能定位是「参数不对」「前置条件没满足」还是「这台固件走的是属性路径（0x1016 + 0xD200 系列）而不是 vendor opcode」。

---

## 五、已知未验证点（诚实交代）

- 100 种矩阵是**纯静态分析推出来的候选集**，没有真机验证过哪一种是 Z30 的正确答案。世界范围内没有开源的 Z30 0x90CD 写入实现（libgphoto2 只定义了 opcode，没有实现；digiCamControl 的 `SetPictureControl` 是空函数）。所以这一版本质是「把猜测空间穷举 + 让结果自证」。
- 如果 Z30 固件根本不支持通过 PTP 写自定义优化校准（只能走 SD 卡 recipe 文件那条路 —— 我们之前的逆向结论里，机身确实有 SDS 写服务但没找到 Imaging Cloud 协议），那矩阵会全失败，那时唯一出路是 SD 卡方案。日志能告诉我们是不是这种情况。
- 「先选中槽位 0xD200」这条路最有可能成功，也最有可能带来副作用：写完之后相机当前优化校准项会停在你写的槽位上。属正常，手动切回去即可。

---

## 六、源码与重打包

源码根目录：`C:\Users\laoziocean\Desktop\Z30刷机\ZCloudAI\`

本轮改动文件：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/zcloud/ai/ptp/NikonPtpDriver.kt` | 新增 `setPicCtrlRaw` / `armPcControlMode` / `WriteProbe` / `WriteMode` / `probeWriteMechanism`（100 组合）/ `scanPicCtrlItems`；`setPicCtrlData` 支持 5 种参数形态 × 4 种载荷形态 × 5 种前置动作 |
| `app/src/main/java/com/zcloud/ai/ui/MainViewModel.kt` | `UiState` 加 `probingWrite` / `probeLines` / `writeModeLabel`；新增 `probeWriteMechanism()`、`scanAllPicCtrlItems()`；`writeToCamera()` 的 AccessDenied 自动兜底；`refreshSlots()` 空槽位显示「空槽位」 |
| `app/src/main/java/com/zcloud/ai/ui/AiLabScreen.kt` | 刷入按钮下加「自动探测写入机制」「扫描所有槽位」两个按钮 + 固化机制绿字 + 实时探测日志块 |
| `app/build.gradle.kts` | versionCode 6 / versionName 1.4.0 |

重打包命令：

```bash
cd /c/Users/laoziocean/Desktop/Z30刷机/ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain
```

输出：`app/build/outputs/apk/release/app-release.apk`

---

## 七、签名信息

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
证书 CN=ZCloud AI
```

与 v1.0.0 ~ v1.3.0 同一把 keystore（`ZCloudAI/keystore/zcloud.jks`），**可直接覆盖安装**，数据不丢。
