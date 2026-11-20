package com.crsyyds.suzubot.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.crsyyds.suzubot.pico.PicoOnebotClient
import com.crsyyds.suzubot.runtime.PicoRuntimeMode

/**
 * 协议端启动 / 隐私政策引导。
 *
 * 设计目标（按主人要求）：
 *  - 点「启动协议端」→ **静默启动**，不把 PicoOnebot 的界面留在前台
 *  - 只有「需要登录」时才让用户看到它的二维码页
 *  - 登录成功后立刻切回本应用
 *
 * 技术背景：
 * PicoOnebot 的 6099 服务在 `WatchApplication.attachBaseContext` 里就已经起来了，
 * 也就是说**只要 Application 被创建，服务就活了**。
 * 拉起它的 Activity 只是为了「让进程启动」+「首次同意隐私政策」。
 * 所以我们可以：拉起 → 等 1 秒 → 立刻切回自己。
 */
object PrivacyHelper {

	private const val TAG = "PrivacyHelper"
	private const val PICO_PKG = "com.tencent.qqlite"
	private const val PICO_LAUNCHER = "com.tencent.qqnt.watch.app.JumpActivity"
	private const val CONSOLE_PORT = 6099

	/**
	 * 静默拉起协议端进程。
	 *
	 * @param returnToApp 拉起后是否立刻把本应用切回前台（默认 true = 静默）
	 * @param appActivityClass 要切回来的 Activity 类
	 * @return 是否成功发出了启动 Intent
	 */
	/**
	 * 静默拉起协议端。
	 *
	 * 关键：PicoOnebot 启动时会**连续**拉几次界面（JumpActivity → MainActivity），
	 * 只切一次很容易被后面那次顶掉。所以要**多轮切回**。
	 */
	/**
	 * 静默拉起协议端。
	 *
	 * 探讨过的方案：
	 *  A. 「后台弹出界面」权限 → ColorOS 的悬浮窗列表里找不到我们的 App，不可用
	 *  C. 只拉起进程 → PicoOnebot 没有可用的 exported 组件，不可用
	 *  B. **HOME 键中转**（本实现）→ 无需任何权限
	 *
	 * 原理：Android 的 BAL（后台启动 Activity）限制只对「后台」生效。
	 * 发 HOME 键后，系统的前台变成桌面，此时我们的 startActivity
	 * 不再被当作“后台抢界面”而丢弃。
	 *
	 * 代价：会看到桌面闪一下（约 0.5 秒）。
	 */
	/**
	 * 启动协议端（接受跳转方案）。
	 *
	 * 为什么不做静默：
	 *  · 后台启动 Activity 被 BAL 限制（无 START_ACTIVITIES_FROM_BACKGROUND）
	 *  · 悬浮窗权限在 ColorOS 列表里找不到我们
	 *  · 发 HOME 键需 INJECT_EVENTS 系统权限
	 *  三条路都堵死了，所以干净地跳过去 + 提示用户返回。
	 *
	 * @return 是否成功发出启动 Intent
	 */
	fun launchPicoSilently(
		context: Context,
		appActivityClass: Class<*>? = null,
		returnToApp: Boolean = true,
		returnDelayMs: Long = 0
	): Boolean {
		if (PicoRuntimeMode.current() != PicoRuntimeMode.NATIVE) return false
		val ok = startPicoActivity(context)
		if (!ok) return false

		// 告诉用户接下来该怎么做
		runCatching {
			android.widget.Toast.makeText(
				context,
				"正在启动 QQ 协议端…它起来后，请按返回键回到本应用",
				android.widget.Toast.LENGTH_LONG
			).show()
		}

		return true
	}

	/**
	 * 让前台变成桌面。
	 *
	 * 尝试顺序（都不需要特殊权限）：
	 *  1. 通过 ActivityManager 的 moveTaskToBack（需要我们自己的 Activity）
	 *  2. 发 KEYCODE_HOME（Instrumentation，需 INJECT_EVENTS，大部分机器不行）
	 *  3. shell input keyevent（需 root）
	 *
	 * 注：实测 ColorOS/Android 8.1 上这三条都不行，
	 * 所以本方案最终走不通，保留代码仅作文档。
	 */
	private fun sendHome(activity: android.app.Activity? = null) {
		// 方式 1：自己的 Activity 可以 moveTaskToBack
		if (activity != null) {
			runCatching {
				activity.runOnUiThread {
					activity.moveTaskToBack(true)
				}
				Thread.sleep(300)
				return
			}.onFailure { Log.w(TAG, "moveTaskToBack failed", it) }
		}

		// 方式 2：Instrumentation（需 INJECT_EVENTS）
		runCatching {
			val cls = Class.forName("android.app.Instrumentation")
			val inst = cls.getDeclaredConstructor().newInstance()
			cls.getMethod("sendKeyDownUpSync", Int::class.java)
				.invoke(inst, android.view.KeyEvent.KEYCODE_HOME)
			return
		}.onFailure { Log.w(TAG, "Instrumentation HOME failed: ${it.message}") }

		// 方式 3：shell（需 root）
		runCatching {
			Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 3")).waitFor()
		}.onFailure { Log.w(TAG, "shell HOME failed", it) }
	}

	/** 粗略判断本应用是否已在前台（用 activity 栈顶部名字）。 */
	private fun isAppInForeground(context: Context, activityClass: Class<*>): Boolean {
		return try {
			val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
			@Suppress("DEPRECATION")
			val top = am.getRunningTasks(1).firstOrNull()?.topActivity?.className
			top == activityClass.name
		} catch (t: Throwable) {
			false
		}
	}

	/** 只发启动 Intent，不做任何切回。 */
	fun startPicoActivity(context: Context): Boolean {
		when (PicoRuntimeMode.current()) {
			PicoRuntimeMode.EXTERNAL_ARM64 -> return openPicoConsole(context)
			PicoRuntimeMode.UNSUPPORTED -> return false
			PicoRuntimeMode.NATIVE -> Unit
		}
		val attempts = listOf(
			Intent().setClassName(PICO_PKG, PICO_LAUNCHER),
			context.packageManager.getLaunchIntentForPackage(PICO_PKG)
		)
		for (intent in attempts) {
			if (intent == null) continue
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if (runCatching { context.startActivity(intent) }.isSuccess) {
				Log.i(TAG, "pico activity started")
				return true
			}
		}
		Log.w(TAG, "cannot start pico activity")
		return false
	}

	private fun openPicoConsole(context: Context): Boolean = runCatching {
		val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PicoOnebotClient.DEFAULT_BASE_URL))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
		true
	}.getOrElse {
		Log.w(TAG, "cannot open pico console", it)
		false
	}

	/**
	 * 把本应用切回前台（不新建栈，直接复用已有任务）。
	 */
	fun bringAppToFront(context: Context, activityClass: Class<*>): Boolean {
		return runCatching {
			val intent = Intent(context, activityClass).apply {
				addFlags(
					Intent.FLAG_ACTIVITY_NEW_TASK or
						Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
						Intent.FLAG_ACTIVITY_SINGLE_TOP or
						Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
				)
			}
			context.startActivity(intent)
			Log.i(TAG, "brought app to front")
			true
		}.getOrElse {
			Log.w(TAG, "bringAppToFront failed", it)
			false
		}
	}

	/**
	 * 拉起协议端并等端口就绪。
	 *
	 * @param silent true = 拉起后立刻切回本应用（用户看不到 PicoOnebot 界面）
	 * @return true = 端口已就绪
	 */
	suspend fun launchAndWait(
		context: Context,
		appActivityClass: Class<*>? = null,
		silent: Boolean = true,
		timeoutMs: Long = 20_000
	): Boolean {
		// 已经在跑
		if (isPicoRunning()) return true

		if (startPicoActivity(context).not() && isPicoRunning()) return true

		if (silent && appActivityClass != null) {
			kotlinx.coroutines.delay(1_200)
			bringAppToFront(context, appActivityClass)
		}

		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (isPicoRunning()) {
				// 再等一会儿让控制台完全起来
				kotlinx.coroutines.delay(800)
				return true
			}
			kotlinx.coroutines.delay(500)
		}
		return false
	}

	/** 二维码是否已就绪（登录页已出现）。 */
	suspend fun isQrReady(client: PicoOnebotClient): Boolean = runCatching {
		val info = client.qr(forceRefresh = false)
		info.hasFragment || info.status == PicoOnebotClient.QrStatus.OK
	}.getOrDefault(false)

	suspend fun awaitQrReady(client: PicoOnebotClient, waitMs: Long = 6_000): Boolean {
		val deadline = System.currentTimeMillis() + waitMs
		while (System.currentTimeMillis() < deadline) {
			if (isQrReady(client)) return true
			kotlinx.coroutines.delay(600)
		}
		return false
	}


	// ── 后台弹出界面权限（方案 A）──────────────────────────────────

	/**
	 * 检查是否拥有「后台弹出界面」权限（又名：悬浮窗、
	 * 显示在其它应用上层、Sanctum）。
	 *
	 * 不同 ROM 叫法不同，这里用的是统一判据：
	 * `Settings.canDrawOverlays()`。它在 Android 6+ 都有，而且
	 * 拥有它的应用在后台调 startActivity 不会被 BAL 拦截。
	 */
	fun canDrawOverlays(context: Context): Boolean =
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
			android.provider.Settings.canDrawOverlays(context)
		} else true

	/**
	 * 跳到系统设置让用户开「后台弹出界面 / 悬浮窗」权限。
	 *
	 * 优先试 OPPO/ColorOS 专属入口，再退到 AOSP 标准入口。
	 */
	fun requestOverlayPermission(context: Context): Boolean {
		if (canDrawOverlays(context)) return true

		val candidates = listOf(
			// AOSP 标准（ColorOS / MIUI / EMUI 都会转发）
			Intent(
				android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
				android.net.Uri.parse("package:${context.packageName}")
			),
			// OPPO 旧版入口（部分 ColorOS 上可用）
			Intent("com.oppo.settings.accessibility").setPackage("com.coloros.safecenter"),
			Intent("com.oplus.settings.SECURE_SETTINGS").setPackage("com.oplus.safecenter"),
			// 最后退到应用详情页
			Intent(
				android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				android.net.Uri.parse("package:${context.packageName}")
			)
		)

		for (intent in candidates) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if (runCatching { context.startActivity(intent) }.isSuccess) {
				Log.i(TAG, "overlay permission requested via ${intent.action}")
				return true
			}
		}
		return false
	}

	/**
	 * 弹窗式提醒用户去开权限（用 Toast + 跳转，不依赖 Dialog）。
	 */
	fun ensureOverlay(context: Context): Boolean {
		if (canDrawOverlays(context)) return true
		android.widget.Toast.makeText(
			context,
			"需要「后台弹出界面」权限才能静默启动协议端",
			android.widget.Toast.LENGTH_LONG
		).show()
		return requestOverlayPermission(context)
	}

	/**
	 * PicoOnebot 是否在运行。
	 *
	 * 实测发现单次 800ms 超时在某些机器上会误判（系统暂时卡顿），
	 * 这里改成：先尝试 TCP 连接（多次、更长超时），再用端口表兜底。
	 */
	fun isPicoRunning(): Boolean {
		// 1) TCP 连接（最可靠）
		repeat(2) { attempt ->
			try {
				java.net.Socket().use { s ->
					s.connect(java.net.InetSocketAddress("127.0.0.1", CONSOLE_PORT), 2_500)
					return true
				}
			} catch (t: Throwable) {
				if (attempt == 0) Thread.sleep(150)
			}
		}

		// 2) 端口表兜底（不依赖网络堆）
		return try {
			java.io.File("/proc/net/tcp6").readLines(Charsets.UTF_8).any { line ->
				// 本地地址 0.0.0.0:17D3 或 类似，状态 0A=LISTEN
				line.contains(":17D3") && line.contains(" 0A ")
			}
		} catch (t: Throwable) {
			false
		}
	}

	/**
	 * 仅在「需要登录」时才展示 PicoOnebot 的界面（让用户看到二维码 / 同意隐私政策）。
	 */
	fun bringPicoToFrontForLogin(context: Context): Boolean = startPicoActivity(context)
}
