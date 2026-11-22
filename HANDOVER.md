# SuzuBot 项目交接文档

> 交接对象：Codex（或其他接手的 AI / 开发者）
> 交接时间：2026-11-20
> 项目仓库：https://github.com/CRSYYDS/SuzuBot
> 当前版本：**v1.1.4**
> 许可证：GPL-3.0

---

## 0. 一句话说明

**SuzuBot 是一个 Android 上的「QQ 机器人启动器 + AI 后端」**，把闲置旧手机变成 7×24 在线的 QQ 机器人。它本身**不实现 QQ 协议**，而是内置并拉起 [PicoOnebot](https://github.com/Exmeaning/PicoOnebot)（基于手表 QQ 的 OneBot v11 协议端），再通过本地 HTTP/WS 与之通信。

目标用户：Android 7.0+ / 约 4GB 运存的旧手机，追求**轻量**（实测整机约 200MB，对比 NapCat 方案 450–650MB）。

---

## 1. 项目位置与工具链

| 项 | 路径 / 值 |
|---|---|
| 工程根目录 | `D:\dsh-workspace\qqbot-wrapper` |
| 仓库 | `https://github.com/CRSYYDS/SuzuBot.git`，主分支 `main` |
| 包名 / namespace | `com.crsyyds.suzubot` |
| minSdk / targetSdk / compileSdk | 24 (Android 7.0) / 35 / 35 |
| versionName | `1.1.4` |
| versionCode | `(System.currentTimeMillis() / 60_000L).toInt()`（时间戳，**不能用于 Google Play**） |
| Kotlin / AGP / Gradle | 2.0.21 / 8.7.3 / 8.9 |
| 构建产物 | `app/build/outputs/apk/release/SuzuBot-v1.1.4.apk`（约 60.6 MB，正式签名） |

**本机工具链（Windows，不在 PATH，需手动设环境变量）：**
```
JAVA_HOME   = D:\dsh-workspace\tools\jdk-17.0.21+9
ANDROID_HOME= D:\dsh-workspace\tools\sdk
adb         = D:\dsh-workspace\tools\sdk\platform-tools\adb.exe
aapt2/apksigner = D:\dsh-workspace\tools\sdk\build-tools\35.0.0\
gradle      = D:\dsh-workspace\tools\gradle-8.9\bin\gradle.bat
git         = C:\Program Files\Git\cmd\git.exe
```

**常用命令：**
```bash
# 编译 release
gradle :app:assembleRelease --no-daemon --console=plain

# 跑单元测试（86 个，JVM + Robolectric，约 4 秒）
gradle :app:testDebugUnitTest --no-daemon --console=plain
# 报告：app/build/reports/tests/testDebugUnitTest/index.html

# 装到真机（本机主用户为 0。其他设备先确认用户 ID，见 §6 第 10 条）
adb install --user 0 -r <apk>
# Windows 项目脚本会自动取得当前前台用户 ID。安装 test-only APK 时加 -AllowTestApk
pwsh -File ./scripts/install-adb.ps1 -ApkPath <apk> -AdbPath <adb.exe>

# 从指定用户卸载 SuzuBot（完整清理顺序见 §6）
adb shell pm uninstall --user 0 com.crsyyds.suzubot
```

**git 推送**：走 HTTP 代理（Shadowsocks 的 privoxy 端口会变，用 `Get-NetTCPConnection` 找 `ss_privoxy` 的端口，然后 `git config --global http.proxy http://127.0.0.1:<port>`）。Release 上传用 GitHub API + `git credential fill` 取 token。

---

## 2. 架构总览

```
┌─────────────────────────────────────────────┐
│  SuzuBot (com.crsyyds.suzubot)              │
│  ┌───────────────┐  ┌───────────────────┐  │
│  │ 部署向导       │  │ 主页 / 模型 / 人设│  │  ← Compose UI
│  │ SetupFlow      │  │ ViewModel 层     │  │
│  └───────┬───────┘  └────────┬──────────┘  │
│          │                   │              │
│  ┌───────▼───────────────────▼──────────┐  │
│  │ BotService（前台服务，保活+看护）    │  │
│  │ BotRuntime（单例持有 BotEngine）     │  │
│  └───────────────┬───────────────────────┘  │
│                  │                            │
│  ┌───────────────▼───────────────────────┐  │
│  │ AI 后端（纯 Kotlin 重写 LismQQ）     │  │
│  │  ChatClient / FallbackChatClient      │  │
│  │  MemoryPool / SessionStore           │  │
│  │  SocialV1 / RateLimiter / WakeScheduler│ │
│  │  QqTools（12 个工具 + function call）│  │
│  │  ModelChain（多模型回退）            │  │
│  │  PersonaStore（多人设）              │  │
│  └───────────────┬───────────────────────┘  │
│                  │ HTTP :6099 / WS :3001     │
│  ┌───────────────▼───────────────────────┐  │
│  │ PicoOnebotClient（控制台 API 客户端）│  │
│  │ OneBotClient（OneBot v11 WS 客户端） │  │
│  └───────────────────────────────────────┘  │
└─────────────────────┬───────────────────────┘
                      │ 127.0.0.1（本机）
┌─────────────────────▼───────────────────────┐
│  PicoOnebot (com.tencent.qqlite)           │  ← 独立进程，内置 APK
│  手表 QQ + Hook 注入层 + WebUI(:6099)      │
│  + OneBot v11 WS Server(:3001)             │
└─────────────────────────────────────────────┘
```

**关键点：**
- **两个独立 App、两个进程**。SuzuBot 通过 `assets/runtime/PicoOnebot-0.16.apk`（50MB，未压缩 `noCompress`）安装协议端；桌面只保留 SuzuBot 一个图标（协议端 APK 已去掉 LAUNCHER）。
- **不能把两者源码合并成一个 APK**：QQ 必须叫 `com.tencent.qqlite`、跑在独立进程（`:MSF`），且有腾讯专有二进制与签名校验。
- 通信全在 `127.0.0.1`：控制台 API `:6099`，OneBot WS `:3001`。

---

## 3. 源码地图（33 个 .kt / 约 9900 行）

| 目录 | 职责 |
|---|---|
| `App.kt` | Application，初始化 `CredentialStore`、崩溃日志落盘 |
| `setup/SetupFlow.kt` | **核心**：8 步部署向导状态机，设备安装协议端、初始化密码、检测登录 |
| `setup/PrivacyHelper.kt` | 拉起/切回协议端、判断 PicoOnebot 是否运行 |
| `pico/PicoOnebotClient.kt` | 6099 控制台 HTTP API 客户端（status/config/qr/webui token/logs） |
| `pico/CredentialStore.kt` | **控制台密码的单一可信源**（内存缓存 + `commit()` 同步落盘） |
| `runtime/Installer.kt` | `PackageInstaller` 无感安装协议端 |
| `runtime/RuntimeDownloader.kt` | 从 assets 复制 / 回退下载协议端 APK |
| `runtime/InstallResultReceiver.kt` | 安装结果广播 |
| `backend/BotEngine.kt` | AI 后端主循环（记忆、会话、四相、工具调用） |
| `backend/BotRuntime.kt` | 单例持有 BotEngine，`@Synchronized` |
| `backend/ChatClient.kt` / `FallbackChatClient.kt` | OpenAI 兼容接口调用 + 回退 |
| `backend/ModelChain.kt` | 多模型链、预设端点、旧配置迁移 |
| `backend/OneBotClient.kt` | OneBot v11 正向/反向 WS 客户端 |
| `backend/QqTools.kt` | 12 个 QQ 工具 + function calling |
| `backend/MediaLoader.kt` | 图片压缩转 base64（多模态） |
| `memory/MemoryPool.kt` | CJK bigram 倒排记忆池、序列化 |
| `memory/SessionStore.kt` | 会话持久化 |
| `persona/PersonaStore.kt` | 多人设 CRUD |
| `social/SocialV1.kt` | 四相状态机、触发概率、分条发送 |
| `social/RateLimiter.kt` | 分档限频（发送/唤醒） |
| `social/WakeScheduler.kt` | 唤醒调度 |
| `service/BotService.kt` | 前台服务、15s 看护、配置重新下发 |
| `ui/**` | Compose 界面（PCL 风格暗色 + 青色），home / setup / models / persona / theme / components |

**测试（5 个文件 / 1018 行 / 86 个用例，全绿）：**
`ModelChainTest` `MemoryPoolTest` `CredentialStoreTest` `RateLimiterTest` `SocialV1Test`

---

## 4. 部署向导 8 步（`SetupFlow.Step`）

```
0 检查设备        检查 Android 版本 / 32 位 ABI / 存储空间
1 安装协议端      从 assets 复制 → ensure() → PackageInstaller 无感安装
2 启动协议端      跳转 PicoOnebot（系统 BAL 限制，无法静默不跳前台）
3 初始化控制台    needsPasswordInit() 探 428 → PUT /api/webui/token 设确定性密码
4 在 PicoOnebot 里扫码登录   跳转到协议端界面扫码（SuzuBot 内不显示二维码），轮询状态
5 连接机器人后端  下发 wsServers 等配置到 :6099
6 配置 AI         用户填 API Key
7 完成 → 主页
```

---

## 5. 必须记住的技术契约（踩过坑，别改错）

### PicoOnebot 控制台 API（:6099）
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/status` | GET | 需 `Authorization: Bearer <token>`；**无认证 401、`picopico` 全新→428 `mustChangePassword`、密码已改→401** |
| `/api/auth/login` | POST | body `{"token": "<密码>"}`（**字段是 `token` 不是 `password`**） |
| `/api/webui/token` | PUT | body `{"token": "<新密码>"}`，用 `picopico` 认证时初始化 |
| `/api/config` | GET/PUT | 配置字段是 **`wsServers`**（不是 websocketServers），需齐 4 个数组：`wsServers/wsClients/httpServers/httpClients` |
| `/api/qr`, `/api/qr/refresh` | GET/POST | 二维码 |
| `/api/restart` | POST | 重启 QQ |
| `/api/logs/stream?token=` | WS | 日志流 |

### AI 模型（实测校准，别凭记忆）
- **DeepSeek**：`base_url=https://api.deepseek.com`（**不带 /v1**），`model=deepseek-flash`，**必须发 `thinking:{"type":"disabled"}`** 否则 `content` 为空、`reasoning_content` 才有内容。`deepseek-chat` 已下线，别用。**DeepSeek 无视觉**。
- **智谱**：`https://open.bigmodel.cn/api/paas/v4`，免费档 `glm-4-flash`；视觉仅 `glm-4v-flash` / `glm-4.6v-flash` / `glm-4.6v` / `glm-4v-plus`。

### 设备/系统
- 目标机型实测：OPPO R11（Android 8.1 / sdm660 / 3.83GB），协议端 PSS 5–14 MB，整机约 200 MB。
- 协议端**只有 armeabi-v7a（32 位）原生库**，纯 64 位且无转译层的手机（Pixel 7+ / 骁龙 8 Elite）装不了。开源转译层 [ZettaBridge](https://github.com/zailoxtt/zettabridge) 仍在早期开发，不可直接用。
- Android BAL（后台启动 Activity）限制导致「启动协议端」必然跳到 PicoOnebot 界面，用户需**按返回键**回 SuzuBot；这是系统限制，无法规避。
- 隐私政策「同意」需在 PicoOnebot 界面点一次；`hasFragment` 必须为 true。

### 凭据 / 签名
- 控制台初始密码 `picopico`，首次 `initializePassword` 后改成**设备指纹推导的确定性密码**（`devicePassword()`，同设备重装也能算回来）。
- release 签名：`suzubot-release.jks`，DN `CN=SuzuBot, OU=CRSYYDS`，SHA-256 `5c2c2e59dde9000f9e59ecd3d127233d70f2e9a87510563f10c757bcadf268e8`。
- `keystore.properties` / `suzubot-release.jks` / `local.properties` **已 gitignore，切勿提交**。模板是 `keystore.properties.example`。

---

## 6. 反复踩过的坑（务必读）

1. **卸载顺序**：要彻底清干净，必须**先卸 SuzuBot 再卸 PicoOnebot**，否则 SuzuBot 检测到协议端没了会自动重装。还要检查 `/data/user_de/0/com.tencent.qqlite`（设备加密存储，普通卸载不清，密码藏这里）——按用户卸掉（见第 10 条）后确认 6 个数据目录都没了。

2. **`?.` 静默吞失败**：`CredentialStore` 早期 `init()` 从未被调用，`prefs=null`，`prefs?.edit()?.putString(...)?.apply()` 全程静默失败，密码只进内存不落盘，App 一重启就报「密码不匹配」。修复：`App.onCreate` + `SetupFlow.init` + `ensurePrefs()` 三处初始化兜底，并改 `commit()` 同步落盘。**改代码时别再让 null 被 `?.` 吃掉**。

3. **删步骤要查依赖**：删掉「准备协议端」步骤时，把唯一负责 `downloader.ensure()` 从 assets 复制 APK 的环节一起删了，导致「找不到安装包」。现在 `installProtocol()` 里主动调 `ensure()`。

4. **assets 里的 APK 不能压缩**：`androidResources { noCompress += listOf("apk") }`。压缩后 `assets.openFd()` 不可用，只能流式解压，慢且低端机易 OOM；而且 50MB 压完只省 1.5MB，不值。

5. **装两次**：`awaitPackageInstalled` 超时曾返回 `NeedUserAction("继续")` 让用户重复触发安装。现已改成超时 30s 且无论如何都往下走。

6. **SuperNotCalledException**：`SetupActivity.onCreate` 里提前 `return` 跳过了 `super.onCreate()`，直接崩溃。现在 `super.onCreate` 放第一行。

7. **ColorOS 的 `PackageInstaller.uninstall()` 会让系统包安装器崩溃**（`system_app_crash`），已删除该实现；重置改为跳系统「应用信息」页让用户手动「清除数据」。

8. **二维码页重复**：SuzuBot 里别再显示登录二维码，首次登录必须在 PicoOnebot 界面点「同意」，用户在那边扫。删掉了 `SetupActivity` 的二维码 UI，改成跳转+轮询 `/api/status`。

9. **日志**：release 包 `run-as` 不可用（not debuggable）；`pm clear` 需要 `CLEAR_APP_USER_DATA`，adb 无权限。中文在 PowerShell 控制台会乱码，但那是显示问题，不影响文件。

10. **ADB 安装要指定 Android 用户。** 2026-11-20 在 Android 16 的 Moto XT2537-4 上实测，运行 `adb install <apk>` 后，SuzuBot 同时注册到主用户 `0` 和应用分身 `900`、`901`。ADB 省略 `--user` 时默认安装到设备上的全部用户。后续用 §1 的项目脚本，或先用 `adb shell am get-current-user` 确认 ID，再显式传入 `--user`。 [Android ADB 文档](https://developer.android.com/tools/adb)

    在这台测试机上，确认 `900`、`901` 为分身后，用下列命令移除分身里的 SuzuBot。`-k` 保留各自的数据和缓存。

    ```powershell
    adb shell pm uninstall -k --user 900 com.crsyyds.suzubot
    adb shell pm uninstall -k --user 901 com.crsyyds.suzubot
    ```

    用 `adb shell dumpsys package com.crsyyds.suzubot` 核对各用户的 `installed` 状态。协议端与原版手表 QQ 共用 `com.tencent.qqlite` 包名，清理协议端前需要确认目标安装确实是 PicoOnebot。

---

## 7. 当前状态与已知限制

### 已完成
- ✅ 内置协议端一体包、正式签名、GitHub Release 流程
- ✅ 86 个单元测试（v1.1.4 补齐）
- ✅ README / LICENSE(GPL-3.0) / THIRD_PARTY_NOTICES / docs
- ✅ 主页高亮引导、运维常驻展开

### 已知限制 / 待办
- ❌ **零 instrumented test（androidTest）**，纯 JVM 单测覆盖不了 UI 与真实设备交互
- ❌ **无 CI/CD**：靠手工构建 + GitHub API 发版（代理端口会变）
- ❌ 硬编码端口 6099 / 3001（协议端改端口就崩，概率低）
- ❌ 593 处硬编码中文，未国际化（目标用户为中文用户，可接受）
- ❌ 无崩溃上报、无灰度
- ❌ 协议端升级需重新发 App（内置 APK 版本固定 0.16）
- ❌ `versionCode` 用时间戳，无法上架 Google Play（本项目也不上）
- ❌ **纯 ARM64 真机直装受限。** 2026-11-20 在 Moto XT2537-4（Android 16）实测，系统只报 `arm64-v8a`（`zygote64`、`native.bridge=0`）。主用户安装原样 PicoOnebot 0.16 返回 `INSTALL_FAILED_NO_MATCHING_ABIS`，设备上没有安装协议端。SuzuBot 的纯 ARM64 模式需要已经运行的 PicoOnebot 实例。
- ✅ 同机覆盖安装 SuzuBot 后，向导显示「连接 PicoOnebot」及缺少运行环境的提示，清单已移除跳过的安装步骤。SuzuBot 仅安装于主用户 0。
- ✅ **ARM32 转译基础验证。** 同一台设备上，`liba32android` 探针执行合成 ARM32 指令返回 42，并只读扫描原包里的 52 个库，扫描前后的 SHA-256 一致。上游 ELF 加载测试也通过，两个合成 ARM32 库完成依赖加载、重定位和调用，返回 123。PicoOnebot 的 Android 应用进程仍缺可用的本机运行器，扫码与收发未进入实测。
- 🧪 **独立 ARM64 运行器原型。** `compat/arm64-runner` 提供基于 Armbat v0.1.4 的宿主补丁和构建脚本。已验证补丁可应用，并在本机验证原包校验通过、同尺寸的错误文件被拒绝。宿主完整构建仍待补齐上游子模块，访客 `:MSF` 服务路由与端到端流程待验证。
- ⚠️ 用户真机端到端验证主要靠人工，改动 `SetupFlow` 后务必完整跑一遍 8 步

### Release 历史
```
v1.0.0  初版
v1.1.0  内置协议端首发
v1.1.1  五项体验改进
v1.1.2  修复内置协议端找不到安装包
v1.1.3  修复密码丢失与内存泄漏
v1.1.4  补齐 86 个单测 + 修复 4 个 bug（当前）
```

---

## 8. 给接手者的建议

1. **先跑一遍测试**：`gradle :app:testDebugUnitTest`，确认 86 个全绿再动代码。
2. **改 `SetupFlow` / `CredentialStore` / `MemoryPool` 前先看对应测试**，这几个模块都有回归保护，测试失败说明行为变了，先想清楚是预期还是 bug。
3. **任何涉及密码/安装的改动，必须用真机完整走一遍 8 步**，这套流程历史上出过最多问题。
4. 想进一步成熟：补 instrumented test、加 GitHub Actions CI、把端口做成可配置、加协议端版本检测。
5. **不要试图把 PicoOnebot 源码并进 SuzuBot**（见 §2），这是死路。
6. 用户是中文用户，UI 文案直接写中文即可；提交代码前主人不喜欢自作主张的 merge commit，用 rebase 同步远程改动。

---

## 9. 环境里的其他上下文

- 主人有 5 台服务器（见 `~/.dsh/servers.md`），但与本项目无关。
- 本机 DSH 工具链、adb 路径见 §1。
- 项目不涉及任何密钥硬编码：API Key 由用户在 App 内填写，存在设备本地。
- 测试账号（仅供调试，别提交）：QQ `2194058988`。

---

*交接完毕。对任何拿不准的行为，以「真机实测 + 单元测试」为准，不要凭印象改。*
