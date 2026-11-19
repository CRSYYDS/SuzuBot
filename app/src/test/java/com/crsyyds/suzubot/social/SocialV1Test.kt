package com.crsyyds.suzubot.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 四相仿真状态机的行为验证。
 *
 * 这个模块决定机器人「什么时候该说话、什么时候该安静」。
 * 核心是状态迁移与概率控制——但概率部分只能测「边界」和「不崩溃」，
 * 不能断言具体某一次是否响应（否则测试会随机失败）。
 */
class SocialV1Test {

	private lateinit var social: SocialV1

	@Before
	fun setUp() {
		social = SocialV1()
	}

	// ── 状态机 ────────────────────────────────────────────────────────────────

	@Test
	fun `新会话初始处于 IDLE`() {
		val st = social.state("g:1")
		assertEquals(SocialV1.Phase.IDLE, st.phase)
	}

	@Test
	fun `setPhase 能切换状态`() {
		social.setPhase("g:1", SocialV1.Phase.ACTIVE)
		assertEquals(SocialV1.Phase.ACTIVE, social.state("g:1").phase)

		social.setPhase("g:1", SocialV1.Phase.PROBING)
		assertEquals(SocialV1.Phase.PROBING, social.state("g:1").phase)
	}

	@Test
	fun `不同会话状态独立`() {
		social.setPhase("g:1", SocialV1.Phase.ACTIVE)
		assertEquals(SocialV1.Phase.ACTIVE, social.state("g:1").phase)
		assertEquals("另一个会话不该受影响", SocialV1.Phase.IDLE, social.state("g:2").phase)
	}

	@Test
	fun `四个相位都能设置`() {
		SocialV1.Phase.values().forEach { phase ->
			social.setPhase("g:1", phase)
			assertEquals(phase, social.state("g:1").phase)
		}
	}

	// ── 被 @ 时必须回应 ───────────────────────────────────────────────────────

	@Test
	fun `被 at 时必定响应`() {
		// 多次测试都应该 respond=true（不该有概率跳过）
		repeat(20) {
			val d = social.shouldRespond("g:test$it", "随便说点什么", isAtMe = true)
			assertTrue("被 @ 时必须回应", d.respond)
		}
	}

	@Test
	fun `被 at 时有自然的打字延迟`() {
		val d = social.shouldRespond("g:1", "hi", isAtMe = true)
		// 实现里 activeReplyDelay 是 2-8 秒，模拟真人看到消息后打字的停顿
		assertTrue("延迟应在 2-8 秒之间，实际 ${d.delayMs}", d.delayMs in 2_000..8_000)
	}

	// ── 未 @ 时的概率行为 ─────────────────────────────────────────────────────

	@Test
	fun `未 at 且非激活状态通常不响应`() {
		// IDLE 状态下，普通消息应该大概率不响应
		var responded = 0
		repeat(200) { i ->
			val d = social.shouldRespond("g:idle$i", "群友闲聊", isAtMe = false)
			if (d.respond) responded++
		}
		// 触发概率 10% 左右，放宽到 40% 避免随机失败
		assertTrue("IDLE 下不该频繁插话，实际 $responded/200", responded < 80)
	}

	@Test
	fun `shouldRespond 永不为 null`() {
		repeat(50) { i ->
			val d = social.shouldRespond("g:$i", "测试", isAtMe = i % 2 == 0)
			assertNotNull("决策不能为 null", d)
			assertTrue("延迟不能为负", d.delayMs >= 0)
		}
	}

	// ── 必须回应的关键词 ──────────────────────────────────────────────────────

	@Test
	fun `包含关键词时必须回应`() {
		// 默认关键词列表：deepseek / ai / 人工智能 / 机器人 / 模型
		assertTrue(social.mustReply("这个机器人好用吗"))
		assertTrue(social.mustReply("你是什么 AI"))
		assertTrue(social.mustReply("DeepSeek 怎么样"))
	}

	@Test
	fun `普通闲聊不需要必须回应`() {
		assertFalse(social.mustReply("今天天气不错"))
	}

	@Test
	fun `isDirectedAtAi 能识别名字`() {
		assertTrue(social.isDirectedAtAi("SuzuBot 在吗", listOf("SuzuBot")))
		assertFalse(social.isDirectedAtAi("今天吃什么", listOf("SuzuBot")))
	}

	@Test
	fun `isDirectedAtAi 名字列表为空时不崩溃`() {
		val r = social.isDirectedAtAi("随便说点什么", emptyList())
		assertFalse("没有名字配置就不该判定为指向自己", r)
	}

	@Test
	fun `isDirectedAtAi 对名字匹配区分大小写`() {
		// 实现用 String.contains，区分大小写
		assertTrue(social.isDirectedAtAi("SuzuBot 你好", listOf("SuzuBot")))
		// 全大写不匹配，这是已知行为（如需不敏感应改用 ignoreCase）
		assertFalse(social.isDirectedAtAi("SUZUBOT 你好", listOf("suzubot")))
	}

	// ── tick 心跳 ─────────────────────────────────────────────────────────────

	@Test
	fun `tick 在 IDLE 时可能返回空或唤醒事件`() {
		// 只要不崩溃即可
		val ev = social.tick("g:1", now = 0L)
		if (ev != null) {
			assertNotNull(ev.kind)
		}
	}

	@Test
	fun `ACTIVE 状态下 tick 能正常工作`() {
		val now = 1_000_000L
		social.setPhase("g:1", SocialV1.Phase.ACTIVE)
		social.state("g:1").lastActivityAt = now

		// 推进 1 小时，只要不崩且状态可读即可
		val ev = social.tick("g:1", now = now + 60 * 60_000)
		val phase = social.state("g:1").phase
		assertNotNull("状态应可读", phase)
		// 事件如果有，kind 应非空
		ev?.let { assertNotNull(it.kind) }
	}

	@Test
	fun `tick 多次调用不崩溃`() {
		var t = 0L
		repeat(500) {
			social.tick("g:1", now = t)
			t += 5_000
		}
		assertNotNull(social.state("g:1"))
	}

	// ── SILENT 标记 ───────────────────────────────────────────────────────────

	@Test
	fun `isSilentMarker 识别标准标记`() {
		assertTrue(SocialV1.isSilentMarker("[SILENT]"))
		assertTrue(SocialV1.isSilentMarker("  [SILENT]  "))
	}

	@Test
	fun `isSilentMarker 不误判正常文本`() {
		assertFalse(SocialV1.isSilentMarker("今天天气不错"))
		assertFalse(SocialV1.isSilentMarker(""))
	}

	// ── 分条发送 ──────────────────────────────────────────────────────────────

	@Test
	fun `splitByCjkSpaces 保留相邻中文不拆开`() {
		// 实现的设计：两侧都是 CJK 或数字时合并，避免把「我 今天」错拆
		val parts = SocialV1.splitByCjkSpaces("你好 今天 怎么样")
		assertEquals("全是中文应合并成一段", 1, parts.size)
	}

	@Test
	fun `splitByCjkSpaces 中文与英文之间切分`() {
		val parts = SocialV1.splitByCjkSpaces("你好 hello 世界")
		assertTrue("中英混合应切成多段", parts.size >= 2)
	}

	@Test
	fun `splitByCjkSpaces 单句不切分`() {
		val parts = SocialV1.splitByCjkSpaces("你好")
		assertEquals(1, parts.size)
	}

	@Test
	fun `splitLongSegment 超长文本会被切`() {
		val long = "啊".repeat(1000)
		val parts = SocialV1.splitLongSegment(long, maxChars = 400)
		assertTrue("应该被切成多段", parts.size > 1)
		parts.forEach {
			assertTrue("每段不应超过上限", it.length <= 400)
		}
	}

	@Test
	fun `splitLongSegment 短文本原样返回`() {
		val parts = SocialV1.splitLongSegment("短文本", maxChars = 400)
		assertEquals(1, parts.size)
		assertEquals("短文本", parts[0])
	}

	@Test
	fun `splitLongSegment 空串返回空列表或单空串`() {
		val parts = SocialV1.splitLongSegment("", maxChars = 400)
		assertTrue("不该崩溃", parts.isEmpty() || parts == listOf(""))
	}

	// ── 字符判定 ──────────────────────────────────────────────────────────────

	@Test
	fun `isCjk 正确识别中文字符`() {
		assertTrue(SocialV1.isCjk('中'))
		assertTrue(SocialV1.isCjk('文'))
		assertFalse(SocialV1.isCjk('a'))
		assertFalse(SocialV1.isCjk('1'))
	}
}
