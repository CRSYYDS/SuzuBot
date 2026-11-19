package com.crsyyds.suzubot.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 分档限频器的行为验证。
 *
 * 这个类决定了机器人会不会刷屏，是用户体验的关键防线。
 * 测试重点是「四档互不挤占」——直接消息不该被普通消息的额度拖累。
 */
class RateLimiterTest {

	private lateinit var limiter: RateLimiter

	@Before
	fun setUp() {
		limiter = RateLimiter()
	}

	// ── 发送限频 ──────────────────────────────────────────────────────────────

	@Test
	fun `初始状态可以发送`() {
		assertTrue(limiter.canSend(now = 0L))
	}

	@Test
	fun `默认配置下每分钟最多 8 条`() {
		val now = 1_000L
		repeat(8) {
			assertTrue("第 ${it + 1} 条应该允许", limiter.canSend(now))
			limiter.recordSend(now)
		}
		assertFalse("第 9 条应该被拒绝", limiter.canSend(now))
	}

	@Test
	fun `超过一分钟后配额恢复`() {
		val start = 1_000L
		repeat(8) { limiter.recordSend(start) }
		assertFalse(limiter.canSend(start))

		// 61 秒后窗口滑过
		assertTrue(limiter.canSend(start + 61_000))
	}

	@Test
	fun `部分消息滑出窗口后可以继续发送`() {
		val start = 0L
		// 第 1 秒发 4 条
		repeat(4) { limiter.recordSend(start) }
		// 第 31 秒再发 4 条
		repeat(4) { limiter.recordSend(start + 31_000) }

		// 第 31 秒时：前 4 条还在窗口内 → 满 8 条，不能再发
		assertFalse(limiter.canSend(start + 31_000))

		// 第 62 秒时：前 4 条已滑出 → 只剩 4 条，可以发
		assertTrue(limiter.canSend(start + 62_000))
	}

	// ── 分档隔离 ──────────────────────────────────────────────────────────────

	@Test
	fun `直接消息用独立配额 不被普通消息挤占`() {
		val now = 0L
		// 把普通配额用光
		repeat(8) {
			limiter.recordSend(now)
		}
		assertFalse("普通消息已满", limiter.canSend(now))

		// 直接消息（回复 @ 自己的）应该有独立配额
		assertTrue("直接消息不该受影响", limiter.canWake(RateLimiter.Tier.DIRECT, now))
	}

	@Test
	fun `tryWake 成功后该档配额减少`() {
		val now = 0L
		assertTrue(limiter.tryWake(RateLimiter.Tier.PROACTIVE, now))
		// 主动消息默认 1 次 / 20 分钟
		assertFalse("同一窗口内第二次应被拒绝", limiter.tryWake(RateLimiter.Tier.PROACTIVE, now))
	}

	@Test
	fun `主动消息分钟窗口生效`() {
		val now = 0L
		limiter.tryWake(RateLimiter.Tier.PROACTIVE, now)
		// PROACTIVE 是 1 次/分钟，30 秒内必然拒绝
		assertFalse("分钟额度已用完", limiter.canWake(RateLimiter.Tier.PROACTIVE, now + 30_000))
		// 过了 1 分钟就恢复（小时上限 20 次还很宽裕）
		assertTrue("过一分钟后应恢复", limiter.canWake(RateLimiter.Tier.PROACTIVE, now + 61_000))
	}

	@Test
	fun `主动消息小时窗口生效`() {
		val now = 0L
		// 分散在 1 小时内发满 20 次（避开分钟限制）
		repeat(20) { i ->
			val t = now + i * 61_000L   // 每 61 秒一次
			assertTrue("第 ${i + 1} 次应该允许", limiter.tryWake(RateLimiter.Tier.PROACTIVE, t))
		}
		// 第 21 次（仍在 1 小时内）应被拒绝
		assertFalse("小时额度已满", limiter.canWake(RateLimiter.Tier.PROACTIVE, now + 20 * 61_000L))
	}

	@Test
	fun `四档配额各不相同`() {
		val direct = limiter.limitsOf(RateLimiter.Tier.DIRECT)
		val followUp = limiter.limitsOf(RateLimiter.Tier.FOLLOW_UP)
		val normal = limiter.limitsOf(RateLimiter.Tier.NORMAL)
		val proactive = limiter.limitsOf(RateLimiter.Tier.PROACTIVE)

		// DIRECT 应该最宽松
		assertTrue("DIRECT 额度应不小于 NORMAL", direct.first >= normal.first)
		// PROACTIVE 应该最严格
		assertTrue("PROACTIVE 额度应不大于 NORMAL", proactive.first <= normal.first)
		// 四档的窗口长度不全相同（否则分档没意义）
		val windows = setOf(direct.second, followUp.second, normal.second, proactive.second)
		assertTrue("窗口长度应该有多样性", windows.size >= 2)
	}

	// ── 边界情况 ──────────────────────────────────────────────────────────────

	@Test
	fun `负数或零时间戳不崩溃`() {
		assertTrue(limiter.canSend(now = 0L))
		limiter.recordSend(now = 0L)
		// 早期时间戳（时钟回拨场景）
		limiter.recordSend(now = -1000L)
		assertTrue("不应崩溃", true)
	}

	@Test
	fun `小时上限对密集发送生效`() {
		val now = 0L
		// 1 小时内发 10000 条，远超小时上限 60
		repeat(10_000) { i -> limiter.recordSend(now + i) }
		// 仍在 1 小时内 → 应被小时上限拒绝
		assertFalse("小时额度早已超限", limiter.canSend(now + 10_000 + 120_000))
	}

	@Test
	fun `超过一小时后所有记录滑出窗口`() {
		val now = 0L
		repeat(100) { limiter.recordSend(now) }   // 同一时刻发 100 条
		// 61 分钟后：全部滑出 1 小时窗口
		assertTrue("所有记录应已过期", limiter.canSend(now + 61 * 60_000L))
	}

	@Test
	fun `canSend 不改变状态`() {
		val now = 0L
		repeat(100) { limiter.canSend(now) }
		// 只是查询 100 次，不应该消耗任何配额
		assertTrue(limiter.canSend(now))
	}

	@Test
	fun `不同实例互不影响`() {
		val a = RateLimiter()
		val b = RateLimiter()
		val now = 0L

		repeat(8) { a.recordSend(now) }
		assertFalse(a.canSend(now))
		assertTrue("另一个实例应该是干净的", b.canSend(now))
	}
}
