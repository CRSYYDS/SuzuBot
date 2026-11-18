package com.crsyyds.suzubot.social

import kotlin.random.Random

/**
 * 一代仿真：四相状态机。
 *
 * 原版：LismQQ-bot `src/social-v1.js`
 *
 * 状态流转：
 *   idle（观望）  → 按概率参与普通消息
 *   active（活跃）→ 选择性沉默（skipProbability），有活跃时长上限
 *   probing（试探）→ 冷场时主动抛话题
 *   exiting（退场）→ 逐渐淡出
 *
 * AI 用 `[SILENT]` 标记表示「这条我不回」。
 */
class SocialV1(
	private val limits: Limits = Limits()
) {

	data class Limits(
		/** 观望期普通消息触发活跃的概率 */
		val triggerProbability: Double = 0.1,
		/** 活跃期非必回消息的沉默概率 */
		val skipProbability: Double = 0.15,
		/** 近期消息窗口（条） */
		val contextWindow: Int = 20,
		val activeCheckMinMs: Long = 10_000,
		val activeCheckMaxMs: Long = 30_000,
		val activeReplyDelayMinMs: Long = 2_000,
		val activeReplyDelayMaxMs: Long = 8_000,
		val activeDurationEnabled: Boolean = true,
		val activeDurationMinMs: Long = 15 * 60 * 1000,
		val activeDurationMaxMs: Long = 30 * 60 * 1000,
		/** 活跃期冷场阈值 */
		val idleWindowMs: Long = 6 * 60 * 1000,
		/** 冷场试探概率 */
		val idleRetryProbability: Double = 0.25,
		val idleRetryWaitMs: Long = 2 * 60 * 1000,
		val maxReplyChars: Int = 500,
		val burstEnabled: Boolean = true,
		val mustReplyKeywords: List<String> = listOf(
			"deepseek", "ai", "人工智能", "机器人", "模型"
		),
		/** 退场兜底 */
		val exitingTimeoutMs: Long = 60 * 60 * 1000,
		val summaryMaxItems: Int = 60,
		val silentContextMax: Int = 30
	)

	enum class Phase { IDLE, ACTIVE, PROBING, EXITING }

	data class State(
		var phase: Phase = Phase.IDLE,
		var phaseSince: Long = System.currentTimeMillis(),
		/** 最后一次有人说话的时间 */
		var lastActivityAt: Long = System.currentTimeMillis(),
		var lastProbeAt: Long = 0L,
		/** 当前活跃期的计划结束时间 */
		var activeUntil: Long = 0L
	)

	private val states = HashMap<String, State>()
	private val rnd = Random.Default

	// ── 状态读写 ──────────────────────────────────────────────────────────────

	@Synchronized
	fun state(key: String): State = states.getOrPut(key) { State() }

	@Synchronized
	fun setPhase(key: String, phase: Phase) {
		val st = state(key)
		st.phase = phase
		st.phaseSince = System.currentTimeMillis()
		if (phase == Phase.ACTIVE && limits.activeDurationEnabled) {
			st.activeUntil = System.currentTimeMillis() + rndDelay(
				limits.activeDurationMinMs, limits.activeDurationMaxMs
			)
		}
	}

	// ── 指向性判定（对齐原版 isDirectedAtAi 的正则族） ───────────────────────

	private val aiMentionRe = Regex(
		"deepseek|claude|chatgpt|gpt|机器人|小鱼|小鲸|d指令|d老师|d师姐|人工智能|\\bds\\b|\\bai\\b|人工智障|模型",
		RegexOption.IGNORE_CASE
	)

	/** 这条消息是否指向 AI（点名 / 挑战 / 直接问）。 */
	fun isDirectedAtAi(text: String, botNames: List<String> = emptyList()): Boolean {
		val s = text.trim()
		if (s.isEmpty()) return false
		if (aiMentionRe.containsMatchIn(s)) return true
		if (botNames.any { it.isNotBlank() && s.contains(it) }) return true
		// 直接问「你」
		if (Regex("^(你|您)(是不是|行不行|能不能|会不会|好不好|在|没在)").containsMatchIn(s)) return true
		if (Regex("(你|您).{0,10}(比|强|弱|牛|怎么样|是不是|能不能|行不行|有没有)").containsMatchIn(s)) return true
		return false
	}

	/** 是否属于「必须回」的关键词。 */
	fun mustReply(text: String): Boolean =
		limits.mustReplyKeywords.any { text.contains(it, ignoreCase = true) }

	// ── 决策 ──────────────────────────────────────────────────────────────────

	/**
	 * 收到群消息时，判断要不要参与。
	 *
	 * @return true = 应该回复；false = 这条不管
	 */
	@Synchronized
	fun shouldRespond(key: String, text: String, isAtMe: Boolean): Decision {
		val st = state(key)
		st.lastActivityAt = System.currentTimeMillis()

		// @ 我 / 私聊 → 必回，且直接把状态推到 active
		if (isAtMe) {
			if (st.phase != Phase.ACTIVE) setPhase(key, Phase.ACTIVE)
			return Decision(respond = true, delayMs = rndDelay(
				limits.activeReplyDelayMinMs, limits.activeReplyDelayMaxMs
			), reason = "direct")
		}

		// 必回关键词
		if (mustReply(text)) {
			return Decision(respond = true, delayMs = rndDelay(1000, 3000), reason = "keyword")
		}

		return when (st.phase) {
			Phase.IDLE -> {
				// 观望期：按概率参与
				if (rnd.nextDouble() < limits.triggerProbability) {
					setPhase(key, Phase.ACTIVE)
					Decision(true, rndDelay(2000, 6000), "idle-trigger")
				} else {
					Decision(false, 0, "idle-skip")
				}
			}

			Phase.ACTIVE -> {
				// 活跃期：选择性沉默
				if (rnd.nextDouble() < limits.skipProbability) {
					Decision(false, 0, "active-skip")
				} else {
					Decision(true, rndDelay(
						limits.activeReplyDelayMinMs, limits.activeReplyDelayMaxMs
					), "active")
				}
			}

			Phase.PROBING -> Decision(false, 0, "probing-skip")

			Phase.EXITING -> Decision(false, 0, "exiting-skip")
		}
	}

	data class Decision(val respond: Boolean, val delayMs: Long, val reason: String)

	/**
	 * 心跳：推进状态机。
	 *
	 * @return 需要主动说的话（probe = 抛话题，exit = 退场告别），null = 不用动
	 */
	@Synchronized
	fun tick(key: String, now: Long = System.currentTimeMillis()): TickEvent? {
		val st = state(key)

		when (st.phase) {
			Phase.ACTIVE -> {
				// 活跃超时 → 退场
				if (limits.activeDurationEnabled && st.activeUntil > 0 && now > st.activeUntil) {
					setPhase(key, Phase.EXITING)
					return TickEvent("exit")
				}
				// 冷场 → 试探
				if (now - st.lastActivityAt > limits.idleWindowMs &&
					now - st.lastProbeAt > limits.idleRetryWaitMs
				) {
					if (rnd.nextDouble() < limits.idleRetryProbability) {
						st.lastProbeAt = now
						setPhase(key, Phase.PROBING)
						return TickEvent("probe")
					}
				}
			}

			Phase.PROBING -> {
				// 试探没接话，等一段时间回退到 active
				if (now - st.phaseSince > limits.idleRetryWaitMs) {
					setPhase(key, Phase.ACTIVE)
				}
			}

			Phase.EXITING -> {
				// 兜底：超时后彻底回 idle
				if (now - st.phaseSince > limits.exitingTimeoutMs) {
					setPhase(key, Phase.IDLE)
				}
			}

			Phase.IDLE -> {
				// 观望期什么都不做
			}
		}
		return null
	}

	data class TickEvent(val kind: String)

	private fun rndDelay(min: Long, max: Long): Long {
		if (max <= min) return min
		return min + (Random.nextDouble() * (max - min)).toLong()
	}

	// ── 文本处理 ──────────────────────────────────────────────────────────────

	companion object {
		const val SILENT_MARKER = "[SILENT]"

		private val SILENT_RE = Regex(
			"^\\s*(?:\\[SILENT\\][\\s。.，,、!！?？~～]*)+$", RegexOption.IGNORE_CASE
		)

		/** AI 说「这条我不回」。 */
		fun isSilentMarker(text: String): Boolean = SILENT_RE.matches(text)

		fun stripSilentMarkers(text: String): String =
			text.replace("[SILENT]", "", ignoreCase = true).trim()

		/** CJK 之间用空格分隔多条消息（原版的「分条发送」约定）。 */
		fun splitByCjkSpaces(src: String): List<String> {
			val tokens = src.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
			if (tokens.isEmpty()) return emptyList()
			val groups = mutableListOf<String>()
			val cur = StringBuilder()
			for (t in tokens) {
				val prevLast = cur.lastOrNull()
				val currFirst = t.firstOrNull()
				// 两侧都是 CJK 或数字 → 合并（避免把「我 今天」拆开）
				if (prevLast != null && currFirst != null &&
					(isCjk(prevLast) || prevLast.isDigit()) && (isCjk(currFirst) || currFirst.isDigit())
				) {
					cur.append(' ')
					cur.append(t)
				} else {
					if (cur.isNotEmpty()) groups.add(cur.toString())
					cur.clear()
					cur.append(t)
				}
			}
			if (cur.isNotEmpty()) groups.add(cur.toString())
			return groups
		}

		fun isCjk(c: Char): Boolean {
			val code = c.code
			return (code in 0x3040..0x30FF) || (code in 0x3400..0x4DBF) ||
				(code in 0x4E00..0x9FFF) || (code in 0xAC00..0xD7AF)
		}

		/** 超长文本按字符（不是字节）安全切分，不切坏代理对。 */
		fun splitLongSegment(text: String, maxChars: Int): List<String> {
			if (text.length <= maxChars) return listOf(text)
			val out = mutableListOf<String>()
			var start = 0
			while (start < text.length) {
				var end = (start + maxChars).coerceAtMost(text.length)
				// 避免切断代理对
				if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
				if (end <= start) end = (start + maxChars).coerceAtMost(text.length)
				out.add(text.substring(start, end))
				start = end
			}
			return out
		}
	}
}
