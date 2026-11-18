package dev.qqbot.wrapper.social

import java.util.concurrent.ConcurrentHashMap

/**
 * 发送/唤醒限频。
 *
 * 原版：LismQQ-bot `src/wake-scheduler.js` 的 `limits` 部分
 *
 * 唤醒分四档，互不挤占：
 *   direct    显式请求（@我 / 私聊 / 戳）—— 不会被常规额度卡住
 *   followUp  短时跟随（我问过话、对方接话）
 *   normal    常规触发（提到名字 / 提问 / 关键词）
 *   proactive AI 自己的定时器（巡逻 / 回复检查 / 睡眠到点）
 */
class RateLimiter(
	private val cfg: Config = Config()
) {

	data class Config(
		val maxSendPerMinute: Int = 8,
		val maxSendPerHour: Int = 60,
		val maxDirectWakePerMinute: Int = 6,
		val maxDirectWakePerHour: Int = 120,
		val maxFollowUpWakePerMinute: Int = 3,
		val maxFollowUpWakePerHour: Int = 60,
		val maxWakePerMinute: Int = 1,
		val maxWakePerHour: Int = 12,
		val maxProactiveWakePerMinute: Int = 1,
		val maxProactiveWakePerHour: Int = 20
	)

	enum class Tier {
		DIRECT, FOLLOW_UP, NORMAL, PROACTIVE
	}

	/** 滑动窗口计数：只保留最近 1 小时的时间戳。 */
	private class Window {
		val stamps = ArrayDeque<Long>()

		fun add(now: Long) {
			stamps.addLast(now)
			trim(now)
		}

		fun trim(now: Long) {
			while (stamps.isNotEmpty() && now - stamps.first() > HOUR_MS) {
				stamps.removeFirst()
			}
		}

		fun countSince(now: Long, windowMs: Long): Int {
			var n = 0
			for (i in stamps.indices.reversed()) {
				if (now - stamps.elementAt(i) <= windowMs) n++ else break
			}
			return n
		}
	}

	private val sendWindow = Window()
	private val wakeWindows = ConcurrentHashMap<Tier, Window>()

	private fun windowOf(tier: Tier): Window = wakeWindows.getOrPut(tier) { Window() }

	/** 能否发一条消息。 */
	@Synchronized
	fun canSend(now: Long = System.currentTimeMillis()): Boolean {
		sendWindow.trim(now)
		if (sendWindow.countSince(now, MINUTE_MS) >= cfg.maxSendPerMinute) return false
		if (sendWindow.countSince(now, HOUR_MS) >= cfg.maxSendPerHour) return false
		return true
	}

	@Synchronized
	fun recordSend(now: Long = System.currentTimeMillis()) {
		sendWindow.add(now)
	}

	/** 能否在这一档唤醒。 */
	@Synchronized
	fun canWake(tier: Tier, now: Long = System.currentTimeMillis()): Boolean {
		val w = windowOf(tier)
		w.trim(now)
		val (perMin, perHour) = limitsOf(tier)
		if (w.countSince(now, MINUTE_MS) >= perMin) return false
		if (w.countSince(now, HOUR_MS) >= perHour) return false
		return true
	}

	@Synchronized
	fun recordWake(tier: Tier, now: Long = System.currentTimeMillis()) {
		windowOf(tier).add(now)
	}

	/** 带门禁的唤醒尝试：能就记一笔并返回 true。 */
	@Synchronized
	fun tryWake(tier: Tier, now: Long = System.currentTimeMillis()): Boolean {
		if (!canWake(tier, now)) return false
		recordWake(tier, now)
		return true
	}

	fun limitsOf(tier: Tier): Pair<Int, Int> = when (tier) {
		Tier.DIRECT -> cfg.maxDirectWakePerMinute to cfg.maxDirectWakePerHour
		Tier.FOLLOW_UP -> cfg.maxFollowUpWakePerMinute to cfg.maxFollowUpWakePerHour
		Tier.NORMAL -> cfg.maxWakePerMinute to cfg.maxWakePerHour
		Tier.PROACTIVE -> cfg.maxProactiveWakePerMinute to cfg.maxProactiveWakePerHour
	}

	companion object {
		const val MINUTE_MS = 60_000L
		const val HOUR_MS = 3_600_000L
	}
}
