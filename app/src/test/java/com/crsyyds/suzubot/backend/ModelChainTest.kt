package com.crsyyds.suzubot.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 模型链配置的行为验证。
 *
 * 重点：
 *  - 预设端点必须与官方文档一致（DeepSeek 不带 /v1、模型名 deepseek-flash）
 *  - 旧配置迁移要能纠正历史上写错的值
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ModelChainTest {

	private lateinit var context: Context

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().clear().commit()
		context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().clear().commit()
	}

	// ── 预设端点 ──────────────────────────────────────────────────────────────

	@Test
	fun `DeepSeek 预设使用官方端点`() {
		val p = ModelEndpoint.preset("deepseek")
		assertEquals(
			"DeepSeek 官方 base_url 不带 /v1",
			"https://api.deepseek.com", p.baseUrl
		)
		assertEquals(
			"当前主力模型是 deepseek-flash",
			"deepseek-flash", p.model
		)
	}

	@Test
	fun `智谱预设使用官方端点`() {
		val p = ModelEndpoint.preset("zhipu")
		assertEquals("https://open.bigmodel.cn/api/paas/v4", p.baseUrl)
		assertEquals("glm-4-flash", p.model)
	}

	@Test
	fun `自定义预设为空`() {
		val p = ModelEndpoint.preset("custom")
		assertTrue("地址应为空", p.baseUrl.isEmpty())
		assertTrue("模型应为空", p.model.isEmpty())
		assertFalse("没有 Key 时不应认为已配置", p.configured)
	}

	@Test
	fun `configured 判定需要三项齐全`() {
		val base = ModelEndpoint.preset("deepseek")
		assertFalse("没 Key 时未配置", base.configured)

		val withKey = base.copy(apiKey = "sk-test")
		assertTrue("填了 Key 就算配置好了", withKey.configured)

		val noModel = withKey.copy(model = "")
		assertFalse("缺模型名不算配置好", noModel.configured)
	}

	// ── 模型清单 ──────────────────────────────────────────────────────────────

	@Test
	fun `DeepSeek 模型清单不含已下线的 deepseek-chat`() {
		val models = ModelEndpoint.modelsFor("deepseek")
		assertTrue("应包含 deepseek-flash", models.contains("deepseek-flash"))
		assertFalse(
			"deepseek-chat 已下线，不该出现在推荐列表里",
			models.contains("deepseek-chat")
		)
	}

	@Test
	fun `智谱模型清单包含免费档`() {
		val models = ModelEndpoint.modelsFor("zhipu")
		assertTrue("应包含免费档 glm-4-flash", models.contains("glm-4-flash"))
	}

	@Test
	fun `智谱视觉模型被正确标注`() {
		val catalog = ModelEndpoint.modelCatalog("zhipu")
		val visionModels = catalog.filter { it.vision }.map { it.id }

		assertTrue("glm-4v-flash 应标注为视觉模型", visionModels.contains("glm-4v-flash"))
		assertTrue("glm-4.6v-flash 应标注为视觉模型", visionModels.contains("glm-4.6v-flash"))
	}

	@Test
	fun `DeepSeek 全部不支持视觉`() {
		val catalog = ModelEndpoint.modelCatalog("deepseek")
		assertTrue(
			"DeepSeek 目前没有视觉模型，不该误标",
			catalog.none { it.vision }
		)
	}

	// ── 序列化 ────────────────────────────────────────────────────────────────

	@Test
	fun `ModelEndpoint 序列化往返一致`() {
		val original = ModelEndpoint(
			id = "test-id",
			name = "测试模型",
			kind = "custom",
			baseUrl = "https://example.com/v1",
			apiKey = "sk-secret",
			model = "test-model",
			timeoutMs = 30_000,
			thinkingEnabled = true,
			visionEnabled = true
		)

		val json = original.toJson()
		val restored = ModelEndpoint.fromJson(json)

		assertEquals(original.id, restored.id)
		assertEquals(original.name, restored.name)
		assertEquals(original.baseUrl, restored.baseUrl)
		assertEquals(original.apiKey, restored.apiKey)
		assertEquals(original.model, restored.model)
		assertEquals(original.timeoutMs, restored.timeoutMs)
		assertEquals(original.thinkingEnabled, restored.thinkingEnabled)
		assertEquals(original.visionEnabled, restored.visionEnabled)
	}

	// ── 链管理 ────────────────────────────────────────────────────────────────

	@Test
	fun `保存后能读回`() {
		val mgr = ModelChainManager(context)
		val chain = listOf(
			ModelEndpoint.preset("deepseek").copy(apiKey = "sk-1"),
			ModelEndpoint.preset("zhipu").copy(apiKey = "key-2")
		)
		mgr.save(chain)

		val loaded = mgr.load()
		assertEquals(2, loaded.size)
		assertEquals("sk-1", loaded[0].apiKey)
		assertEquals("key-2", loaded[1].apiKey)
	}

	@Test
	fun `usable 只返回已配置的`() {
		val mgr = ModelChainManager(context)
		mgr.save(
			listOf(
				ModelEndpoint.preset("deepseek").copy(apiKey = "sk-1"),
				ModelEndpoint.preset("zhipu")   // 没有 key
			)
		)

		val usable = mgr.usable()
		assertEquals("只有 1 个配置完整", 1, usable.size)
		assertEquals("deepseek", usable[0].kind)
	}

	@Test
	fun `空链时 load 返回默认 DeepSeek`() {
		val mgr = ModelChainManager(context)
		val loaded = mgr.load()
		assertTrue("应该至少有一个默认项", loaded.isNotEmpty())
		assertEquals("deepseek", loaded[0].kind)
	}

	// ── 旧配置迁移（重要：纠正历史错误值）──────────────────────────────────────

	@Test
	fun `迁移旧配置时纠正 deepseek-chat`() {
		// 模拟旧版本写入的错误配置
		context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit()
			.putString("api_key", "sk-legacy")
			.putString("api_base", "https://api.deepseek.com/v1")
			.putString("api_model", "deepseek-chat")
			.apply()

		val mgr = ModelChainManager(context)
		mgr.migrateLegacy()

		val loaded = mgr.load()
		assertEquals(1, loaded.size)
		assertEquals("Key 应保留", "sk-legacy", loaded[0].apiKey)
		assertEquals(
			"deepseek-chat 应被纠正为 deepseek-flash",
			"deepseek-flash", loaded[0].model
		)
		assertEquals(
			"带 /v1 的地址应被纠正",
			"https://api.deepseek.com", loaded[0].baseUrl
		)
	}

	@Test
	fun `没有旧配置时迁移不做事`() {
		val mgr = ModelChainManager(context)
		mgr.migrateLegacy()

		// 应该还是默认链
		val loaded = mgr.load()
		assertEquals(1, loaded.size)
		assertEquals("deepseek", loaded[0].kind)
		assertTrue("不该有 Key", loaded[0].apiKey.isEmpty())
	}
}
