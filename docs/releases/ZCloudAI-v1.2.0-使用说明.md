# Z-Cloud AI v1.2.0 · 综合相机伴侣

APK：`Z-Cloud-AI-v1.2.0.apk`（11,743,951 字节）
SHA-256：`3192229a45d5e503ae70ca428bdfdd80d1ea8d7f6a5e60321a2e1c865f36e551`
包名 `com.zcloud.ai` · versionCode 4 · minSdk 26（Android 8.0+）· targetSdk 34
签名：APK Signature Scheme v2，已通过 `apksigner verify`

---

## 这一版比 v1.1.1 多了什么

v1.1.1 是「能连上 + 能刷优化校准」的工具；v1.2.0 变成日常能用的相机伴侣，共 5 个页签：

| 页签 | 能力 |
|---|---|
| **监看** | 实时取景、快门、录像、光圈/快门/ISO/曝光补偿/白平衡/优化校准全参数控制、自动对焦、手动对焦推拉、拍完即看 |
| **AI 调色** | 参考图 → 直方图特征 → 视觉大模型输出 JSON → 曲线实时预览 → 刷入 C1~C9（本 App 的特色） |
| **相册** | 读机身 SD 卡文件列表、缩略图墙、下载到手机相册、删除、存储卷剩余容量 |
| **预设库** | NCP 导入/导出、已存预设管理、批量刷入、**滤镜快传码**、API Key 与 token 记账 |
| **调试台** | USB 描述符、PTP Hex 收发日志、槽位读取、一键诊断、实时取景调试 |

### 本轮新增的具体功能

1. **实时监看**（对标你提到 ZENCHE 的能力）
   `0x9201` 启动 → 循环 `0x9203` 取帧 → `0x9202` 关闭。约 6fps，够对焦构图。
   取景画面上叠了 `1/125 F5.6 ISO100` 和 `EV±x.x` 的实时回显。

2. **拍摄参数控制**
   光圈 `0x5007`、ISO `0x500F`、曝光补偿 `0x5010`、白平衡 `0x5005`、曝光模式 `0x500E`、优化校准 `0xD200`、快门 `0xD100`（失败回落 `0x500D`）。
   全部按 ZENCHE 的写法编码（光圈×100、EV×1000、快门 `num<<16|den` 打包），连上后自动读回一次。

3. **拍照**
   `0x9207` CaptureToSdram → 轮询 `DeviceReady + GetEvent` 抓 `0xC101` 事件里的句柄 → `0x1009` 拉 JPEG。
   拿到就显示在页面上，可以一键「存到手机相册」或「当作调色参考图」——拍完直接进 AI 调色流程。

4. **录像**
   `0x920A` 开始 / `0x920B` 结束。

5. **相册管理**
   `0x1004` 存储卷 → `0x1005` 容量 → `0x1007/0x1008` 对象列表 → `0x100A` 缩略图 → `0x1009` 下载 → `0x100B` 删除。
   下载走 MediaStore 直接落系统相册，**不申请存储权限**。视频进 Movies，照片进 Pictures。

6. **滤镜快传**（新功能，我加的）
   把一套调色参数压成一行文本码：`ZCAI1:<Base64>`，可复制、可发微信、可贴评论区。
   对方粘进「预设库 → 滤镜快传」就能还原成完整曲线和参数，一键刷进自己相机。
   没引二维码库（省依赖），文本码比截图可检索、可进群公告。

7. **电量显示**
   顶栏显示 `🔋xx%`，≤20% 变红。读 `0x5001`。

8. **未连接时的连接入口**
   监看页顶部直接列出已枚举的 Nikon 设备 + 「连接」按钮 + 三条排错提示，不用先跳调试台。

---

## 装法与连法

1. 手机装 `Z-Cloud-AI-v1.2.0.apk`（v1.1.x 可直接覆盖安装，签名一致）。
2. **Z30 开机 → 菜单 → 设置 → 连接 → USB → 选「MTP/PTP」**（不是「相机控制」）。
3. Type-C 线接手机，App 冷启动会自动枚举并申请权限，点「允许」。
4. 连上后顶栏出现绿点和电量；进「监看」点「开启取景」。

## 连不上时怎么办

直接发我「调试台」页的日志，按顺序看这四类：

| 日志标题 | 说明 |
|---|---|
| `USB 描述符` | 有没有枚举到 VID 0x04B0 / PID 0x0452，接口 class 是不是 6，有几个 Bulk 端点 |
| `握手失败 第 n/3 轮` | 握手卡在哪一轮，附带 RC 码 |
| `诊断结果` | 0x1001 / 0x90C8 / 0x90CF / 0x90CC 四层逐层结果 |
| `事务号不一致` / `异步传输降级` | 传输层兼容性问题，说明主机控制器行为异常 |

调试台还有两个按钮：
- **一键诊断**：把上面四层跑一遍，输出成一段文本，直接截图发我。
- **切 PC 控制模式**：发 `0x90C2`，部分机身要先切进 PC 控制模式才肯响应 0x90CC。

## 还没验证的地方（诚实说明）

以下都只在代码层和离线单元测试里验证过，**没有 Z30 真机跑过**：

- `0x90CC` / `0x90CD` 的载荷前缀（我做了 1~8 字节的自动归一化，但真机才知道偏移到底是几）
- `0x90CD` 的写入参数形态（默认两参 `(slot, 0)`，调试台可切单参 `(slot)`）
- 实时取景帧率与稳定性
- 相册缩略图批量读取耗时（现在只预取前 60 张）
- 录像 `0x920A/0x920B` 在 Z30 上是否被支持

刷入前建议先在调试台读一遍 C-1~C-9，确认能读出名字再动写。

---

## 源码与重打包

工程：`C:\Users\laoziocean\Desktop\Z30刷机\ZCloudAI\`

```bash
cd /c/Users/laoziocean/Desktop/Z30刷机/ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain -q
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名：`keystore/zcloud.jks`，alias `zcloud`，store/key 密码都是 `zcloudai`。

主要文件：

| 文件 | 职责 |
|---|---|
| `ptp/NikonPtpDriver.kt` | USB Host + PTP 容器 + 全部尼康 opcode |
| `ptp/Ptp.kt` | 操作码/响应码/属性号常量表 |
| `core/CameraStorage.kt` | PTP Dataset 解析（存储卷/对象信息） |
| `core/NcpParserAndBuilder.kt` | 84 字节优化校准载荷构建与解析 |
| `core/CubicSplineLut.kt` | Fritsch–Carlson 单调三次样条 → 256 LUT |
| `core/RecipeShareCode.kt` | 滤镜快传码编解码 |
| `ai/AiColorGradingRepository.kt` | 直方图特征 + 视觉大模型调用 + JSON 解析 |
| `ui/LiveMonitorScreen.kt` | 监看页 |
| `ui/GalleryScreen.kt` | 相册页 |
| `ui/AiLabScreen.kt` | AI 调色页 |
| `ui/PresetLibraryScreen.kt` | 预设库 + 快传页 |
| `ui/UsbConsoleScreen.kt` | 调试台 |
