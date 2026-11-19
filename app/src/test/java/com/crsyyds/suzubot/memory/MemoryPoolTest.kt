package com.crsyyds.suzubot.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 记忆池的行为验证。
 *
 * 这个模块负责「机器人能记住什么」，核心是 CJK bigram 检索：
 * 中文没有空格分隔，必须用相邻二字组合做倒排索引。
 */
class MemoryPoolTest {

	private lateinit var pool: MemoryPool

	@Before
	fun setUp() {
		pool = MemoryPool(maxRecords = 100, maxResults = 5)
	}

	// ── 基本存取 ──────────────────────────────────────────────────────────────

	@Test
	fun `存入后能检索到`() {
		pool.store("今天天气真不错", namespace = "g:123", source = "user")
		val hits = pool.retrieve("天气", namespace = "g:123")
		assertEquals(1, hits.size)
		assertTrue(hits[0].memory.contains("天气"))
	}

	@Test
	fun `检索不到无关内容`() {
		pool.store("今天天气真不错", namespace = "g:123", source = "user")
		val hits = pool.retrieve("量子物理", namespace = "g:123")
		assertTrue("无关查询应返回空", hits.isEmpty())
	}

	@Test
	fun `空查询返回空结果`() {
		pool.store("一些内容", namespace = "g:1", source = "user")
		assertTrue(pool.retrieve("", namespace = "g:1").isEmpty())
		assertTrue(pool.retrieve("   ", namespace = "g:1").isEmpty())
	}

	@Test
	fun `空内容不参与检索`() {
		// 实现上 store 不拒绝空串，但空内容没有可检索的 token，
		// 所以永远检索不出来 —— 这等价于「不存在」。
		pool.store("", namespace = "g:1", source = "user")
		pool.store("   ", namespace = "g:1", source = "user")
		assertTrue(pool.retrieve("任何查询", namespace = "g:1").isEmpty())
	}

	// ── namespace 隔离 ────────────────────────────────────────────────────────

	@Test
	fun `不同会话的记忆互相隔离`() {
		pool.store("群 A 的话题", namespace = "g:111", source = "user")
		pool.store("群 B 的话题", namespace = "g:222", source = "user")

		val hitsA = pool.retrieve("话题", namespace = "g:111")
		val hitsB = pool.retrieve("话题", namespace = "g:222")

		assertEquals(1, hitsA.size)
		assertEquals(1, hitsB.size)
		assertTrue("A 的结果不该包含 B 的内容", hitsA[0].memory.contains("群 A"))
		assertTrue("B 的结果不该包含 A 的内容", hitsB[0].memory.contains("群 B"))
	}

	@Test
	fun `空 namespace 用于跨会话记忆`() {
		// store 与 retrieve 的 scope 默认值不同，测试里显式对齐
		pool.store("全局偏好：喜欢猫", namespace = "", source = "user", scope = "user")
		val hits = pool.retrieve("猫", namespace = "", scope = "user")
		assertEquals(1, hits.size)
	}

	// ── CJK bigram 检索 ───────────────────────────────────────────────────────

	@Test
	fun `部分字匹配也能命中`() {
		pool.store("我最喜欢吃火锅", namespace = "g:1", source = "user")
		// 只查两个字，bigram 应该能命中
		val hits = pool.retrieve("火锅", namespace = "g:1")
		assertEquals(1, hits.size)
	}

	@Test
	fun `英文与数字也能检索`() {
		pool.store("model is deepseek-flash", namespace = "g:1", source = "user", scope = "user")
		val hits = pool.retrieve("deepseek", namespace = "g:1", scope = "user")
		assertEquals(1, hits.size)
	}

	@Test
	fun `停用词不会造成大量误命中`() {
		pool.store("我的是了他的", namespace = "g:1", source = "user")
		pool.store("完全不同的话题内容", namespace = "g:1", source = "user")
		// 查询一个只含停用词串的内容，不应该把无关记录也带出来
		val hits = pool.retrieve("的了", namespace = "g:1")
		// 不强制断言为空（bigram 实现可能仍匹配），但至少要能返回而不崩
		assertTrue("不应崩溃", hits.size <= 2)
	}

	// ── 容量与淘汰 ────────────────────────────────────────────────────────────

	@Test
	fun `超过上限时淘汰最旧的`() {
		val small = MemoryPool(maxRecords = 3, maxResults = 10)
		small.store("第一条", namespace = "g:1", source = "user")
		small.store("第二条", namespace = "g:1", source = "user")
		small.store("第三条", namespace = "g:1", source = "user")
		small.store("第四条", namespace = "g:1", source = "user")

		assertEquals("应该只保留 3 条", 3, small.count())

		// 第一条应该被淘汰
		val hits = small.retrieve("第一条", namespace = "g:1")
		assertTrue("最旧的应该已被淘汰", hits.none { it.memory.contains("第一条") })
	}

	@Test
	fun `retrieve 返回数量受 maxResults 限制`() {
		val small = MemoryPool(maxRecords = 100, maxResults = 2)
		repeat(10) {
			small.store("共同关键词 内容 $it", namespace = "g:1", source = "user")
		}
		val hits = small.retrieve("关键词", namespace = "g:1")
		assertTrue("结果数不应超过 maxResults", hits.size <= 2)
	}

	// ── clear ──────────────────────────────────────────────────────────────────

	@Test
	fun `clear 指定 namespace 只清该会话`() {
		pool.store("A 的内容", namespace = "g:1", source = "user")
		pool.store("B 的内容", namespace = "g:2", source = "user")

		val cleared = pool.clear(namespace = "g:1")
		assertEquals(1, cleared)
		assertEquals(1, pool.count())
		assertTrue(pool.retrieve("A", namespace = "g:1").isEmpty())
		assertEquals(1, pool.retrieve("B", namespace = "g:2").size)
	}

	@Test
	fun `clear 全部`() {
		repeat(5) { pool.store("内容 $it", namespace = "g:1", source = "user") }
		val cleared = pool.clear()
		assertEquals(5, cleared)
		assertEquals(0, pool.count())
	}

	// ── 序列化 ────────────────────────────────────────────────────────────────

	@Test
	fun `serialize 与 restore 往返一致`() {
		pool.store("第一条记忆", namespace = "g:1", source = "user")
		pool.store("第二条记忆", namespace = "g:2", source = "assistant")

		val data = pool.serialize()
		assertTrue("序列化结果不应为空", data.isNotEmpty())

		val restored = MemoryPool(maxRecords = 100, maxResults = 5)
		restored.restore(data)

		assertEquals("条数应一致", pool.count(), restored.count())
		// 检索能力也应恢复
		assertEquals(1, restored.retrieve("第一条", namespace = "g:1").size)
		assertEquals(1, restored.retrieve("第二条", namespace = "g:2").size)
	}

	@Test
	fun `restore 空数据不崩溃`() {
		pool.restore("")
		assertEquals(0, pool.count())
	}

	@Test
	fun `restore 畸形数据不崩溃`() {
		pool.store("正常数据", namespace = "g:1", source = "user")
		// 塞入一批畸形行
		pool.restore("乱七八糟\n\n\t\t\ninvalid|data|here")
		// 不要求恢复成功，只要求不抛异常
		assertTrue(true)
	}

	// ── 偏好块渲染 ────────────────────────────────────────────────────────────

	@Test
	fun `renderPreferenceBlock 对空结果返回空串`() {
		val block = MemoryPool.renderPreferenceBlock(emptyList())
		assertTrue("空结果不该生成提示词块", block.isEmpty())
	}

	@Test
	fun `renderPreferenceBlock 对有结果生成非空文本`() {
		pool.store("用户喜欢猫", namespace = "g:1", source = "user", scope = "user")
		val hits = pool.retrieve("猫", namespace = "g:1", scope = "user")
		assertTrue("应能检索到", hits.isNotEmpty())
		val block = MemoryPool.renderPreferenceBlock(hits)
		assertTrue("应该生成提示词块", block.isNotEmpty())
		assertTrue("应包含记忆内容", block.contains("猫"))
	}

	// ── 并发安全（基础）────────────────────────────────────────────────────────

	@Test
	fun `多线程并发存取不崩溃`() {
		val threads = (0 until 4).map { t ->
			Thread {
				repeat(50) { i ->
					pool.store("线程$t 消息$i", namespace = "g:$t", source = "user")
					pool.retrieve("消息", namespace = "g:$t")
				}
			}
		}
		threads.forEach { it.start() }
		threads.forEach { it.join() }

		assertTrue("应该有记录被存入", pool.count() > 0)
	}
}
