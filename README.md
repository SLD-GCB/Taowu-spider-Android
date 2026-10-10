# 梼杌 (taowu-android)

> 一个安卓上的**网页视频/动漫抓取 & 下载**工具。内置浏览器走到要抓的页面，点一下按钮，
> 自动解析出 m3u8 / mp4 / DASH 流并下载成能播的本地文件。
>
> 本项目是 PC 版 **梼杌 v2.1**（Python + Qt）的安卓移植版，把解析引擎、下载栈、解密、
> 配方系统整套搬到了 Android（Kotlin + Jetpack Compose + Media3）。

- 包名：`com.taowu`
- 当前版本：`3.4`（versionCode 16）
- 最低系统：Android 8.0（API 26）；目标 SDK 37

---

## 目录

- [它能做什么](#它能做什么)
- [界面与全套使用方法](#界面与全套使用方法)
  - [① 捕获页（内置浏览器）](#-捕获页内置浏览器)
  - [② 解析页](#-解析页)
  - [③ 分集列表](#-分集列表)
  - [④ 整部抓取](#-整部抓取)
  - [⑤ 内容页（已下载 / 播放）](#-内容页已下载--播放)
  - [⑥ 设置页](#-设置页)
- [抓取流水线（原理）](#抓取流水线原理)
- [配方（站点规则）](#配方站点规则)
- [抓取参数一览](#抓取参数一览)
- [构建与打包](#构建与打包)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [常见问题](#常见问题)
- [已知限制](#已知限制)

---

## 它能做什么

- **通用模式**：不挑站。给一个网页地址，程序自己扫页面里的 m3u8 / mp4 / DASH 地址（页内八趟静态扫描 + 混淆解码 + iframe 深挖）。
- **配方模式**：给特定站写一份 JSON 规则，按接口 / 页面结构精准取流。
- **单集解析**：一个播放页 → 出若干条候选轨道（不同线路 / 清晰度）。
- **分集列表**：一个详情页 → 自动认出整部番的所有线路与集数。
- **整部抓取**：一键把整部番（可多线路、多集并行）抓下来，支持随时停止。
- **HLS / DASH 下载**：分片并发下载，支持 AES-128 / AES-256 / AES-256-CTR / cbcs 解密、DASH CENC / ClearKey。
- **直链下载**：Range 多连接加速 + 断点续传（`.part` / `.idx`）。
- **音频 / 视频合流**：分轨（DASH 视频 + 音频）用 Media3 Transformer 合成单文件。
- **在线播放**：下载前可直接在线试播；下载后在「内容」页离线播放，完整播放器（进度、选集、横屏）。
- **后台抓取**：前台服务保活，退到后台 / 关界面不停，通知栏显示进度、可一键停止。

---

## 界面与全套使用方法

底部三个主栏：**捕获 / 内容 / 设置**。

### ① 捕获页（内置浏览器）

捕获页就是一个**内置浏览器**，用来走到你要抓的页面：

```
┌──────────────────────────────────────┐
│ [ 输入网址…               ] [前往]     │  ← 金色地址栏（永远可见）
│                                       │
│            网页（WebView）             │
│                                       │
├──────────────────────────────────────┤
│ 模式                   [通用模式 ▾]    │  ← 模式切换
│ [解析] [分集列表]                      │
│ [        整部抓取         ]            │
│ 浏览记录                    收起 ▾      │
└──────────────────────────────────────┘
```

**用法**：在地址栏输入网址 → 点「前往」→ 浏览到要抓的页面 →（可选）在下拉框选 **通用模式 / 配方模式** → 点下方三个按钮之一。

- **地址栏**：输 `xxx.com` 会自动补 `https://`；键盘上的「前往 / Go」也能提交。页面跳转（点链接、点分集）时地址栏**自动更新**为当前页地址（只有当光标停在地址栏里打字时不覆盖）。
- **模式下拉**：
  - **通用模式**：页内扫描，任意站可用（默认）。
  - **配方模式**：用站点配方规则（内置 / 用户导入的 JSON）取流。
- **三个按钮**（对**当前浏览到的网页**生效）：
  - **解析** → 进「解析页」，出候选轨道。
  - **分集列表** → 进「分集列表页」，列出线路与集数。
  - **整部抓取** → 进「整部抓取页」，批量下载。
- **可收起的面板**：底部面板右下「收起 ▾」可整块收起，把屏幕全让给网页（网页底部弹窗就不会被挡）。收起后**顶部地址栏右侧出现 `抓取 ▴`**，点它再展开。
- **浏览记录**：底部面板左下「浏览记录」→ 弹出历史列表（最近在最前，最多 60 条，本地持久化）。点一条直接跳转，可单条删除或清空。
- **系统返回键**：在网页内后退，不会直接退出 App。

> 想抓的页面藏在站内搜索 / 列表里时，直接在浏览器里点进去即可，不用手抄 URL。

### ② 解析页

对当前页面跑「通用模式」或「配方模式」解析，列出拿到的候选轨道：

- 每条轨道显示 **清晰度 / 格式 / 大小（若可得）/ 归属**。
- 顶部显示 `route = … · 各步说明`（如 `通用静态扫描`、`配方:xxx`、`页面获取失败`），用来判断失败原因。
- 点某条轨道 → 进播放页（在线试播）。
- 页面底部通常有 **「整部抓取」** 入口，可跳到整部。

### ③ 分集列表

识别当前页属于哪部番，列出**线路 × 集数**：

- 每集一条，显示集号与线路。
- 可单集点进播放 / 下载。
- 也可直接跳到「整部抓取」。

### ④ 整部抓取

把整部番批量抓下来：

- 顶部列出每集状态（等待 / 抓取中 / 完成 / 失败）与总进度。
- 底部主按钮：没任务时是 **「整部抓取 / 重新整部抓取」**，有任务在跑时变成红色 **「停止抓取（已完成/总数）」**，点了随时停。
- 「内容」页的实时进度卡片、解析页状态行、**通知栏**里也各有一个「停止」，哪儿都能停。

**停止是协作式取消**：当次网络请求不会被硬打断，等它返回后在下一个检查点停下（HLS 通常是当前这一批分片，几秒内）。停止后前台通知自动撤掉。

### ⑤ 内容页（已下载 / 播放）

- **正在下载**卡片：显示当前任务标题、`已完成/总数`、当前步骤、实时速度，右上角可「停止」。
- **已下载**列表：列出已完成的文件，点击播放。
- **播放页**：完整播放器（进度、选集列表在视频下方、横屏、记忆播放位置）。

### ⑥ 设置页

顶部标签栏六个页签：

| 页签 | 内容 |
|---|---|
| **媒体配方** | 查看内置配方、导入 / 删除用户配方（JSON） |
| **解析** | 跳转层数、静态扫描开关、跟随 iframe、超时、重试次数/退避、混淆解码开关/轮数、分集识别门槛、额外分集链接正则 |
| **下载** | 分片并发、整部并行集数、重试、Range 分段数/触发大小、分片超时、解密方式 |
| **节流** | 全局/单 host 并发上限、自适应并发开关 |
| **请求头** | 全局 User-Agent、附加 Referer 覆盖 |
| **后处理** | 视频元数据打标开关 |

每页底部有 **「恢复全部默认」**。

---

## 抓取流水线（原理）

```
输入 URL
  │
  ├─ 配方模式 ──▶ 命中配方(host_suffix) ──▶ 跑 steps 取数据 ──▶ emit 产出 Track
  │                                        （未命中/未产出 → 提示换通用模式）
  │
  └─ 通用模式 ──▶ 抓页面 ──▶ 八趟静态扫描
                     │            ├─ 明文 m3u8/mp4/dash 直链
                     │            ├─ 混淆解码（base64↔URL 多重编码，MacCMS encrypt 系）
                     │            ├─ <video>/<source>/data-* 等
                     │            └─ iframe / meta-refresh / 分集页 深挖（跳数上限）
                     ▼
                 Track[]（候选轨道）
                     │
                     ▼
              Downloader 逐条下载
                 ├─ HLS：拉清单 → 并发下分片 → 按 #EXT-X-KEY 解密 → 拼接
                 ├─ DASH：拉 MPD → 视频/音频分轨 → 合流（Media3 Transformer）
                 └─ 直链：Range 多连接分段 + 断点续传（.part/.idx）
                     │
                     ▼
              落盘（命名规整 + 可选元数据 .info.json）→ 内容页可离线播放
```

**关键点**

- **多重编码解码**：MacCMS `player_aaaa` 的 `url` 常是 `base64(URL编码(真实地址))`（`encrypt:2`），程序会**迭代解码到稳定**，把真实 m3u8 还原出来。
- **403 归因**：请求被拒时区分「需要 Referer / Cookie / 防盗链」，便于定位。
- **自适应并发**：全局 + 单 host 双层闸门，按吞吐自动升降，避免把 CDN 打死。
- **断点续传**：中途退出 / 失败后重跑，从已下部分继续。

---

## 配方（站点规则）

配方是**数据不是代码**——一份 JSON 描述某个站「怎么取流」。内置配方在 `src/main/assets/recipes/`，
用户配方放在 App 私有目录（设置 → 媒体配方 里导入，升级不覆盖）。

### 内置配方

| 文件 | 站点 |
|---|---|
| `age_anime.json` | age 动漫（agedmani.com，MacCMS + `a_0001` 模板） |
| `girigirilove.json` | girigiri 愛動漫（只提供分集形状，取流靠通用模式） |
| `bilibili.json` / `douyin.json` / `kuaishou.json` / `youtube.json` | 对应站点 |
| `_模板.json` | 配方模板（`_` 开头的示例文件，引擎忽略） |

### 配方结构（顶层白名单字段）

```jsonc
{
  "schema_version": 1,
  "name": "站点名",
  "desc": "备注",
  "enabled": true,
  "order": 150,
  "host_suffix": ["example.com"],     // 命中域名（含子域）
  "short_hosts": [],
  "cookies": "jar",                    // 或 "none"
  "session": { "referer": "...", "user_agent": "...", "headers": {"Accept-Language": "..."} },
  "identity": { "any_of": [ [ {"name": "vid", "pattern": "/video/(\\d+)"} ] ] },  // 从 URL 抽变量
  "steps":   [ { "url": "https://api.example.com/play?id={vid}", "as": "play",
                 "parse": {...}, "expect": [...] } ],
  "emit":    [ { "when": [...], "track": { "kind": "hls", "url": {"path": "play.data.url"} } } ],
  "episode_link": { "re": "^playGV(\\d+)-(\\d+)-(\\d+)$", "line": 2, "ep": 3 }   // 可选：分集链接形状
}
```

- `identity`：从输入网址里正则抽变量（如 `vid`），供后续 `{vid}` 拼接。
- `steps`：按顺序发请求取数据，`parse` 支持正则 / JSON / HTML 转义，`expect` 校验。
- `emit`：把数据映射成轨道（`track` / `produce` 多形态择优 / `pick` 选优 / `loop` 分集）。
- `episode_link`：告诉通用模式「本站分集链接长什么样」，让解析能从详情页自动跳到播放页、分集列表能认全（3 个捕获组：分组 / 线路 / 集）。

### 红线（校验直接拒绝）

配方**不生成签名、不解密、不破验证码、不执行脚本**。以下键名一旦出现即校验失败：
`sign / signature / a_bogus / wbi / decrypt / key / license / widevine / playready / fairplay / clearkey / captcha / eval / exec / code / script / js`。

接口要签名时，用配方里的 **`browser_seam`**（真浏览器旁观页面发出的请求），而不是自己复现签名。

> ⚠️ 见到 `#EXT-X-KEY:METHOD=SAMPLE-AES`、`skd://`、`edef8ba9`（Widevine）这类**商业 DRM**，属于做不了也不该做的范畴，直接弃站。

---

## 抓取参数一览

设置页可调项（括号内为默认值）：

**解析**：最大跳转层数(2)、页内静态扫描(开)、跟随 iframe/meta/分集页(开)、页面超时(20s)、
失败重试次数(2)、重试间隔基数(0.8s)、启用混淆解码(开)、最大解码轮数(8)、
每线路最少集数(2)、通用聚类最少锚点数(2)、额外分集链接正则(空)。

**下载**：分片并发(12)、整部并行集数(3)、失败重试(2)、Range 分段数(6)、Range 触发大小(8MB)、
Range 加速(开)、分片超时(30s)、解密方式(自动)。

**节流**：节流总开关(开)、单 host 并发(12)、全局并发(24)、自适应并发(开)。

**请求头**：User-Agent(内置默认)、Referer 覆盖(空)。

**后处理**：视频元数据打标(开)。

---

## 构建与打包

### 环境要求

- JDK 17+（本项目在 `%USERPROFILE%\.jdks\temurin-26.0.2.1` 上验证；`gradle.properties` 已指向该路径）
- Android SDK（含 `build-tools`，`local.properties` 里 `sdk.dir` 指向它）
- Gradle 用项目自带的 `gradlew`（wrapper）

### 命令行构建

```bash
# 编译 + 跑单测
./gradlew compileDebugKotlin testDebugUnitTest

# 打 debug 包
./gradlew assembleDebug

# 打 release 包（R8 混淆 + 资源压缩 + 签名，需 keystore.properties）
./gradlew assembleRelease
```

### 一键脚本（Windows）

| 脚本 | 作用 |
|---|---|
| `打包APK.bat` | 打 **debug** 包，产物复制到 `dist\梼杌-debug.apk` |
| `打包Release.bat` | 打 **release** 包（混淆+签名），复制到 `dist\梼杌-release.apk`，并导出 `mapping.txt` 供崩溃栈还原 |
| `还原崩溃栈.bat` | 用 `mapping.txt` + `d8.jar` 的 retrace 把 release 崩溃栈还原成源码行号 |

### 签名

release 签名读 **`keystore.properties`**（不存在则 release 不签名）：

```properties
storeFile=keystore/taowu-release.jks
storePassword=********
keyAlias=taowu
keyPassword=********
```

> ⚠️ `keystore.properties` 与 `keystore/` 含私钥口令，**不要提交到公开仓库**。

### 产物

- `dist\梼杌-debug.apk`（调试包，随装随用）
- `dist\梼杌-release.apk`（发布包，混淆+签名）
- `dist\梼杌-release-mapping.txt`（release 混淆映射，务必随版本归档，否则无法还原崩溃栈）

> **安装提示**：debug 包与 release 包同包名但签名不同，**不能互相覆盖安装**。
> 换包前先卸载旧版（`adb uninstall com.taowu`），否则可能装不上或仍跑旧包。

---

## 项目结构

源码根有两个（在 `build.gradle.kts` 的 `sourceSets` 里追加）：`UI/`（界面）与 `后端/`（后端），入口 `Main.kt` 在 `src/main/kotlin`。

```
taowu-android/
├── src/main/kotlin/
│   ├── Main.kt              # 进程入口 TaowuApplication + 界面入口 MainActivity
│   ├── GrabTasks.kt         # 应用级抓取任务托管（协程 + 状态流 + 取消）
│   ├── GrabService.kt       # 前台服务 + 进度通知（含「停止」按钮）
│   └── GrabStopReceiver.kt  # 通知栏「停止」广播接收器
├── UI/                      # Jetpack Compose 界面
│   ├── AppEntry.kt          # 导航骨架（底部三栏 + 子流程）
│   ├── BottomBar.kt
│   ├── CaptureScreen.kt     # 捕获页（内置浏览器）
│   ├── ResolveScreen.kt     # 解析页
│   ├── EpisodesScreen.kt    # 分集列表
│   ├── SeriesScreen.kt      # 整部抓取
│   ├── ContentScreen.kt     # 内容页（下载中/已下载）
│   ├── PlayerScreen.kt      # 播放页
│   ├── PlayerFactory.kt / PlayContext.kt
│   ├── SettingsScreen.kt    # 设置页（六页签）
│   └── Theme.kt             # 配色（深青底 + 虎纹金）
├── 后端/                    # 后端（Kotlin）
│   ├── BackendContainer.kt  # 装配引擎/注册表/解析器/下载器，载入配方
│   ├── MediaResolver.kt     # 解析编排（通用 / 配方）
│   ├── StaticExtract.kt     # 页内八趟静态扫描 + 混淆解码
│   ├── EpisodePlan.kt       # 分集/整部识别（MacCMS、额外正则）
│   ├── Grabber.kt           # 抓取编排层（单集/分集/整部，主力）
│   ├── Download.kt          # 下载栈（HLS/DASH/直链，Range，续传）
│   ├── StreamDecrypt.kt / CencDecryptor.kt / CbcsDecryptor.kt / ClearKey.kt  # 解密
│   ├── DashParser.kt        # DASH MPD 解析
│   ├── Remuxer.kt           # 分轨合流（Media3 Transformer）
│   ├── RecipeEngine.kt      # 配方引擎（跑 steps/emit）
│   ├── Registry.kt / SiteAdapter.kt / Schema.kt / RecipeInfo.kt  # 配方注册与校验
│   ├── NetEngine.kt / MediaHeaders.kt  # 网络与请求头
│   ├── Throttle.kt          # 并发闸门 + 自适应
│   ├── Naming.kt / Path.kt / MediaUtil.kt / VideoMeta.kt
│   └── Settings.kt          # AppSettings（SharedPreferences 持久化）
├── src/main/assets/recipes/ # 内置配方
├── src/test/kotlin/         # JVM 单测（EpisodePlan / SavedSources / StaticExtract / Resolver）
├── dist/                    # 打包产物
├── build.gradle.kts / settings.gradle.kts / proguard-rules.pro
├── 打包APK.bat / 打包Release.bat / 还原崩溃栈.bat
└── keystore.properties / keystore/
```

---

## 技术栈

- **语言 / UI**：Kotlin + Jetpack Compose（Material3），Navigation-Compose
- **播放 / 转码**：Media3（ExoPlayer + HLS + DASH + OkHttp data source + Transformer）
- **网络**：OkHttp
- **序列化**：kotlinx.serialization（JsonElement 动态解析）
- **并发**：kotlinx.coroutines（Flow / 结构化并发 / 协作式取消）
- **构建**：AGP 9.4.1 / Kotlin 2.4.20 / Gradle wrapper；release 用 R8 混淆 + 资源压缩

---

## 常见问题

**Q：点了按钮半天没结果？**
看解析页 `route = …` 那行说明：

- `页面获取失败（已重试 N 次）` → 抓不到页面（网络 / 被拒 / 超时）。
- `未发现可下载的视频` → 页面拿到了，但没解出流（可能是加密串、纯 iframe、或人机验证页）。
- `未命中配方（换通用模式试试）` → 配方模式没匹配到该站，用通用模式。

**Q：MacCMS 站（player_aaaa encrypt:2）抓不到？**
确认 设置 → 解析 → **「启用混淆解码」是开**、**「最大解码轮数」≥ 2**。

**Q：站内分了 `/playXXX-线-集/` 这种非标准形状，分集列表认不全？**
在 设置 → 解析 → **「额外分集链接正则」** 填对应形状（3 个捕获组：分组 / 线路 / 集），
或写进站点配方的 `episode_link` 字段。

**Q：浏览器里能看，点抓取却没结果？**
浏览器的 Cookie 与抓取引擎（OkHttp）各自独立。若该站靠 Cookie / 验证放行，抓取时可能被拦。

**Q：桌面图标还是旧的 / 显示成模板图标？**
Android 会缓存 App 图标，**覆盖安装常常不刷新**。卸载后重装（顽固时重启手机）。

**Q：release 崩溃了看不懂栈？**
用 `还原崩溃栈.bat`，配合对应版本的 `mapping.txt` 还原成源码行号。

---

## 已知限制

- 配方引擎**不执行脚本、不解密、不签名**（设计红线），所以「地址靠站点自身 JS 现算」的站（如带 `encrypt:2` 的播放页）只能走**通用模式**由程序自己解。
- 商业 DRM（Widevine / PlayReady / FairPlay / SAMPLE-AES）**不支持也不提供**。
- 浏览器与抓取引擎是两套网络栈，Cookie 不自动同步。
- 通用模式页内扫描对**纯 JS 渲染后才出现的地址**无能为力（需要配方或浏览器官旁取）。

---

## 来源与致谢

- 移植自 PC 版 **梼杌 v2.1**（Python + PyQt）。
- 配方体系、解析思路、下载/解密策略均继承自 PC 版；界面与线程模型为安卓重写。
