package com.crsyyds.suzubot.runtime

import android.os.Build

/**
 * 设备能跑哪种协议端运行时：本机原生跑、外接已运行的 PicoOnebot、或完全不支持。
 *
 * 判定依据只有 ABI —— 手表 QQ 只提供 ARM32 原生库，所以有无 `armeabi-v7a`/`armeabi` 决定一切。
 */
enum class PicoRuntimeMode {

	/** 设备保留 ARM32 能力，协议端可在本机原生运行。 */
	NATIVE,

	/** 纯 64 位设备：本身跑不了 ARM32，只能连接用户已在别处运行起来的 PicoOnebot。 */
	EXTERNAL_ARM64,

	/** 既无 ARM32 也无 `arm64-v8a`，两条路都走不通。 */
	UNSUPPORTED;

	companion object {

		/** ARM32 ABI —— 手表 QQ 原生库的目标架构。 */
		private val ARM32_ABIS = listOf("armeabi-v7a", "armeabi")

		/** 64 位 ARM ABI —— 外接运行时的最低门槛。 */
		private const val ARM64_ABI = "arm64-v8a"

		/**
		 * 按 ABI 列表判定运行时模式（纯函数）。
		 *
		 * 优先级 ARM32 > arm64-v8a > 不支持：同时支持 32/64 位的设备一律判 [NATIVE]。
		 *
		 * @param abis 设备支持的 ABI，通常来自 `Build.SUPPORTED_ABIS`
		 */
		fun detect(abis: List<String>): PicoRuntimeMode {
			if (abis.any { it in ARM32_ABIS }) return NATIVE
			if (abis.any { it == ARM64_ABI }) return EXTERNAL_ARM64
			return UNSUPPORTED
		}

		/** 当前设备的运行时模式，直接读 `Build.SUPPORTED_ABIS`（不做缓存）。 */
		fun current(): PicoRuntimeMode = detect(Build.SUPPORTED_ABIS?.toList().orEmpty())
	}
}
