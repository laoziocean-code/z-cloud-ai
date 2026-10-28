# Z-Cloud AI v1.6.0 — 内置预设 50 → 200 套

包名 `com.zcloud.ai` · versionCode 11 · versionName 1.6.0 · minSdk 26 / targetSdk 34
APK：`Z-Cloud-AI-v1.6.0.apk` · 11,826,359 字节
SHA-256：`863bcdee52755e6679ce158bb7d66cac192b3e812a31f4088d8369fd395c2dd0`
签名：v2 方案，`CN=ZCloud AI`

安装（魅族等限制 `INSTALL_FAILED_USER_RESTRICTED` 的机型必须带 `--user 0`）：

```bash
adb install --user 0 -r "Z-Cloud-AI-v1.6.0.apk"
```

---

## 一、预设数据从哪来

你要求「尽量从尼康云创获取」，我把两条路都试到底了，结论如实说：

**尼康云创（Nikon Imaging Cloud）拿不到。** 它的配方库在 `api.user.cwp.imagingcloud.nikon.com/bff/recipe/published`，
匿名请求返回 `401 {"message":"Unauthorized"}`，必须登录态 `access_token`。换 `api.user.ir.*` 那组
`publicrecipe / publicbook` 接口，未带 bearer 时被 API Gateway 直接挡回
`403 {"message":"Missing Authentication Token"}`。也就是说这些配方不是公开数据，得用你自己的账号登录才读得到。
硬闯属于越权，没做。

**改用 nikonpc.com。** 这是第三方 Nikon Picture Control Editor，社区从 2005 年起沉淀了 166 套真实
`.NCP` 配方，覆盖 Fuji / Kodak / Agfa / Ferrania / Konica 胶片模拟、BeatLog / Flaat 电影 Log、
SHING 系列日系调、Tri-X / T-Max 黑白等等。抓取方式：

```
POST https://nikonpc.com/?cmd=loadNpc
name=<"Fuji/PICCON07.NCP">
```

返回里 `splain.points` 就是原始的 (x, y) 曲线锚点数组 —— **这是真曲线，不是猜的**。
166 条全部抓到，0 条失败，落在 `ref/nikon/npc_raw.json`。

字段覆盖：

| 字段 | 有值的条数 | 说明 |
| --- | --- | --- |
| `name` | 166 | 配方名 |
| `picCtrl` | 166 | Neutral 72 / Standard 41 / Monochrome 23 / Portrait 15 / Vivid 14 / False 1 |
| `sharpening` | 166 | 0→24, 2→34, 3→31, 5→35, 其余零星 |
| `saturation` | 143 | 黑白那 23 条没有这个字段 |
| `hue` | 143 | 同上 |
| `points` | 166 | 条数 2~20，中位 6 |

---

## 二、从 166 到 200

nikonpc 那批是影友调胶片和 Log 用的，天生偏科：电影 51 套、胶片 37 套，而**人像、美食、夜景几乎没有**
（美食 0 套、夜景 0 套 —— 2005–2015 年没人做这类预设）。所以我做了两件事，都在 `note` 里写清楚了：

1. **按类补缺**（每类最少 12 套）：给缺的类别挑一条合适的真实曲线做母本，再叠针对该场景调过的
   锐化/饱和/色相/曲线偏移。`note` 末尾标 `·由 <母本名> 曲线派生`，不假装那是别人调好的原创配方。
2. **补到 200**：仍然用真实曲线做参数变体（`PLUS / SOFT / DEEP / AIR / XHARD`），`note` 标 `·衍生`，
   数据里 `source = "derived"`，**界面上会显示一个灰色的「派生」小标签** —— 你能一眼分清哪 166 套是真配方。

最终分布：

| 分类 | 套数 | | 分类 | 套数 |
| --- | --- | --- | --- | --- |
| 电影 | 51 | | 风光 | 18 |
| 胶片 | 37 | | 黑白 | 16 |
| 日系 | 18 | | 人像 | 12 |
| 街拍 | 12 | | 暗调 | 12 |
| 美食 | 12 | | 夜景 | 12 |

**热门度前 20**（rank 越小越靠前）：

```
 1  Fuji Provia           11  TMAX
 2  Fuji Velvia           12  NEOPAN
 3  Kodak Portra NC       13  BeatLogII MaxDR
 4  Fuji Astia            14  BeatLogII WideDR
 5  Kodak Ektachrome      15  Flaat_11p
 6  Kodak Kodachrome 2    16  Flaat_12p
 7  Kodak Portra 160NC    17  SHING TokyoTone
 8  Fuji Superia Reala    18  SHING TitanicTone
 9  Fuji Superia Reala II 19  Agfa Optima
10  Tri-X                 20  Agfa Vista 200
```

---

## 三、界面改了什么

**预设库页**

- 标题改成「内置预设（200）」，分类 chip 显示各类实际数量。
- **新增「派生」灰色小标签**：真配方不标，派生配方才标。
- **分批渲染**：每批 24 条，底部按钮「继续加载（还有 N 条）」。为什么这么做 —— 200 张卡每张都要现画
  一条 128×18 灰阶预览带 + 一个 42×42 曲线缩略图，一次性组合会在进入页面时明显卡顿。分批后首屏只画 24 张。
  换分类或改搜索词会自动重置到第 1 批，不会把「翻到第 8 页」的状态串到新结果上。
- 搜索框照旧可用：名称 / 机名 / 分类 / 说明四路匹配，200 条里搜「portra」能直接捞出 3 条。

**其余 Tab 未改动**，USB 协议层、AI 调色、监看、相册全部与 v1.5.0 一致。

---

## 四、曲线与参数的正确性怎么验的

不靠眼睛看，跑了三个脚本：

**1. 曲线拟合一致性** —— 生成器里的 `pchip_lut()` 是完整 Fritsch–Carlson 单调三次插值（端点限制 +
极值点切线置零 + Hermite 求值），与 App 里 `CubicSplineLut.kt` 同一算法。所以 App 拿到 2~20 个锚点
自己再算一次 LUT，结果与生成时一致，不会因为两套插值实现不同而变形。

**2. 参数边界扫描** —— 对 200 条逐条检查：控制点数 2~20（实测分布 `{2:29, 3:13, 4:14, 5:19, 6:44,
7:11, 8:20, 9:16, 10:15, 11:7, 12:8, 18:2, 20:2}`）、锐化 -3..9、饱和 -3..3、色相 -3..3、
pcdType ∈ {0,1,2,8}。**全部通过，越界 0 条**。机身是严格的，一个超界就整个 `0x90CD` 被拒。

**3. 机名唯一性** —— 614 字节载荷的名称区只有 20 字节 ASCII，中文会变下划线，所以每个配方都有
`displayName`（中文界面名）和 `cameraName`（≤19 位 ASCII 真名）。200 条 `cameraName` **零重复**，
重名的配方自动加 ` II / III` 后缀（例如 `Fuji Superia Reala II`），不再出现之前那种
「Fuji Superia Reala L_2」的乱拼。

**4. 进包验证** —— 解包 APK 的 `classes2.dex`（10,490,116 字节）直接搜字符串：
`Fuji Provia`、`Fuji Velvia`、`Kodak Portra NC`、`BeatLog`、`TitanicTone`、`WARM 美食`、`NEON 夜景`、
`Fuji Superia Reala II`、`c1 III`、`继续加载`、`派生`、`已全部显示` —— **全部命中**，确信新数据真的在包里。

---

## 五、note 是算出来的，不是编的

之前 50 套那版的 note 有个毛病：写「黑位 13」，但那其实只是 `lut[13] == 13`，曲线本身是直线，
会让人误以为抬了暗部。这版改成算**相对直线的偏移**：

- `abs(三个采样点偏移) <= 2` → 「曲线接近直线，只靠参数」
- 否则报实际动作：「暗部抬 +12 有灰雾，中灰 +50 偏亮，高光 +11，锐化 6，饱和 +2（standard 基底）」

`nl_s0`~`nl_s9` 那批 Neutral Linear 系列也重新归类了：它们曲线是直线、只改锐化，属于技术型平基底，
放在电影/Log 那挂，不再误放在人像类里。

---

## 六、源码改动

| 文件 | 改动 |
| --- | --- |
| `core/BuiltInPresets.kt` | 重写，450 行 → 1,747 行，50 套 → 200 套 |
| `ui/BuiltInPresetSection.kt` | 分批渲染（`PAGE = 24` + 继续加载按钮）、`派生` 标签、KDoc 更新 |
| `ui/PresetLibraryScreen.kt` | 注释同步 |
| `ui/MainViewModel.kt` | 注释同步 |
| `app/build.gradle.kts` | versionCode 10→11、versionName 1.5.0→1.6.0 |
| `ref/nikon/gen_presets.py` | 生成器：PCHIP 拟合、分类规则、按类补缺、note 生成、罗马数字去重、Kotlin 输出 |

重新打包：

```bash
cd ZCloudAI
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export ANDROID_HOME=/c/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
"/c/Android/gradle-8.7/bin/gradle" :app:assembleRelease --no-daemon --console=plain -q
```

重新生成预设（如果以后想加入新配方，往 `npc_raw.json` 里加一条再跑）：

```bash
cd ref/nikon && python gen_presets.py
```

---

## 七、还没验证的

- **200 套没有逐条刷进机身验证过。** 之前 C-1 能读出 `Fuji Provia`、C-2 读出 `Fuji Astia`，说明
  614 字节格式和 `0x00C9` 起槽位是对的；但这 200 套里新加的那些曲线（尤其派生配方）没有真机回读确认过。
  建议先挑 3 套不同分类的（比如 `Fuji Velvia`、`Tri-X`、`NEON 夜景`）刷到 C-1~C-3，看机身 LCD 显示
  的名字和实际拍出来的效果对不对。
- **派生配方的观感是算出来的。** 母本曲线是真的，参数是我按场景调的，但没人拿真机拍过样片。
  如果某个「WARM 美食」看着不对，告诉我，我改参数不用重抓数据。
- **视频和封面里的文案还写着「50 套」**，那是上一版出片时的数字。你说了视频就这样，我没重渲。
  要更新的话，`promo/render_v15.py` 第 382/474/486 行、`promo/make_cover15.py` 第 110 行改数字再跑一遍就行。
