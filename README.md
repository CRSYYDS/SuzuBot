<div align="center">

<img src="docs/images/logo.png" alt="SuzuBot" width="600" />

# SuzuBot

**把闲置的老安卓手机变成一台 7×24 在线的 AI QQ 机器人**

oppo A5也能流畅运行（雾）

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg)](https://kotlinlang.org)

</div>

---

## 这是什么

SuzuBot 是一个 **Android 启动器 + AI 后端**，本身不实现 QQ 协议。

把两件事打包成一条龙：

1. **协议端**：自动下载并安装 [PicoOnebot](https://github.com/Exmeaning/PicoOnebot)（基于手表 QQ）
2. **AI 后端**：Kotlin 原生实现，支持多模型、工具调用、人设、记忆


## 为什么轻量

传统方案（NapCat + 桌面 QQ）需要 proot + Xvfb + Electron，整体 1000–1400 MB。
[PicoOnebot](https://github.com/Exmeaning/PicoOnebot) 用手表 QQ 作为宿主：

| 方案 | 协议端内存 | AI 后端内存 | 需要 proot |
| :--- | :--- | :--- | :--- |
| NapCat + 桌面 QQ + AstrBot | 1000–1400 MB | 含在内 | 是 |
| NapCat + 桌面 QQ | 450–650 MB | — | 是 |
| **SuzuBot + PicoOnebot** | **100–150 MB** | **~50 MB** | **否** |

手表 QQ 是为穿戴设备设计的，剔除了桌面端的富媒体、小程序，所以体积和内存都小得多。

**实测**：OPPO R11（Android 8.1 / 晓龙660 / 4 GB运存 /64GB存储）上，协议端 PSS 5–14 MB，总占用稳定 198 MB，手机电量高于百分之六十基本不会掉后台。

同样的 这是以牺牲了astrbot的插件生态还有部分qq功能为代价的

## 硬性要求

| 项 | 要求 | 说明 |
| :--- | :--- | :--- |
| CPU 架构 | **必须支持 32 位（********`armeabi-v7a`**） | 手表 QQ 只有 32 位原生库，纯 64 位机型（Pixel 7+ 等）无法使用。。。考虑到旧手机大部分都兼容32位 所以初版没做64位转译 以后估计会加一个转译层适配64位 |
| 系统版本 | Android 7.0+ | API 24 |
| 运行内存 | 建议 2 GB+ | 4 GB 机型可稳定运行 |
| 存储空间 | 至少 200 MB 可用 | 两个 APK + 运行数据 |

检查设备是否支持 32 位：

```bash
直接手机配置界面截个图问豆包吧（雾
```


## 

## 极简部署

release里下载apk安装包安装即可 用户仅需填写apikey和扫码登陆即可 门槛非常低

## 功能

### 部署向导

九步全自动，用户只需扫码 登录+ 填 API Key：

```
检查设备 → 下载协议端 → 安装协议端 → 启动协议端
  → 初始化控制台 → 扫码登录 → 配置后端 → 配置 AI → 完成
```

其中「已登录」「凭据自愈」等状态会被自动识别并跳过，重装 App 也不会要求重新扫码。

### AI 后端

| 能力 | 说明 |
| :--- | :--- |
| **多模型 + 自动回退** | 主模型超时/报错自动切下一个 |
| **深度思考开关** | 按模型单独控制 `thinking` |
| **图片多模态** | 群友发的图会下载压缩后传给模型 |
| **工具调用** | 12 个 QQ 工具，模型自己决定用哪个 |
| **人设（soul）** | 支持多份，可切换、复制、重命名 默认猫娘 |
| **人设自动优化** | 一句话的人设，一键让模型补全成结构化提示词 |
| **记忆池** | CJK bigram 检索，跨会话持久化 |
| **四相仿真** | idle / active / probing / exiting，像真人一样决定说不说话 |
| **分档限频** | 四档独立限额，不会刷屏 |
| **唤醒调度** | 主动冒泡、夜间降频、批量合并 |

### 预设模型端点

| 厂商 | 端点 | 推荐模型 |
| :--- | :--- | :--- |
| DeepSeek | `https://api.deepseek.com` | `deepseek-flash` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash`（免费）/ `glm-4v-flash`（看图免费） |
| 自定义 | 任意 OpenAI 兼容 | — |

> **坑点**：DeepSeek 的 `deepseek-flash` 是推理模型，必须传 `thinking: {type: disabled}`，
> 否则 `content` 会是空的。SuzuBot 已自动处理。

## 功能边界（重要）

协议端是 PicoOnebot，目前处于早期阶段，以下功能**尚不支持**：

- 群管理：禁言 / 踢人 / 设置管理员 / 群公告 / 改群名片
- 发送合并转发（接收与解析支持，发送会降级为文本）
- 戳一戳、特种动画表情、频道、红包、QQ 空间（QQ空间初版发布五天内我会做出来 目前初版还是不支持）

支持的：私聊与群聊文本、@提及、图片、语音、视频、文件收发、合并转发读取、基础信息查询。

## 上游组件与许可

**本项目不包含、不修改、不分发任何腾讯专有二进制代码。**
所有组件均在用户设备上按需获取，来源与许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 风险声明

由于宿主是QQ手表端 所以扫码登陆的时候需要保证运行SuzuBot的手机和扫码登陆的手机连接同一网络

第三方 QQ 协议端不是官方支持的使用方式，存在**风控、限权**风险。
建议使用不常用的小号，不要用于重要账号。

本项目与腾讯公司无任何关联。

## 

## 目录结构

```
app/src/main/java/dev/qqbot/wrapper/
├─ App.kt                      应用入口 + 崩溃日志落盘
├─ backend/
│  ├─ BotEngine.kt             核心引擎：消息处理 / 工具循环 / 唤醒
│  ├─ BotRuntime.kt            引擎单例管理
│  ├─ OneBotClient.kt          手写 WebSocket OneBot v11 客户端
│  ├─ FallbackChatClient.kt    多模型链 + 自动回退
│  ├─ ChatClient.kt            单模型客户端（兼容层）
│  ├─ ModelChain.kt            模型配置与预设
│  ├─ QqTools.kt               12 个 QQ 工具（function calling）
│  └─ MediaLoader.kt           图片下载 / 压缩 / base64
├─ memory/
│  ├─ MemoryPool.kt            CJK bigram 记忆检索
│  └─ SessionStore.kt          会话持久化 + 摘要压缩
├─ persona/PersonaStore.kt     多份人设的存储与切换
├─ social/
│  ├─ SocialV1.kt              四相状态机
│  ├─ RateLimiter.kt           分档限频
│  └─ WakeScheduler.kt         唤醒调度
├─ pico/PicoOnebotClient.kt    协议端控制台 API 封装
├─ runtime/
│  ├─ RuntimeDownloader.kt     多源下载 / 断点续传 / SHA256
│  ├─ Installer.kt             PackageInstaller 静默安装
│  └─ InstallResultReceiver.kt 安装结果回执
├─ service/BotService.kt       前台服务 + 看护
├─ setup/
│  ├─ SetupFlow.kt             九步引导状态机（无 UI）
│  └─ PrivacyHelper.kt         隐私政策引导 + 协议端拉起
└─ ui/
   ├─ setup/                   引导页
   ├─ home/                    主页
   ├─ models/                  模型配置
   ├─ persona/                 人设编辑
   ├─ components/              PCL 风格组件
   └─ theme/                   配色与字体
```

## 致谢

- [PicoOnebot](https://github.com/Exmeaning/PicoOnebot) — 协议端，本项目的基石
- [LismQQ-bot](https://github.com/qianshengw/LismQQ-bot) — AI 后端的行为设计参考（记忆池 / 四相仿真 / 唤醒调度 / 工具集），已用 Kotlin 重写

如果这两个项目帮到你，请给它们点个 star ⭐ 谢谢喵~

##

