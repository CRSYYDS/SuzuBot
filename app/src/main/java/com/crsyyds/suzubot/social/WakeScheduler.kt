package com.crsyyds.suzubot.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 唤醒调度器。
 *
 * 原版：LismQQ-bot `src/wake-scheduler.js`
 *
 * 核心机制：
 *  - **合并窗口**：触发后等 batchWindowMs 再唤醒，期间多条消息合并成一次
 *  - **分档限频**：四档互不挤占（见 RateLimiter）
 *  - **巡逻**：自排 min~max 随机间隔，深夜降概率
 *  - **睡眠**：AI 可以请求睡一段时间，到点再醒
 */
class WakeScheduler(
	private val scope: CoroutineScope,
	private val limiter: RateLimiter = RateLimiter(),
	private val cfg: Config = Config()
) {

	data class Config(
		/** 触发后等多久再唤醒（期间多条合并） */
		val batchWindowMs: Long = 3_000,
		val proactiveMinMs: Long = 30 * 60 * 1000,
		val proactiveMaxMs: Long = 90 * 60 * 1000,
		val sleepMinMs: Long = 60_000,
		val sleepMaxMs: Long = 2 * 60 * 60 * 1000,
		/** 过期闲聊归档阈值 */
		val staleChatMs: Long = 30 * 60 * 1000
	)

	/** 一次唤醒请求。 */
	data class WakeRequest(
		val key: String,
		val reason: String,
		val tier: RateLimiter.Tier,
		val at: Long = System.currentTimeMillis()
	)

	/** 唤醒触发时回调，由 BotEngine 接。 */
	var onWake: (suspend (WakeRequest) -> Unit)? = null

	private class Runtime {
		val pending = mutableListOf<WakeRequest>()
		var flushJob: Job? = null
		var proactiveJob: Job? = null
		var asleepUntil: Long = 0L
	}

	private val runtimes = ConcurrentHashMap<String, Runtime>()

	private fun rt(key: String): Runtime = runtimes.getOrPut(key) { Runtime() }

	// ── 触发 ──────────────────────────────────────────────────────────────────

	/**
	 * 评估一条消息应不应该唤醒。
	 *
	 * @return 若产生唤醒请求则返回它
	 */
	fun onMessage(key: String, text: String, isAtMe: Boolean): WakeRequest? {
		val runtime = rt(key)

		// 睡着时只有 direct 能叫醒
		if (runtime.asleepUntil > System.currentTimeMillis() && !isAtMe) return null

		val tier = when {
			isAtMe -> RateLimiter.Tier.DIRECT
			else -> RateLimiter.Tier.NORMAL
		}

		val reason = if (isAtMe) "mention" else "message"
		return enqueue(key, reason, tier)
	}

	/** 入队并安排合并窗口。 */
	@Synchronized
	fun enqueue(key: String, reason: String, tier: RateLimiter.Tier): WakeRequest? {
		val runtime = rt(key)
		// 同一原因已排队 → 不重复
		if (runtime.pending.any { it.reason == reason }) return null

		val req = WakeRequest(key, reason, tier)
		runtime.pending.add(req)

		// 已有待触发的 flush 就不再加
		if (runtime.flushJob?.isActive == true) return req

		runtime.flushJob = scope.launch {
			delay(cfg.batchWindowMs)
			flush(key)
		}
		return req
	}

	/** 取走并触发排队的唤醒（优先级：direct > followUp > normal > proactive）。 */
	private suspend fun flush(key: String) {
		val runtime = rt(key)
		val best: WakeRequest = synchronized(this) {
			if (runtime.pending.isEmpty()) return
			val sorted = runtime.pending.sortedByDescending { priority(it.tier) }
			val b = sorted.firstOrNull() ?: return
			runtime.pending.clear()
			b
		}

		// 睡梦中：只有 direct 放行
		if (runtime.asleepUntil > System.currentTimeMillis() &&
			best.tier != RateLimiter.Tier.DIRECT
		) {
			return
		}

		if (!limiter.tryWake(best.tier)) return
		onWake?.invoke(best)
	}

	private fun priority(tier: RateLimiter.Tier): Int = when (tier) {
		RateLimiter.Tier.DIRECT -> 4
		RateLimiter.Tier.FOLLOW_UP -> 3
		RateLimiter.Tier.NORMAL -> 2
		RateLimiter.Tier.PROACTIVE -> 1
	}

	// ── 巡逻 ──────────────────────────────────────────────────────────────────

	/** 为一个会话启动巡逻（AI 自己定时醒来看看群里）。 */
	@Synchronized
	fun startProactive(key: String) {
		val runtime = rt(key)
		if (runtime.proactiveJob?.isActive == true) return

		runtime.proactiveJob = scope.launch {
			while (scope.isActive) {
				val base = Random.nextLong(cfg.proactiveMinMs, cfg.proactiveMaxMs)
				// 深夜（23-7 点）降概率：间隔拉长 1.5~3 倍
				val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
				val factor = if (hour >= 23 || hour <= 7) Random.nextDouble(1.5, 3.0) else 1.0
				delay((base * factor).toLong())

				if (runtime.asleepUntil > System.currentTimeMillis()) continue
				enqueue(key, "proactive", RateLimiter.Tier.PROACTIVE)
			}
		}
	}

	@Synchronized
	fun stopProactive(key: String) {
		rt(key).proactiveJob?.cancel()
	}

	// ── 睡眠 ──────────────────────────────────────────────────────────────────

	/** AI 请求睡一会儿（会被夹到 [sleepMinMs, sleepMaxMs]）。 */
	@Synchronized
	fun sleep(key: String, requestedMs: Long) {
		val actual = requestedMs.coerceIn(cfg.sleepMinMs, cfg.sleepMaxMs)
		rt(key).asleepUntil = System.currentTimeMillis() + actual
	}

	@Synchronized
	fun wakeUp(key: String) {
		rt(key).asleepUntil = 0L
	}

	fun isAsleep(key: String): Boolean =
		rt(key).asleepUntil > System.currentTimeMillis()

	// ── 清理 ──────────────────────────────────────────────────────────────────

	@Synchronized
	fun shutdown() {
		runtimes.values.forEach {
			it.flushJob?.cancel()
			it.proactiveJob?.cancel()
		}
		runtimes.clear()
	}
}
