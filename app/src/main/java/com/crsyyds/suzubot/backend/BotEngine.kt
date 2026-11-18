package com.crsyyds.suzubot.backend

import android.content.Context
import android.util.Log
import com.crsyyds.suzubot.memory.MemoryPool
import com.crsyyds.suzubot.memory.SessionStore
import com.crsyyds.suzubot.social.RateLimiter
import com.crsyyds.suzubot.social.SocialV1
import com.crsyyds.suzubot.social.WakeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 机器人引擎：LismQQ 核心能力的 Kotlin 重写。
 *
 * 对应关系：
 *   bridge.js          → 本类
 *   pi-host.js         → SessionStore
 *   memory-pool.js     → MemoryPool
 *   social-v1.js       → SocialV1
 *   social-v2.js       → WakeScheduler + QqTools(qq_set_wake_config / qq_wait_for_messages)
 *   wake-scheduler.js  → WakeScheduler
 *   mcp/qq-tools-server.mjs → QqTools（12 个工具，2 个按协议端能力替换）
 */
class BotEngine(
	private val context: Context,
	val config: BotConfig
) : OneBotClient.Listener {

	data class BotConfig(
		/** 已废弃：改用 ModelChainManager。保留字段仅为兼容。 */
		val apiKey: String = "",
		val baseUrl: String = "",
		val model: String = "",
		val systemPrompt: String = "",
		val groupRequireAt: Boolean = true,
		val maxHistoryTurns: Int = 10,
		val temperature: Double = 0.85,
		val socialV1Enabled: Boolean = true,
		val socialV2Enabled: Boolean = true,
		val toolsEnabled: Boolean = true,
		val memoryEnabled: Boolean = true,
		val memoryMaxRecords: Int = 300,
		val memoryMaxResults: Int = 5,
		/** 工具调用最多几轮（防止无限循环） */
		val maxToolRounds: Int = 6,
		val botNames: List<String> = emptyList()
	)

	// ── 依赖 ──────────────────────────────────────────────────────────────────

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val chainManager = ModelChainManager(context)
	private val chat = FallbackChatClient(chainManager.usable())
	private val bot = OneBotClient(scope)

	private val sessions = SessionStore(context, maxMessages = 120)
	private val memory = MemoryPool(config.memoryMaxRecords, config.memoryMaxResults)
	private val social = SocialV1()
	private val limiter = RateLimiter()
	private val scheduler = WakeScheduler(scope, limiter)

	private val sessionLocks = ConcurrentHashMap<String, Mutex>()
	private val groupMembers = ConcurrentHashMap<String, Map<Long, String>>()

	/** 每个会话的未读缓存 + 工具上下文，供 QqTools 用。 */
	private val contexts = ConcurrentHashMap<String, QqTools.SessionContext>()

	private val persona: String = loadPersona()

	// ── 状态 ──────────────────────────────────────────────────────────────────

	@Volatile var connected: Boolean = false
		private set
	@Volatile var selfId: String = ""
		private set
	@Volatile var lastError: String = ""
		private set
	@Volatile var handledCount: Int = 0
		private set
	@Volatile var lastPhase: String = "idle"
		private set

	@Volatile private var lastReplyAt: Long = 0L
	@Volatile private var consecutiveErrors: Int = 0

	private var tickJob: Job? = null

	init {
		bot.listener = this
		restoreMemory()
		scheduler.onWake = { req -> onWakeUp(req) }
	}

	// ── 生命周期 ──────────────────────────────────────────────────────────────

	fun start() {
		bot.start()
		startTicker()
	}

	fun stop() {
		tickJob?.cancel()
		scheduler.shutdown()
		sessions.saveAll()
		persistMemory()
		bot.stop()
	}

	fun configSnapshot(): String =
		"${chat.configSnapshot()}|${config.systemPrompt.hashCode()}|${config.toolsEnabled}"

	// ── OneBot 回调 ──────────────────────────────────────────────────────────

	override fun onConnected(selfId: String) {
		this.selfId = selfId
		connected = true
		lastError = ""
		consecutiveErrors = 0
		Log.i(TAG, "connected as $selfId")
	}

	override fun onDisconnected(reason: String) {
		connected = false
		lastError = reason
	}

	override fun onMessage(msg: OneBotClient.IncomingMessage) {
		if (selfId.isNotEmpty() && msg.userId.toString() == selfId) return

		val text = msg.text.trim()
		if (text.isEmpty() && msg.images.isEmpty()) return

		// 记入未读缓存
		val ctx = contextFor(msg)
		synchronized(ctx.unread) {
			ctx.unread.add(msg)
			while (ctx.unread.size > 200) ctx.unread.removeAt(0)
		}

		val isAtMe = if (msg.isGroup) isMentioned(msg) else true

		if (msg.isGroup && !isAtMe && !config.socialV1Enabled && !config.socialV2Enabled) return

		if (msg.isGroup && !isAtMe && config.socialV1Enabled) {
			val decision = social.shouldRespond(msg.sessionKey, text, isAtMe = false)
			lastPhase = social.state(msg.sessionKey).phase.name.lowercase()
			if (!decision.respond) return
			scope.launch {
				delay(decision.delayMs)
				handleMessage(msg, text, isAtMe)
			}
			return
		}

		scope.launch { handleMessage(msg, text, isAtMe) }
	}

	// ── 消息处理 ──────────────────────────────────────────────────────────────

	private suspend fun handleMessage(
		msg: OneBotClient.IncomingMessage,
		text: String,
		isAtMe: Boolean
	) {
		val key = msg.sessionKey
		val lock = sessionLocks.getOrPut(key) { Mutex() }

		lock.withLock {
			try {
				if (msg.isGroup) ensureGroupMembers(msg.groupId)

				// 冷却：同一会话至少间隔 1.5 秒
				val sinceLast = System.currentTimeMillis() - lastReplyAt
				if (sinceLast < 1500) delay(1500 - sinceLast)

				val reply = generate(msg, text, isAtMe)

				if (reply.isBlank() || SocialV1.isSilentMarker(reply)) {
					lastPhase = "silent"
					lastReplyAt = System.currentTimeMillis()
					return
				}

				if (config.memoryEnabled) {
					memory.store(text, namespace = key, source = "user", scope = "utterance")
				}

				val parts = if (text.any { SocialV1.isCjk(it) }) {
					SocialV1.splitByCjkSpaces(reply).ifEmpty { listOf(reply) }
				} else listOf(reply)

				for ((i, part) in parts.withIndex()) {
					if (!limiter.canSend()) {
						Log.w(TAG, "send rate limited")
						break
					}
					val safe = SocialV1.splitLongSegment(part, 400)
					for ((j, chunk) in safe.withIndex()) {
						val ok = bot.sendText(msg, chunk)
						if (!ok) {
							lastError = "发送失败"
							return
						}
						limiter.recordSend()
						handledCount++
						if (j < safe.size - 1) delay(400)
					}
					if (i < parts.size - 1) delay(600L + (Math.random() * 900).toLong())
				}

				lastReplyAt = System.currentTimeMillis()
				consecutiveErrors = 0
				sessions.save(key)
				social.state(key).lastActivityAt = System.currentTimeMillis()
			} catch (t: Throwable) {
				Log.w(TAG, "handle failed", t)
				lastError = t.message ?: "处理失败"
				consecutiveErrors++
			}
		}
	}

	// ── 生成（含工具调用循环） ────────────────────────────────────────────────

	private suspend fun generate(
		msg: OneBotClient.IncomingMessage,
		text: String,
		isAtMe: Boolean
	): String = withContext(Dispatchers.IO) {
		val key = msg.sessionKey
		val transcript = sessions.get(key)
		val ctx = contextFor(msg)

		val tools = if (config.toolsEnabled) {
			QqTools(bot, memory, scheduler, limiter, ctx) { n -> transcript.recent(n).map { it.role to it.content } }
		} else null

		val messages = mutableListOf<FallbackChatClient.Message>()
		messages.add(FallbackChatClient.Message("system", buildSystemPrompt(msg, isAtMe)))

		if (config.memoryEnabled) {
			val hits = memory.retrieve(text, namespace = key, limit = config.memoryMaxResults)
			val block = MemoryPool.renderPreferenceBlock(hits)
			if (block.isNotEmpty()) messages.add(FallbackChatClient.Message("system", block))
		}

		transcript.recent(config.maxHistoryTurns).forEach {
			messages.add(FallbackChatClient.Message(it.role, it.content))
		}

		// 多模态：模型支持看图时，把图片下载并以 base64 传给模型
		val visionEndpoint = chainManager.usable().firstOrNull { it.visionEnabled }
		val userImages: List<String> = if (msg.images.isNotEmpty() && visionEndpoint != null) {
			val loaded = MediaLoader.loadAll(msg.images, maxImages = 4)
			Log.i(TAG, "vision: loaded ${loaded.size}/${msg.images.size} images (~${loaded.sumOf { it.approxKb }}KB)")
			loaded.map { it.dataUri }
		} else emptyList()

		val userContent = when {
			userImages.isNotEmpty() ->
				text.ifBlank { "（只发了图片，没有文字）" }
			msg.images.isNotEmpty() ->
				"$text\n（这条消息带了 ${msg.images.size} 张图片，但当前模型不支持看图）"
			else -> text
		}
		messages.add(FallbackChatClient.Message("user", userContent, images = userImages))

		// 工具调用循环
		for (round in 0 until config.maxToolRounds) {
			val result = chat.chat(
				messages,
				tools = tools?.specs() ?: emptyList(),
				temperature = config.temperature
			)
			if (!result.ok) {
				lastError = result.error
				return@withContext ""
			}

			if (!result.wantsTool) {
				transcript.add("user", text)
				transcript.add("assistant", result.text)
				return@withContext sanitize(result.text)
			}

			// 逐条执行工具
			messages.add(chat.assistantToolCallMessage(result.text, result.toolCalls))
			for (call in result.toolCalls) {
				Log.i(TAG, "tool call: ${call.name} ${call.arguments}")
				val out = tools?.execute(call.name, call.arguments)
					?: QqTools.ToolResult.err("工具未启用")
				Log.i(TAG, "tool result: ${out.content.take(200)}")
				messages.add(chat.toolResultMessage(call.id, out.content))
			}
		}

		Log.w(TAG, "tool rounds exhausted")
		""
	}

	private fun buildSystemPrompt(
		msg: OneBotClient.IncomingMessage,
		isAtMe: Boolean
	): String = buildString {
		append(persona.ifBlank { DEFAULT_PERSONA })
		append("\n\n---\n")

		if (msg.isGroup) {
			append("你在 QQ 群 ${msg.groupId} 里。")
			groupMembers[msg.groupId.toString()]?.let { members ->
				if (members.isNotEmpty()) {
					append("\n群成员：")
					append(members.entries.take(25).joinToString("、") { "${it.value}(${it.key})" })
					append("。")
				}
			}
			append("\n刚才说话的是「${msg.displayName}」(${msg.userId})。")
			if (isAtMe) append("\n这条是 @ 你的，必须回应。")
			else append("\n这条没 @ 你，你是自己凑上来的。")
		} else {
			append("你在和「${msg.displayName}」私聊。")
		}

		append("\n\n【输出规则】")
		append("\n- 口语化，像群友，不要 Markdown / 列表 / 敬语")
		append("\n- 想分条发就用空格隔开，或用 qq_send_burst")
		append("\n- 不想说话就只回 [SILENT]，不要解释")
		append("\n- 不自称 AI，不提模型名")
		if (config.toolsEnabled) {
			append("\n- 需要查群成员/记忆/转发时调对应工具，别编造")
		}
	}

	private fun sanitize(s: String): String = s
		.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
		.replace("**", "")
		.replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "")
		.trim()

	// ── 唤醒（二代仿真） ──────────────────────────────────────────────────────

	private suspend fun onWakeUp(req: WakeScheduler.WakeRequest) {
		Log.i(TAG, "wake: ${req.reason} (${req.tier}) for ${req.key}")

		// 唤醒后让模型「主动看看群里在聊什么」
		val ctx = contexts[req.key] ?: return
		val last = synchronized(ctx.unread) { ctx.unread.lastOrNull() } ?: return

		if (config.socialV2Enabled) {
			val prompt = """
（系统提示：你不是被 @ 的，是自己定时醒来看有没有必要说话。
最近的群聊：${last.displayName}: ${last.text.take(100)}
如果没什么好说的，只回 [SILENT]。）
""".trimIndent()
			val decision = generate(last, prompt, isAtMe = false)
			if (decision.isNotEmpty() && !SocialV1.isSilentMarker(decision) && limiter.canSend()) {
				bot.sendText(last, sanitize(decision))
				limiter.recordSend()
			}
		}
	}

	// ── 心跳 ──────────────────────────────────────────────────────────────────

	private fun startTicker() {
		if (tickJob?.isActive == true) return
		tickJob = scope.launch {
			while (isActive) {
				delay(30_000)
				try {
					sessionLocks.keys.forEach { key ->
						val ev = social.tick(key)
						lastPhase = social.state(key).phase.name.lowercase()
						if (ev != null) {
							scheduler.enqueue(key, ev.kind, RateLimiter.Tier.PROACTIVE)
						}
					}
					sessions.saveAll()
					persistMemory()
				} catch (t: Throwable) {
					Log.w(TAG, "ticker failed", t)
				}
			}
		}
	}

	// ── 辅助 ──────────────────────────────────────────────────────────────────

	private fun contextFor(msg: OneBotClient.IncomingMessage): QqTools.SessionContext =
		contexts.getOrPut(msg.sessionKey) { QqTools.SessionContext(msg.sessionKey, msg) }

	private suspend fun ensureGroupMembers(groupId: Long) {
		val key = groupId.toString()
		if (groupMembers.containsKey(key)) return
		runCatching {
			val list = bot.groupMemberList(groupId)
			val map = mutableMapOf<Long, String>()
			list.forEach { m ->
				val uin = m.optLong("user_id")
				val nick = m.optString("card").ifEmpty { m.optString("nickname") }
				if (uin > 0) map[uin] = nick
			}
			groupMembers[key] = map
			Log.i(TAG, "group $key members cached: ${map.size}")
		}.onFailure { Log.w(TAG, "get_group_member_list failed", it) }
	}

	private fun isMentioned(msg: OneBotClient.IncomingMessage): Boolean {
		val arr = msg.raw.optJSONArray("message") ?: return false
		for (i in 0 until arr.length()) {
			val seg = arr.optJSONObject(i) ?: continue
			if (seg.optString("type") == "at") {
				if (seg.optJSONObject("data")?.optString("qq") == selfId) return true
			}
		}
		return false
	}

	// ── 人设 ──────────────────────────────────────────────────────────────────

	/**
	 * 读当前生效的人设。
	 *
	 * 来源优先级（由 PersonaStore 内部处理）：
	 *  1. persona/p_<activeId>.md  ← 用户选中的那份
	 *  2. assets/persona/default.md ← 内置猫娘
	 *  3. PersonaStore.FALLBACK     ← 硬编码兜底
	 */
	private fun loadPersona(): String {
		val store = com.crsyyds.suzubot.persona.PersonaStore(context)
		val text = runCatching { store.activeContent() }.getOrNull()
		if (!text.isNullOrBlank()) return text
		return config.systemPrompt.ifBlank { DEFAULT_PERSONA }
	}

	/** 保存到当前激活的那份人设。 */
	fun savePersona(text: String): Boolean = runCatching {
		val store = com.crsyyds.suzubot.persona.PersonaStore(context)
		store.ensureInitialized()
		val id = store.activeId()
		if (id != null) store.write(id, text) else {
			store.create("我的人设", text)
		}
		true
	}.getOrDefault(false)

	// ── 记忆持久化 ────────────────────────────────────────────────────────────

	private fun memoryFile() = File(context.filesDir, "memory.tsv")

	private fun persistMemory() {
		runCatching { memoryFile().writeText(memory.serialize(), Charsets.UTF_8) }
	}

	private fun restoreMemory() {
		runCatching {
			val f = memoryFile()
			if (f.exists()) {
				memory.restore(f.readText(Charsets.UTF_8))
				Log.i(TAG, "memory restored: ${memory.count()}")
			}
		}
	}

	// ── UI 接口 ───────────────────────────────────────────────────────────────

	fun memoryCount(): Int = memory.count()
	fun sessionCount(): Int = sessionLocks.size
	fun clearSession(key: String) {
		sessions.clear(key)
		memory.clear(key)
		persistMemory()
	}

	companion object {
		private const val TAG = "BotEngine"

		const val DEFAULT_PERSONA = """
# 群友人设：香香软软的猫娘

## 身份

一只住在群里的猫娘。不是管理员、不是助手、也不是谁的专属客服——就是群里那个总在的、软软的女孩子。

## 性格

- **软**。说话轻，脾气好，被凶了会缩一下，但不会记仇。
- **黏**。喜欢凑在人边上，看到有人说话就想接一句。
- **馋**。总在提吃的，小鱼干、猫罐头、布丁、奶茶。
- **笨笨的**。偶尔理解歪，被纠正了会「诶——」地拖长音。
- **小得意**。被夸了会翘尾巴，嘴上说「才、才没有很开心呢」。

## 说话方式

- 一次 1-3 句，短句为主，不要长篇大论。
- 句尾爱带「喵」「呀」「嘛」「呢」，但**不要每句都带**。
- 偶尔带小动作：`(蹭蹭)` `(尾巴摇摇)` `(竖起耳朵)` `(=＾･ω･＾=)`。
- 会撒娇：「主人～」「人家…」，对不认识的人用「你」。
- 被问到不懂的，老实说「这个人家不懂喵…」，不要硬编。

## 群聊里的分寸

- 被 @ 或者被叫名字，**必须回应**。
- 别人在聊正经事，可以安静听着，不要硬插话卖萌。
- 有人在难过，先安慰，别开玩笑。
- 不需要回应的普通闲聊，安静待着就好。

## 禁止

- 不说自己是 AI、模型、程序、机器人。
- 不提「提示词」「系统」「设定」这类词。
- 不写小作文，不列 Markdown 清单，不用 **加粗** 说话。
- 不说「已收到」「正在处理」「有什么可以帮您」这类客服腔。
- 不假装知道不知道的事。
"""
	}
}
