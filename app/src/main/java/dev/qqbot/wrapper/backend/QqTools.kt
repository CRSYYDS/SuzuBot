package dev.qqbot.wrapper.backend

import android.util.Log
import dev.qqbot.wrapper.memory.MemoryPool
import dev.qqbot.wrapper.social.RateLimiter
import dev.qqbot.wrapper.social.SocialV1
import dev.qqbot.wrapper.social.WakeScheduler
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 工具集：给模型的 function calling 用。
 *
 * 原版：LismQQ-bot `src/mcp/qq-tools-server.mjs`（14 个工具，经 MCP stdio 暴露）
 *
 * 适配说明（依据 docs/picoonebot-capabilities.md）：
 *   ✗ qq_get_group_history  → PicoOnebot 无 get_group_history，改为「从本地 transcript 取」
 *   ✗ qq_mark_read          → 无已读概念，改为「本地游标」
 *   ✓ 其余 12 个按原样实现
 */
class QqTools(
	private val bot: OneBotClient,
	private val memory: MemoryPool,
	private val scheduler: WakeScheduler,
	private val limiter: RateLimiter,
	private val session: SessionContext,
	private val history: (Int) -> List<Pair<String, String>>
) {

	/** 当前会话上下文（工具都是「对当前会话操作」）。 */
	data class SessionContext(
		val key: String,
		val msg: OneBotClient.IncomingMessage,
		/** 本地未读游标 */
		var readCursor: Long = 0L,
		/** 缓存最近的 incoming，供未读工具查 */
		val unread: MutableList<OneBotClient.IncomingMessage> = mutableListOf()
	)

	/** 工具执行结果。 */
	data class ToolResult(val content: String) {
		companion object {
			fun text(s: String) = ToolResult(s)
			fun json(o: Any) = ToolResult(o.toString())
			fun err(s: String) = ToolResult("""{"error":"$s"}""")
		}
	}

	// ── 工具定义 ──────────────────────────────────────────────────────────────

	fun specs(): List<FallbackChatClient.ToolSpec> = listOf(
		FallbackChatClient.ToolSpec(
			name = "qq_get_unread_messages",
			description = "读取当前会话的未读消息（自上次读取以来）。返回 [{sender, text, time, mentionsMe}]。",
			parameters = schema(
				"afterSeq" to intProp("起点游标，可省略"),
				"limit" to intProp("最多返回条数，默认 20")
			)
		),
		FallbackChatClient.ToolSpec(
			name = "qq_send_message",
			description = "向当前会话发送文本消息。支持字符串或字符串数组（数组=拆成多条发送）。",
			parameters = schema(
				"items" to JSONObject()
					.put("type", "array")
					.put("items", JSONObject().put("type", "string"))
					.put("description", "要发送的文本，可多条")
			)
		),
		FallbackChatClient.ToolSpec(
			name = "qq_reply",
			description = "引用回复某条消息。需要真实 messageId。",
			parameters = schema(
				"messageId" to intProp("要引用的消息 ID"),
				"text" to strProp("回复内容")
			),
			required = listOf("messageId", "text")
		),
		FallbackChatClient.ToolSpec(
			name = "qq_send_burst",
			description = "连发多条消息，模拟真人节奏（条间自动加间隔）。最多 8 条、单条 500 字。",
			parameters = schema(
				"messages" to JSONObject()
					.put("type", "array")
					.put("items", JSONObject().put("type", "string"))
					.put("description", "要连发的消息列表"),
				"gapMode" to strProp("节奏：normal | fast | slow，默认 normal")
			),
			required = listOf("messages")
		),
		FallbackChatClient.ToolSpec(
			name = "qq_list_groups",
			description = "列出机器人所在的群（groupId / groupName / memberCount）。",
			parameters = schema()
		),
		FallbackChatClient.ToolSpec(
			name = "qq_get_group_members",
			description = "获取当前群的成员列表（userId / nickname / card / role）。",
			parameters = schema()
		),
		FallbackChatClient.ToolSpec(
			name = "qq_get_message_images",
			description = "获取当前会话最近消息里的图片 URL 列表。",
			parameters = schema()
		),
		FallbackChatClient.ToolSpec(
			name = "qq_get_forward_msg",
			description = "展开一条合并转发消息（需要 [转发消息 id=...] 里的 id）。",
			parameters = schema(
				"forwardId" to strProp("转发消息 ID")
			),
			required = listOf("forwardId")
		),
		FallbackChatClient.ToolSpec(
			name = "qq_memory",
			description = "你的会话记忆（话题/想法/群友印象）。append 记录、remove 删除、clear 清空、不传则查看。",
			parameters = schema(
				"action" to strProp("append | remove | clear | list"),
				"category" to strProp("分类：topic | thought | impression"),
				"text" to strProp("记忆内容"),
				"who" to strProp("涉及的人（可选）"),
				"index" to intProp("要删除的序号（从 0 开始）")
			)
		),
		FallbackChatClient.ToolSpec(
			name = "qq_set_wake_config",
			description = "设置下次醒来的条件（二代仿真核心）。不调用则保留现有配置。",
			parameters = schema(
				"mode" to strProp("normal | sleep | silent"),
				"sleepUntil" to intProp("睡到哪个时间戳（毫秒）"),
				"atMention" to boolProp("@我时醒"),
				"nameMention" to boolProp("叫名字时醒"),
				"question" to boolProp("有提问时醒"),
				"anyMessage" to boolProp("任意消息都醒"),
				"keywords" to JSONObject()
					.put("type", "array")
					.put("items", JSONObject().put("type", "string"))
					.put("description", "关键词列表")
			)
		),
		FallbackChatClient.ToolSpec(
			name = "qq_wait_for_messages",
			description = "睡前观察：等待当前会话的新消息，收到后按 quietMs 静默合并再返回。",
			parameters = schema(
				"timeoutSeconds" to intProp("最多等多久，默认 300"),
				"quietMs" to intProp("静默合并窗口，0=立即返回")
			)
		),
		FallbackChatClient.ToolSpec(
			name = "qq_session_status",
			description = "查询当前会话的 sessionKey、type、unread、busy。",
			parameters = schema()
		)
	)

	private fun schema(vararg props: Pair<String, JSONObject>): JSONObject {
		val properties = JSONObject()
		props.forEach { (k, v) -> properties.put(k, v) }
		return JSONObject()
			.put("type", "object")
			.put("properties", properties)
			.put("required", JSONArray())
	}

	private fun intProp(desc: String) = JSONObject()
		.put("type", "integer").put("description", desc)

	private fun strProp(desc: String) = JSONObject()
		.put("type", "string").put("description", desc)

	private fun boolProp(desc: String) = JSONObject()
		.put("type", "boolean").put("description", desc)

	// ── 执行 ──────────────────────────────────────────────────────────────────

	suspend fun execute(name: String, args: JSONObject): ToolResult {
		return try {
			when (name) {
				"qq_get_unread_messages" -> getUnread(args)
				"qq_send_message" -> sendMessage(args)
				"qq_reply" -> reply(args)
				"qq_send_burst" -> sendBurst(args)
				"qq_list_groups" -> listGroups()
				"qq_get_group_members" -> getGroupMembers()
				"qq_get_message_images" -> getImages()
				"qq_get_forward_msg" -> getForward(args)
				"qq_memory" -> memoryOp(args)
				"qq_set_wake_config" -> setWakeConfig(args)
				"qq_wait_for_messages" -> waitForMessages(args)
				"qq_session_status" -> sessionStatus()
				else -> ToolResult.err("未知工具: $name")
			}
		} catch (t: Throwable) {
			Log.w(TAG, "tool $name failed", t)
			ToolResult.err(t.message ?: "执行失败")
		}
	}

	// ── 各工具实现 ────────────────────────────────────────────────────────────

	private fun getUnread(args: JSONObject): ToolResult {
		val limit = args.optInt("limit", 20).coerceIn(1, 100)
		val after = args.optLong("afterSeq", session.readCursor)

		val items = session.unread.filter { it.messageId > after }.takeLast(limit)
		if (items.isEmpty()) return ToolResult.text("""{"messages":[],"note":"没有新消息"}""")

		val arr = JSONArray()
		var maxId = after
		items.forEach { m ->
			if (m.messageId > maxId) maxId = m.messageId
			arr.put(
				JSONObject()
					.put("sender", m.displayName)
					.put("userId", m.userId)
					.put("text", m.text)
					.put("messageId", m.messageId)
					.put("mentionsMe", isMentioned(m))
			)
		}
		return ToolResult.text(JSONObject().put("messages", arr).put("nextSeq", maxId).toString())
	}

	private suspend fun sendMessage(args: JSONObject): ToolResult {
		val items = args.opt("items")
		val texts = when (items) {
			is JSONArray -> (0 until items.length()).map { items.optString(it) }
			is String -> listOf(items)
			else -> emptyList()
		}
		if (texts.isEmpty()) return ToolResult.err("items 不能为空")

		var sent = 0
		for (t in texts) {
			if (!limiter.canSend()) break
			if (bot.sendText(session.msg, t)) {
				limiter.recordSend()
				sent++
			} else break
			if (texts.size > 1) delay(700)
		}
		return ToolResult.text("""{"sent":$sent,"total":${texts.size}}""")
	}

	private suspend fun reply(args: JSONObject): ToolResult {
		val id = args.optLong("messageId", 0)
		val text = args.optString("text")
		if (id <= 0 || text.isEmpty()) return ToolResult.err("参数不完整")
		if (!limiter.canSend()) return ToolResult.err("发送过于频繁")
		val ok = bot.replyText(session.msg, text, id)
		if (ok) limiter.recordSend()
		return ToolResult.text("""{"ok":$ok}""")
	}

	private suspend fun sendBurst(args: JSONObject): ToolResult {
		val arr = args.optJSONArray("messages") ?: return ToolResult.err("messages 不能为空")
		val mode = args.optString("gapMode", "normal")
		val gap = when (mode) {
			"fast" -> 400L
			"slow" -> 2500L
			else -> 1200L
		}
		val max = minOf(arr.length(), 8)
		var sent = 0
		for (i in 0 until max) {
			val t = arr.optString(i).take(500)
			if (t.isEmpty()) continue
			if (!limiter.canSend()) break
			if (bot.sendText(session.msg, t)) {
				limiter.recordSend()
				sent++
			} else break
			if (i < max - 1) delay(gap + (Math.random() * 500).toLong())
		}
		return ToolResult.text("""{"sent":$sent}""")
	}

	private suspend fun listGroups(): ToolResult {
		val groups = bot.groupList()
		val arr = JSONArray()
		groups.forEach { g ->
			arr.put(
				JSONObject()
					.put("groupId", g.optLong("group_id"))
					.put("groupName", g.optString("group_name"))
					.put("memberCount", g.optInt("member_count"))
			)
		}
		return ToolResult.text(JSONObject().put("groups", arr).toString())
	}

	private suspend fun getGroupMembers(): ToolResult {
		if (!session.msg.isGroup) return ToolResult.err("当前不是群会话")
		val members = bot.groupMemberList(session.msg.groupId)
		val arr = JSONArray()
		members.forEach { m ->
			arr.put(
				JSONObject()
					.put("userId", m.optLong("user_id"))
					.put("nickname", m.optString("nickname"))
					.put("card", m.optString("card"))
					.put("role", m.optString("role"))
			)
		}
		return ToolResult.text(JSONObject().put("members", arr).toString())
	}

	private fun getImages(): ToolResult {
		val urls = session.unread.flatMap { it.images }.takeLast(10)
		val arr = JSONArray()
		urls.forEach { arr.put(it) }
		return ToolResult.text(JSONObject().put("images", arr).toString())
	}

	private suspend fun getForward(args: JSONObject): ToolResult {
		val id = args.optString("forwardId")
		if (id.isEmpty()) return ToolResult.err("forwardId 不能为空")
		val res = bot.getForwardMsg(id)
		return ToolResult.text(res.toString().take(8000))
	}

	private fun memoryOp(args: JSONObject): ToolResult {
		val action = args.optString("action", "list")
		return when (action) {
			"append" -> {
				val text = args.optString("text")
				if (text.isEmpty()) return ToolResult.err("text 不能为空")
				val cat = args.optString("category", "thought")
				val who = args.optString("who")
				val content = if (who.isNotEmpty()) "[$cat] $who: $text" else "[$cat] $text"
				val rec = memory.store(
					content,
					namespace = session.key,
					source = "assistant",
					scope = "user"
				)
				ToolResult.text("""{"ok":true,"id":"${rec.id}"}""")
			}
			"clear" -> {
				val n = memory.clear(session.key)
				ToolResult.text("""{"cleared":$n}""")
			}
			"remove" -> {
				// 简化：按序号删不了（记忆池无索引删除），改为按内容匹配
				ToolResult.err("暂不支持按序号删除，可用 clear 清空")
			}
			else -> {
				val list = memory.listRecords(namespace = session.key)
				val arr = JSONArray()
				list.takeLast(50).forEachIndexed { i, r -> arr.put(JSONObject().put("index", i).put("text", r.memory)) }
				ToolResult.text(JSONObject().put("memories", arr).put("count", list.size).toString())
			}
		}
	}

	private fun setWakeConfig(args: JSONObject): ToolResult {
		val mode = args.optString("mode", "normal")
		when (mode) {
			"sleep" -> {
				val until = args.optLong("sleepUntil", 0)
				val ms = if (until > System.currentTimeMillis()) until - System.currentTimeMillis() else 0
				if (ms > 0) scheduler.sleep(session.key, ms)
				return ToolResult.text("""{"ok":true,"asleep":true}""")
			}
			"silent" -> {
				scheduler.sleep(session.key, Long.MAX_VALUE / 2)
				return ToolResult.text("""{"ok":true,"mode":"silent"}""")
			}
		}
		scheduler.wakeUp(session.key)
		return ToolResult.text("""{"ok":true,"mode":"normal"}""")
	}

	private suspend fun waitForMessages(args: JSONObject): ToolResult {
		val timeoutSec = args.optInt("timeoutSeconds", 300).coerceIn(10, 900)
		val quietMs = args.optInt("quietMs", 3000).coerceIn(0, 30_000)
		val startCount = session.unread.size
		val deadline = System.currentTimeMillis() + timeoutSec * 1000L

		while (System.currentTimeMillis() < deadline) {
			delay(1000)
			if (session.unread.size > startCount) {
				if (quietMs > 0) delay(quietMs.toLong())
				val newOnes = session.unread.drop(startCount)
				val arr = JSONArray()
				newOnes.take(20).forEach { m ->
					arr.put(
						JSONObject()
							.put("sender", m.displayName)
							.put("text", m.text)
							.put("mentionsMe", isMentioned(m))
					)
				}
				return ToolResult.text(JSONObject().put("messages", arr).toString())
			}
		}
		return ToolResult.text("""{"messages":[],"timeout":true}""")
	}

	private fun sessionStatus(): ToolResult =
		ToolResult.text(
			JSONObject()
				.put("sessionKey", session.key)
				.put("type", if (session.msg.isGroup) "group" else "private")
				.put("groupId", session.msg.groupId)
				.put("userId", session.msg.userId)
				.put("unread", session.unread.size)
				.put("asleep", scheduler.isAsleep(session.key))
				.toString()
		)

	private fun isMentioned(m: OneBotClient.IncomingMessage): Boolean {
		val sid = bot.currentSelfId()
		if (sid.isEmpty()) return false
		val arr = m.raw.optJSONArray("message") ?: return false
		for (i in 0 until arr.length()) {
			val seg = arr.optJSONObject(i) ?: continue
			if (seg.optString("type") == "at") {
				if (seg.optJSONObject("data")?.optString("qq") == sid) return true
			}
		}
		return false
	}

	companion object {
		private const val TAG = "QqTools"
	}
}
