# PicoOnebot 协议端能力清单

> 依据：源码 `app/src/main/java/pico/onebot/action/*.kt` 的注册表 + 真机实测（v0.16 / 手表 QQ 9.0.7 / OPPO R11 Android 8.1）
> 版本：`PicoOnebot/0.16 (watch-qqnt)`，协议 `OneBot v11`，底层 `watch` 变体

---

## 一、支持的能力（36 个 action）

### 消息（6）

| action | 说明 | 备注 |
|---|---|---|
| `send_msg` | 通用发送 | 按 `message_type` 分发 |
| `send_group_msg` | 发群消息 | |
| `send_private_msg` | 发私聊 | |
| `delete_msg` | 撤回 | |
| `get_msg` | 取单条消息 | |
| `get_forward_msg` | **读合并转发** | 实测支持解析 |

### 合并转发（3）

| action | 说明 |
|---|---|
| `send_forward_msg` | 发合并转发 |
| `send_group_forward_msg` | 群合并转发 |
| `send_private_forward_msg` | 私聊合并转发 |

> ⚠️ 官方 README 提到：手表端**没有完整富媒体转发卡片接口**，发送时会**自动降级**成单条文本或连续消息。

### 信息查询（7）

| action | 说明 |
|---|---|
| `get_friend_list` | 好友列表 |
| `get_friend_info` | 单个好友 |
| `get_group_list` | 群列表 |
| `get_group_info` | 群信息 |
| `get_group_member_list` | **群成员列表** ← 关键 |
| `get_group_member_info` | 单个成员 |
| `get_stranger_info` | 陌生人信息 |

### 媒体（4）

| action | 说明 |
|---|---|
| `get_image` | 取图片 |
| `get_record` | 取语音 |
| `get_file` | 取文件 |
| `get_media` | 通用媒体 |

### 系统（16）

| action | 说明 |
|---|---|
| `get_version_info` | 版本信息 |
| `get_status` | 运行状态（含事件队列、消息管线统计） |
| `get_login_info` | 登录信息 |
| `get_self_info` | 自身信息 |
| `can_send_image` | 能否发图 |
| `can_send_record` | 能否发语音 |
| `logout` | 退出登录 |
| `set_restart` | 重启 |
| `get_login_qr` / `_pico_get_qr` | 拿登录二维码 |
| `_pico_refresh_qr` | 刷新二维码 |
| `_pico_show_panel` | 呼出面板 |
| `_pico_diagnostics` | 诊断 |
| `_pico_config` | 读写配置 |
| `_pico_logs` | 日志 |
| `_pico_logout` | 退出 WebUI |

> 下划线开头的是 PicoOnebot 私有扩展，不是 OneBot 标准。

---

## 二、支持的消息段

### 出站（能发）

| 段 | 说明 |
|---|---|
| `text` | 文本 |
| `at` | @某人 |
| `reply` | 引用回复 |
| `image` | 图片（url/path/base64） |
| `record` | 语音 |
| `video` | 视频 |
| `face` | QQ 表情（实测**不支持**，见下） |
| `mface` | 市场表情 |
| `json` | JSON 卡片 |
| `forward` | 合并转发节点 |

### 入站（能收）

`text` / `at` / `reply` / `image` / `record` / `video` / `face` / `mface` / `forward` / `json`

---

## 三、**不支持**的能力（重点）

### 群管理：全部缺失

```
set_group_ban            禁言
set_group_whole_ban      全员禁言
set_group_kick           踢人
set_group_admin          设置管理员
set_group_card           改群名片
set_group_name           改群名
set_group_leave          退群
set_group_special_title  设专属头衔
```

实测返回：`{"status":"failed","retcode":1404,"message":"unknown action or path: /set_group_ban"}`

**结论：任何依赖群管的 LismQQ 功能都无法实现。**

### 好友/请求处理：缺失

```
set_friend_add_request   处理加好友
set_group_add_request    处理加群请求
get_stranger_info        有，但 del_friend 之类没有
```

### 其他缺失

| 功能 | 状态 |
|---|---|
| 戳一戳（poke） | ❌ |
| 群公告 | ❌ |
| 精华消息 | ❌ |
| 群文件管理 | ❌ 只有 `get_file` 取，不能上传/删除 |
| 群相册 | ❌ |
| QQ 空间 | ❌ |
| 频道（guild） | ❌ |
| 红包 | ❌ |
| 合并转发**发送** | ⚠️ 降级 |
| 特种动画表情 | ❌ |
| 语音发送 | ⚠️ 有 `record` 段，但手表端能力受限 |
| 消息已读状态 | ❌ |
| `_handle_quick_operation` | ❌ |
| 群成员变动事件 | ⚠️ 部分 |

---

## 四、事件（入站）

从实测 `get_status` 的事件统计看得到：

```json
"event_queue": { "posted": 432, "delivered": 136, "no_consumer": 296 }
"msg_pipeline": { "enqueued": 42, "converted": 20, "dedup_size": 31 }
```

可确认收到的事件：

| post_type | 说明 |
|---|---|
| `message` | 私聊 / 群聊消息（`message_type`: `private` / `group`） |
| `message_sent` | 自己发出的消息（可配 `reportSelfMessage`） |
| `meta_event` | 生命周期 / 心跳 |
| `notice` | 通知类（**覆盖范围有限**） |

> 官方说明：群事件通知（成员增减、禁言等）支持不完整。

---

## 五、对 LismQQ 功能移植的影响

### ✅ 可以直接实现（LismQQ 核心能力）

| LismQQ 能力 | 依赖的 action | 可行性 |
|---|---|---|
| 收消息 → 调模型 → 回复 | `message` 事件 + `send_group_msg` / `send_private_msg` | ✅ |
| 会话记忆 | 无依赖，纯本地 | ✅ |
| 人设系统 | 无依赖 | ✅ |
| 白名单 | 无依赖 | ✅ |
| 长期记忆池（bigram） | 无依赖 | ✅ |
| 一代仿真（四相状态机） | 纯逻辑 | ✅ |
| 二代仿真（自主收发/巡逻/睡眠） | 纯逻辑 + 定时器 | ✅ |
| 读群历史 | `get_group_msg_history` **❌ 没有** | ❌ |
| 读未读消息 | 无对应 action | ❌ |
| 群成员称呼（@人/叫名字） | `get_group_member_list` | ✅ |
| 引用回复 | `reply` 段 | ✅ |
| 连发多条 | 多次 `send_group_msg` | ✅ |
| 图片处理 | `get_image` + 接收 `image` 段 | ⚠️ 收图可以，发图可以 |
| 合并转发解析 | `get_forward_msg` | ✅ |

### ⚠️ 需要注意的差距

1. **没有 `get_group_msg_history`**
   LismQQ 的 `qq_get_group_history` 工具无法实现 → 机器人看不到「进群之前的聊天」

2. **没有「已读」概念**
   LismQQ 的 `qq_read_unread` 工具无法实现

3. **群管全无**
   如果 LismQQ 的仿真里有「禁言刷屏的人」这类逻辑，要删掉

4. **事件粒度粗**
   只知道「有消息」，不知道「谁进群/退群/被踢」

---

## 六、结论

**PicoOnebot 的能力边界 = 一个「纯聊天机器人」**

- ✅ 收发文本、@、图片、引用、语音、视频、文件
- ✅ 群列表、群成员列表、好友列表
- ✅ 合并转发读取
- ❌ 一切群管理
- ❌ 群历史消息
- ❌ 已读状态
- ❌ 戳一戳、公告、精华、文件管理

**对 LismQQ 移植的判断：约 70% 的能力可以搬（仿真、记忆、人设、白名单全都可以），
但群管类、历史消息类、已读类的功能必须砍掉。**

对于一个「在群里像群友一样聊天」的机器人，这个能力集**够用**喵。
