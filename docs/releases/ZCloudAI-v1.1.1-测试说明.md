# Z-Cloud AI v1.1.1 测试说明（连不上先看第 3 节）

**APK**：`Z-Cloud-AI-v1.1.1.apk`（11,678,411 字节）
**SHA-256**：`afb615bf76d496f969a0118c391f4b77db4e5f68ea7221170c0f5aa96e1a9371`
**包名/版本**：`com.zcloud.ai` / versionCode 3 / versionName 1.1.1；minSdk 26，targetSdk 34
**签名**：APK Signature Scheme v2，`apksigner verify` → Verifies

---

## 1. 这一版改了什么（针对「能装但连不上」）

上一版 v1.1.0 连不上，定位到**四个独立缺陷**，全部改了：

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | PendingIntent 用了 `FLAG_IMMUTABLE` | 系统无法回填 `EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED`，广播里 `granted` 恒 false → **点「允许」App 毫无反应** | 改 `FLAG_MUTABLE` + `setPackage()`，并对 `EXTRA_DEVICE` 为 null 时回落到 `pendingDevice` |
| 2 | 从未发 PTP class reset | 相机端点可能停在 halt/未初始化态，OpenSession 被吞 | 握手前发 `controlTransfer(0x21, 0x66, 0, ifaceId)`（ZENCHE 的 `resetPtpDevice`） |
| 3 | 读容器时「先精确读 12 字节头，再读 body」 | 部分主机控制器在请求长度 = 12 时以短包结束，**头读到了 body 永远超时** | 改成一次读最多 64 KB 的大块，凑够 12 字节头后从同一块里取 body |
| 4 | OpenSession 事务号从 1 开始 | ISO 15740 要求 OpenSession 的 TransactionID = 0 | 强制 `forceTx = 0L` |

另外补的健壮性：
- **3 次重试 + 传输恢复**：失败后 `resetPtpDevice` → 关连接 → 递增退避 → 按 VID/PID 重新取 `UsbDevice`（旧实例 `openDevice` 会返 null）→ 重开。
- **异步优先、同步粘性降级**：Bulk-IN 先试 `UsbRequest` 异步；一旦失败就清 halt 并**永久切同步** `bulkTransfer`（每个会话只探一次，不再反复踩坑）。Bulk-OUT 直接同步——异步 OUT 在部分控制器上「提交成功但没真正发出」，会让相机容器头错位。
- **非 0 alt setting** 自动 `setInterface(alt=0)`。
- **端点 halt 清除**（两个 Bulk 端点）。
- **分块从 16 KB 提到 64 KB**（API ≥ 28）。
- **热插拔**：插上自动连、拔掉自动复位。

---

## 2. 正确用法（顺序照做）

1. 相机：`设置 → 连接 → USB` 选 **MTP/PTP**，**不要**选「相机控制」。
2. 相机开机、插线。App 会自动弹授权（或自动连上）。
3. 进 **「USB 调试台」**页签，先看 **USB 描述符** 那一条日志，确认枚举到：
   - `VID=0x04B0 PID=0x0452`（Z30）
   - 有一个接口 `cls=6 sub=1 proto=1`，且带 Bulk In + Bulk Out
4. 点 **「一键诊断」**——它会依次跑四层，把卡在哪一层直接写出来：

```
[1] GetDeviceInfo 0x1001 : OK / 失败原因
[2] DeviceReady   0x90C8 : OK / 失败原因
[3] PicCtrlCaps   0x90CF : OK / 失败原因
[4] GetPicCtrl    0x90CC : 两参 OK / 单参 OK / 都失败
```

5. 诊断到 `[4]` 失败 → 点 **「切 PC 控制模式」**（发 0x90C2 + DeviceReady）再诊断一次。
6. **别自己手动空转「参数形态」两个 chip**——`probeOpcodeProfile()` 连接后已经自动跑过两参→单参→PC模式重试三轮。

---

## 3. 连不上时，把这一页原文发我

**「USB 调试台」→ 右上角导出/截图 Hex 日志**，我需要的是这几条：

1. `USB 描述符` —— 完整那一行（接口/端点/包长）
2. `OPEN` —— `VID/PID ... if=.. in=0x.. out=0x.. irq=0x.. attempt=N`
3. `握手失败 第 N/3 轮` —— 每轮的报错文本
4. `异步传输降级` —— 有没有出现（出现了说明主机控制器异步有问题）
5. `事务号不一致(Data/Resp)` —— 出现了说明相机回包错位
6. `诊断结果` —— 那四行

有了这 6 条，我能直接判断是「没枚举到 / 没授权 / reset 没生效 / OpenSession 被拒 / 0x90CC 参数形态不对 / 载荷归一化错」中的哪一种，不用再猜。

---

## 4. 已知仍未验证的点（真机第一次跑才知）

- `0x90CC` 返回长度：我们按「84 字节载荷」做归一化，但 digiCamControl 的字段偏移显示它可能返回 **85 字节**（前 3 字节是 item/mono/custom-flag 前缀）。代码里 `normalizePayload()` 会在 0~8 字节偏移里逐候选打分，选出最像 84B 载荷的那一段——所以两种都能吃。若日志出现 `载荷归一化 机身返回 85B → 裁成 84B`，说明猜对了。
- `0x90CD` 写入是否只吃 84 字节、是否需要第三参，全世界开源代码里没有可参考实现（digiCamControl 的 `SetPictureControl` 是空实现）。所以写入策略是：**先 0x90CC 读回机身模板 → 只覆盖名称/参数/曲线 → 0x90CD 写回 → 立刻 0x90CC 读回校验名称**。读回名字对得上就算成功。
- 实时取景 `0x9201/0x9203/0x9202` 是 ZENCHE 的实机验证路径，应该能通；取不到帧会在日志里写 `返回数据中没有 JPEG（NB）`。

---

## 5. 源码位置

```
C:\Users\laoziocean\Desktop\Z30刷机\ZCloudAI\
├─ app/src/main/java/com/zcloud/ai/
│  ├─ MainActivity.kt              # 权限/热插拔/文件选择
│  ├─ ptp/NikonPtpDriver.kt        # USB + PTP 容器 + 尼康 opcode（1143 行）
│  ├─ ptp/Ptp.kt                   # 0x90CC/0x90CD/0x90CE/0x90CF、取景 opcode
│  ├─ ptp/PtpContainer.kt          # 12 字节头拆装
│  ├─ core/CubicSplineLut.kt       # Fritsch–Carlson 单调样条 → 256 LUT
│  ├─ core/NcpParserAndBuilder.kt  # 84B 载荷解析/构建 + normalizePayload
│  ├─ ai/AiColorGradingRepository.kt # 直方图 + Vision LLM JSON
│  └─ ui/                          # Compose 三页签 + 曲线编辑器
└─ tools/VerifyCore.java           # 离线算法自检（全部 PASS）
```

**重新打包命令**：

```bash
cd /c/Users/laoziocean/Desktop/Z30刷机/ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain
```

产物：`app/build/outputs/apk/release/app-release.apk`（已用 `keystore/zcloud.jks` 自签，可直接覆盖安装）
