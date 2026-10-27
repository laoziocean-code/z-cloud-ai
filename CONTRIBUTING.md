# 贡献 / 开发说明

## 环境

| 项 | 版本 |
|---|---|
| JDK | 17 或 21（本仓库用 21 验证） |
| Gradle | 8.7（`gradle-wrapper` 未内置，直接用本机 gradle 或自己 `gradle wrapper`） |
| Android SDK | `platforms;android-34` + `build-tools;34.0.0` |
| Kotlin | 2.0.20 |
| AGP | 8.5.2 |

`settings.gradle.kts` 里优先走阿里云镜像（`dl.google.com` 在国内经常抽风），失败会回落
`google()` / `mavenCentral()`。海外环境可以把那三行 aliyun 注释掉。

## 构建

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle :app:assembleRelease
```

无签名文件时产出 `app/build/outputs/apk/release/app-release-unsigned.apk`。
要出可安装的包，按 `keystore.properties.example` 建 `keystore.properties`。

## 离线验证算法

`tools/VerifyCore.java` 是纯 JVM 的算法自检（不依赖 Android），验证：

- 单调样条 LUT 长度 / 单调性 / 端点值 / 水平线保持 / 恒等映射
- `defaultTemplate()` 的 614 字节结构能被 `parsePayload` 解析
- `normalizePayload` 对 1~4 字节前缀的还原
- `lutToCurveSegment` / `curveSegmentToLut` 往返误差
- 名称截断到 19 字符

```bash
KS=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar | head -1)
CP="app/build/tmp/kotlin-classes/release:$KS"
javac -encoding UTF-8 -cp "$CP" -d tools/out tools/VerifyCore.java
java -Dfile.encoding=UTF-8 -cp "$CP:tools/out" VerifyCore
```

## 真机联调

1. 相机「设定 → USB」选 **MTP/PTP**
2. **不要同时接电脑** —— 一个 USB-C 口不能既做 adb 又做 OTG 主机
3. App 内 **设置 → 高级 → USB 调试台**，能看到完整的 PTP 指令 Hex 流
4. 连不上时先看调试台的四步排查文案，它会区分「手机没看到任何 USB 外设」（物理层）
   和「看到了但没有 Nikon」（相机 USB 模式不对）

日志落盘在 `Android/data/com.zcloud.ai/files/logs/ptp-YYYYMMDD.log`，
「设置 → 日志 → 分享日志」可以直接发出来，不用接电脑。

## 改协议相关代码前必读

- `ptp/Ptp.kt` 的 OpCode 常量有来源注释，别照抄网上流传的 `0x90C0/C1/C2`（那是错的）
- `core/PcPayload614.kt` 的偏移表是从两个真实配方逐字节比对出来的，改动前先跑
  `tools/VerifyCore.java` 确认往返一致
- `ptp/NikonPtpDriver.kt` 里 `transferOut` 故意只用同步 `bulkTransfer`，
  `transferIn` 才是异步优先 + 粘性降级 —— 原因写在两处的 KDoc 里
- 破坏性操作（`0x90CE` 删槽位）绝不能混进自动探测矩阵。v1.4.0 曾经把它当无害前置动作，
  真机上把用户存好的预设删没了

## 提交

- 不要提交 `.jks` / `keystore.properties` / `local.properties` / `*.apk`
- 改协议逻辑请附上真机日志片段（`GetEvent` / `0x90CC` / `0x90CD` 的应答）
