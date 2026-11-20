package com.crsyyds.suzubot.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 运行时模式判定的行为验证。
 *
 * [PicoRuntimeMode.detect] 是纯函数，纯 JVM JUnit 即可，不需要 Robolectric。
 * 判错的代价很实在：把能直接跑的 32 位设备判成外接模式，用户会白白去折腾外部环境。
 */
class PicoRuntimeModeTest {

	@Test
	fun `有 ARM32 判定为 NATIVE`() {
		assertEquals(
			PicoRuntimeMode.NATIVE,
			PicoRuntimeMode.detect(listOf("armeabi-v7a"))
		)
	}

	@Test
	fun `纯 64 位判定为 EXTERNAL_ARM64`() {
		assertEquals(
			PicoRuntimeMode.EXTERNAL_ARM64,
			PicoRuntimeMode.detect(listOf("arm64-v8a"))
		)
	}

	@Test
	fun `32 位与 64 位并存时优先 NATIVE`() {
		assertEquals(
			PicoRuntimeMode.NATIVE,
			PicoRuntimeMode.detect(listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
		)
	}

	@Test
	fun `只有 x86 或未知 ABI 判定为 UNSUPPORTED`() {
		assertEquals(
			PicoRuntimeMode.UNSUPPORTED,
			PicoRuntimeMode.detect(listOf("x86", "x86_64"))
		)
		assertEquals(
			PicoRuntimeMode.UNSUPPORTED,
			PicoRuntimeMode.detect(listOf("mips", ""))
		)
	}

	@Test
	fun `空 ABI 列表判定为 UNSUPPORTED`() {
		assertEquals(
			PicoRuntimeMode.UNSUPPORTED,
			PicoRuntimeMode.detect(emptyList())
		)
	}
}
