# Z-Cloud AI v1.0.0 — 安装说明

APK：`Z-Cloud-AI-v1.0.0.apk`（11.1 MB，v2 签名，minSdk 26 / targetSdk 34）

## 一、装到手机

1. 把 `Z-Cloud-AI-v1.0.0.apk` 传到手机（微信文件传输 / 数据线 / 网盘都行）。
2. 手机设置里允许「安装未知来源应用」。
3. 点 APK 安装。签名是自签证书（CN=ZCloud AI），后续版本覆盖安装会沿用同一签名，不会冲突。

## 二、连 Z30

1. Z30 菜单 → 设定菜单 → **USB** → 选 **MTP**（不要选「相机控制」）。
2. Type-C 线一头插相机、一头插手机；相机开机。
3. 应用会弹 USB 授权框 → 允许。
4. 状态栏变绿点 + 「C-n 目标槽位」出现 → 连上了。

第一件事去 **USB 终端调试台** 看 C-1~C-9 有没有读出名字。读出来说明 0x90C0 通了，写入基本就稳。

## 三、三个页签怎么用

**① AI 炼丹炉**
- 右上角选一张参考图（电影截图 / 喜欢的照片）
- 点「AI 逆向生成配方」→ 需要先在「本地预设库」填 API Key
- 出配方后曲线图可以手拖控制点微调
- 按住预览图看原图对比
- 底部选槽位 → 刷入

**② 本地预设库**
- 三个供应商：GLM-4V-Flash（免费，推荐先用）、DeepSeek（无视觉，只吃特征数字）、MIMO（视觉）
- 填 Key 后点「切换」设为当前使用
- 实时显示入/出 token 与估算费用，可清零
- 导入 `.NCP` 文件、保存 AI 配方、单个/批量刷入 C-1~C-9

**③ USB 终端调试台**
- 连接状态、VID/PID/序列号、端点地址、session id
- C-1~C-9 槽位表（点一下 = 读回 84 字节原始载荷，Hex 打到下面）
- 操作码族切换：`0x90C0/C1/C2` ↔ `0x90C8/C9/CA`，还有「自动探测」
- Hex 日志：Command / Data / Response 三段实时流，可复制、可清空

## 四、踩坑时先看这里

| 现象 | 处理 |
|---|---|
| 枚举不到设备 | 相机的 USB 模式必须是 MTP；换一根支持数据的线；部分手机要手动开 OTG |
| 读槽位全失败 | 调试台切到 `0x90C8/C9/CA` 再刷一次；或点「自动探测」 |
| 写成功但相机里没变 | 相机要在**照片拍摄**模式（不是回放/菜单）；写完拔线重连看槽位名 |
| 写入报 OperationNotSupported | 该槽位是自定义槽位才有意义，标准槽位 1~6 是只读预设 |
| AI 报 401 | Key 填错或该供应商需实名开通；GLM 免费档直接在 bigmodel 控制台拿 Key |

## 五、实现要点（要改代码时看）

- **84 字节载荷布局**是从固件逆向出来的，不是猜的：名称 20B → 保留 4B → pcd_type 1B → 保留 1B → 曲线 18B×3 → u16 index|flags → 保留 2B。
- **写入前一定先 0x90C0 读模板**，只覆盖名称/参数/曲线，flags 高位原样保留 —— 这是兼容固件校验的关键，别自己造 84 字节。
- 曲线 18 个采样点，`0x80` = 中性，`0xFF` = 该点不参与。
- 曲线插值是 Fritsch–Carlson 单调三次样条，不会过冲出振荡。
- 源工程在 `ZCloudAI/`，重新打包：

```bash
cd ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon
```

产物：`ZCloudAI/app/build/outputs/apk/release/app-release.apk`
签名：`ZCloudAI/keystore/zcloud.jks`（store/key 密码 `zcloudai`，alias `zcloud`）
