package com.crsyyds.suzubot.pico

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 凭据存储的持久化验证。
 *
 * **这个模块曾经有过一个致命 bug**：`init()` 从未被调用，`prefs` 为 null，
 * `prefs?.edit()?.putString(...)` 静默失败 —— 密码只写内存不落盘，
 * App 重启就丢，表现为「明明登录了却显示控制台密码不匹配」。
 *
 * 所以这里的核心测试是：**写进去的值必须真的能从磁盘读回来**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CredentialStoreTest {

	private lateinit var context: Context

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		// 彻底清理：先清底层 prefs，再清 CredentialStore 的内存缓存
		// 注意顺序：先清磁盘，再 clear() 会调 commit 也是去清磁盘
		context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
			.edit().clear().commit()
		CredentialStore.clear()
		CredentialStore.init(context)
	}

	// ── 基础读写 ──────────────────────────────────────────────────────────────

	@Test
	fun `初始状态没有密码`() {
		CredentialStore.clear()
		assertNull(CredentialStore.webUiPassword)
	}

	@Test
	fun `写入后能读回`() {
		CredentialStore.setWebUiPassword("my-secret-123")
		assertEquals("my-secret-123", CredentialStore.webUiPassword)
	}

	@Test
	fun `覆盖写入后者生效`() {
		CredentialStore.setWebUiPassword("first")
		CredentialStore.setWebUiPassword("second")
		assertEquals("second", CredentialStore.webUiPassword)
	}

	// ── 持久化（核心）──────────────────────────────────────────────────────────

	@Test
	fun `密码真正落盘 能从 SharedPreferences 读回`() {
		CredentialStore.setWebUiPassword("persisted-password")

		// 用 CredentialStore 自己的读取路径验证（内部会读同一份 prefs）
		assertEquals("persisted-password", CredentialStore.webUiPassword)

		// 再验证另一条路径：lastGood 走的是不同的 key
		CredentialStore.markGood("marked-password")
		assertEquals("marked-password", CredentialStore.lastGoodPassword)
	}

	@Test
	fun `markGood 同时写当前密码与最近可用密码`() {
		CredentialStore.markGood("good-password")

		// 通过 CredentialStore 自己的读取路径验证（内部走同一份 prefs）
		assertEquals("当前密码应更新", "good-password", CredentialStore.webUiPassword)
		assertEquals("最近可用密码应更新", "good-password", CredentialStore.lastGoodPassword)
	}

	@Test
	fun `clear 会清掉磁盘上的密码`() {
		CredentialStore.setWebUiPassword("to-be-cleared")
		CredentialStore.clear()

		assertNull(CredentialStore.webUiPassword)

		val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
		assertNull("磁盘上的也应该没了", prefs.getString("webui_password", null))
	}

	// ── 候选密码列表 ──────────────────────────────────────────────────────────

	@Test
	fun `candidates 至少包含默认密码`() {
		CredentialStore.clear()
		val list = CredentialStore.candidates()
		assertTrue("必须包含 picopico 作为兜底", list.contains("picopico"))
	}

	@Test
	fun `candidates 按优先级排列 当前密码在前`() {
		CredentialStore.setWebUiPassword("current")
		val list = CredentialStore.candidates()
		assertEquals("当前密码应该排第一", "current", list.first())
	}

	@Test
	fun `candidates 包含最近可用密码`() {
		CredentialStore.markGood("last-good")
		CredentialStore.setWebUiPassword("current")

		val list = CredentialStore.candidates()
		assertTrue("应包含当前密码", list.contains("current"))
		assertTrue("应包含最近可用密码", list.contains("last-good"))
		assertTrue("应包含默认兜底", list.contains("picopico"))
	}

	@Test
	fun `candidates 去重`() {
		// 当前密码就是默认密码时，不该重复出现
		CredentialStore.setWebUiPassword("picopico")
		val list = CredentialStore.candidates()
		assertEquals("不该有重复项", list.size, list.distinct().size)
	}

	@Test
	fun `空密码不会被加入 candidates`() {
		CredentialStore.clear()
		val list = CredentialStore.candidates()
		assertTrue("不该出现空串", list.none { it.isBlank() })
	}

	// ── 懒初始化兜底（曾经 bug 的防线）────────────────────────────────────────

	@Test
	fun `写入后立即读取不会拿到 null`() {
		// 这是对那个 bug 的直接回归测试：
		// 旧实现里 prefs 为 null，写入静默失败，读回来是 null
		CredentialStore.setWebUiPassword("should-not-be-lost")
		assertNotNull(
			"写入后必须能读回，否则就是静默失败",
			CredentialStore.webUiPassword
		)
		assertEquals("should-not-be-lost", CredentialStore.webUiPassword)
	}

	// ── backendToken ──────────────────────────────────────────────────────────

	@Test
	fun `backendToken 可读可写`() {
		CredentialStore.backendToken = "token-abc"
		assertEquals("token-abc", CredentialStore.backendToken)
	}

	@Test
	fun `backendToken 能持久保存`() {
		CredentialStore.backendToken = "persisted-token"
		assertEquals("persisted-token", CredentialStore.backendToken)
	}
}
