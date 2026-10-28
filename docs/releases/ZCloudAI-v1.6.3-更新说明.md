# Z-Cloud AI v1.6.3 更新说明

版本：`versionCode 14 / versionName 1.6.3`
APK：`Z-Cloud-AI-v1.6.3.apk`（11,842,743 字节）
SHA-256：`6938d730e344b90fec1071609409c8d7fa839340d5221d5ed19c67c39500ac63`

---

## 一、这次改的是什么

针对你上一轮反馈的两个问题：**「刚拍的这一张」不显示**、**「曝光参数下发没用（0x500E AccessDenied）」**。
v1.6.2 已经改了一版链路，v1.6.3 补的是**把失败变成能自证的信息**，同时再堵两个漏。

| 问题 | v1.6.3 的处理 |
|---|---|
| 点「下发」就报 `程序:SetDeviceProp 0x500E 失败: AccessDenied` | ① 同值不再发；② 新增「属性」按钮，直接问机身这条属性是不是只读 |
| 部分参数其实写成功了，但提示一片红 | 提示里分开写「X 项写入 / Y 项跳过」，跳过的列出原因 |
| 「刚拍的这一张」还是空白 | 回传兜底从 2 级加到 3 级：事件句柄 → 固定 SDRAM 句柄 `0xFFFF0001` → 存储卡尾部最新对象 |

---

## 二、核心改动

### 1. 新增「属性」按钮：让机身自己回答「能不能写」（只读，随时可点）

一直以来的困境：`0x500E` 回 `AccessDenied`，同一批里的光圈、ISO 却能写成功。
光看错误码分不清是**这条属性在机身上本来就只读**，还是**当前状态被临时锁住**。

新增做法：发 `0x1014 GetDevicePropDesc`，读描述符。返回数据前 5 字节是 PTP 标准结构：

```
[0..1] 属性码   [2..3] 数据类型   [4] GetSet 标志（0 = 只读，1 = 读写）
```

这是机身自己给的权威答案，参考实现 ZENCHE 的 `refreshParameterCapabilities()` 用的也是这个判据
（`ref/zenche/PtpCamera.java:1430`：`descriptor.length >= 5 && descriptor[4] != 0`）。

点击位置：**监看页 → 曝光参数 → 右上角「属性」**
输出：面板上直接列出 8 条属性，每条给「可写 / 只读 / 未知 + 当前值 + GetSet 说明」，同一份内容进日志。

探测的 8 条：

```
曝光程序 0x500E    ISO 0x500F    光圈 0x5007      快门(标准) 0x500D
快门(尼康) 0xD100  曝光补偿 0x5010  白平衡 0x5005   优化校准项 0xD200
```

全程只发 `0x1015`（读值）和 `0x1014`（读描述符），**不改任何机身状态**。

### 2. 同值不再发（`0x500E` 报错最常见的原因）

尼康对「写入值等于当前值」也会回 `0x200F AccessDenied`。而实际使用里，点「下发」时大概率一个参数都没改。
现在下发前先读回机身现值，相等的直接跳过并在提示里写明：

```
参数已下发（0 项写入，7 项跳过）
跳过：曝光程序（已是 A 档）、ISO（已是 100）、光圈（已是 F5.6）、快门（A 档由机身决定）、
      曝光补偿（已是 +0.0）、白平衡（已是 0x0002）、优化校准（已是 #1）
```

改过的参数才会真发。这样「点下发就报错」的场景基本消失，剩下的报错都是真的写不进去。

### 3. 拍摄回传三级兜底

```
① 事件句柄（0xC101 里的 param1）
   ↓ 拿不到 / GetObject 回 0x2009 重试 9 次仍失败
② 固定 SDRAM 句柄 0xFFFF0001   ← v1.6.3 新增
   ↓ 还是失败
③ 存储卡尾部最新对象（getObjectHandles 取最后 4 个，逐个 GetObjectInfo + GetObject）
```

② 的依据：部分尼康机身不用 `0xC101` 事件，SDRAM 里永远只有一个编号为 1 的对象；
ZENCHE 的 `capture()` 在拿不到事件时用的就是这个句柄（`ref/zenche/PtpCamera.java:630`）。
③ 是最后一道，代价是慢（要列存储卡），但能把图救回来。

### 4. 下发结果提示更准

旧版把「跳过」也算成失败，7 个参数里跳过 4 个就显示「部分参数失败」，看起来很严重其实什么都没错。
现在分开：

```
参数已下发（3 项写入，4 项跳过）
跳过：快门（A 档由机身决定）、EV（M 档无效）、...
```

---

## 三、装法

```powershell
# 魅族必须带 --user 0，否则报 INSTALL_FAILED_USER_RESTRICTED
& "C:\Android\Sdk\platform-tools\adb.exe" install --user 0 -r "C:\Users\laoziocean\Desktop\Z30刷机\Z-Cloud-AI-v1.6.3.apk"
```

手机没连电脑时，直接把 APK 拷进手机点安装（同签名覆盖，不用卸载旧版）。

---

## 四、测试顺序（建议按这个来）

1. **先点「属性」**（监看页 → 曝光参数 → 右上）。这一步只读，不改任何东西。
   看 `曝光程序 0x500E` 那行是「可写」还是「只读」。
   - 若是**只读** → 那 `0x500E` 永远写不进去，是机身设计，不是 bug，我们改成不再尝试并提示。
   - 若是**可写** → 那 AccessDenied 是状态锁，点「下发」看是否已经能过。

2. **改一个参数再点「下发」**（比如把 ISO 从 100 改成 800）。同值跳过已经生效，
   这一步能真正验证写入通道。

3. **切档位**（M/P/A/S）——点档位 chip 会立刻发 `0x500E`，不用再点「下发」。
   切档后快门/光圈哪个变灰，说明机身接管了哪个，这是正常的。

4. **拍摄**：先开取景 → 按快门 → 看图有没有出来。若还是空，日志里会有这几行之一：
   - `SDRAM 对象句柄 = 0xXXXXXXXX` → 拿到了句柄，问题在下载
   - `未收到 0xC101 事件，回退到默认句柄 0xFFFF0001` → 事件格式还是不对
   - `句柄 0xXXXXXXXX 下载失败，改试固定 SDRAM 句柄 0xFFFF0001`
   - `兜底下载成功：句柄 0xXXXXXXXX（NNNNNNB）` → 救回来了
   - `回传了 X.X MB 但不是可解码的 JPEG（前 4 字节 ..）` → 拿到数据但不是 JPEG

5. **要日志**：调试台 → 「分享日志」。日志已持续落盘到
   `/sdcard/Android/data/com.zcloud.ai/files/logs/ptp-YYYYMMDD.log`，含每次会话头与全部 PTP 事务。

---

## 五、仍未验证的点（如实说明）

1. **`0x1014` 描述符在 Z30 上是否可用**——参考实现有，我们自己没在 Z30 上跑过。
   若 8 条全显示「未知」，说明机身不支持 `0x1014`，那就只能靠 `0x1015` 读值 + 试写来判断。
2. **`0x500E` 到底是只读还是状态锁**——这个只有真机能回答，也是这次加「属性」按钮的原因。
3. **固定 SDRAM 句柄 `0xFFFF0001` 在 Z30 上是否有效**——ZENCHE 在 Z50/Z6 一类机身上验证过，Z30 未验证。
4. 相册页仍只读最近 100 张（按拍摄时间倒序），这是性能取舍，不是 bug。

---

## 六、改动文件与重打包

| 文件 | 改动 |
|---|---|
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ptp/NikonPtpDriver.kt` | 新增 `getDevicePropDesc(0x1014)`、`data class PropProbe`、`probeWritableProps()`（只读探 8 条属性） |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ui/MainViewModel.kt` | 新增 `probePropWritable()`、`leU16/leI16/fmtOneDecimal/fmtSigned`；`applyShootingParams()` 加同值跳过 + 提示口径；`capturePhoto()` 加固定 SDRAM 句柄兜底 |
| `ZCloudAI/app/src/main/java/com/zcloud/ai/ui/LiveMonitorScreen.kt` | 「曝光参数」标题栏加「属性」按钮；面板内新增可写性结论展示块 |
| `ZCloudAI/app/build.gradle.kts` | `versionCode 14` / `versionName 1.6.3` |

重打包：

```bash
cd /c/Users/laoziocean/Desktop/Z30刷机/ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain -q
```

产物：`app/build/outputs/apk/release/app-release.apk`

---

## 七、签名信息

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
证书 CN=ZCloud AI, OU=Z30 Lab, O=ZCloud
keystore: ZCloudAI/keystore/zcloud.jks  (alias zcloud)
```

同签名，可直接覆盖安装 v1.6.2 及更早版本，预设库与 API Key 配置不会丢。
