package com.crsyyds.suzubot.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import com.crsyyds.suzubot.pico.CredentialStore
import com.crsyyds.suzubot.pico.PicoOnebotClient
import com.crsyyds.suzubot.runtime.Installer
import com.crsyyds.suzubot.runtime.RuntimeDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 首次引导流程的**状态机**（不含任何 UI）。
 *
 * 重要前提：PicoOnebot 是「手表 QQ + Hook 注入」后重打包的产物，
 * 包名与原版手表 QQ 相同（com.tencent.qqlite），因此：
 *   - 只需安装一个 APK，不需要先装原版手表 QQ
 *   - 安装 PicoOnebot 会覆盖设备上已有的手表 QQ
 */
class SetupFlow(private val context: Context) {

	// ── 步骤定义 ──────────────────────────────────────────────────────────────

	enum class Step(val order: Int, val title: String) {
		DEVICE_CHECK(0, "检查设备"),
		INSTALL(1, "安装协议端"),
		START_PROTOCOL(2, "启动协议端"),
		INIT_PASSWORD(3, "初始化控制台"),
		SCAN_LOGIN(4, "扫码登录 QQ"),
		CONFIGURE_BACKEND(5, "连接机器人后端"),
		CONFIGURE_AI(6, "配置 AI"),
		DONE(7, "完成");

		companion object {
			val total get() = values().size
		}
	}

	/** 单步执行结果。 */
	sealed class Outcome {
		data class Done(val next: Step?) : Outcome()

		data class NeedUserAction(
			val message: String,
			val actionLabel: String,
			val action: suspend () -> Unit
		) : Outcome()

		/** 需要用户自己挑一个本地 APK 文件。 */
		data class NeedsUserFile(val reason: String) : Outcome()

		data class Progress(
			val message: String,
			val current: Long = 0,
			val total: Long = 0
		) : Outcome()

		data class Failed(val reason: String, val hint: String) : Outcome()
	}

	private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
	private val downloader by lazy { RuntimeDownloader(context, loadManifest()) }

	// ── 状态持久化 ────────────────────────────────────────────────────────────

	var currentStep: Step
		get() = Step.values().getOrElse(prefs.getInt(KEY_STEP, 0)) { Step.DEVICE_CHECK }
		private set(v) = prefs.edit().putInt(KEY_STEP, v.order).apply()

	private fun isPassed(s: Step): Boolean = prefs.getBoolean("passed_${s.name}", false)

	private fun markPassed(s: Step) = prefs.edit().putBoolean("passed_${s.name}", true).apply()

	fun reset() = prefs.edit().clear().apply()

	fun isCompleted(): Boolean = isPassed(Step.DONE)

	var userApkPath: String?
		get() = prefs.getString(KEY_USER_APK, null)
		set(v) = prefs.edit().putString(KEY_USER_APK, v).apply()

	var backendToken: String?
		get() = prefs.getString(KEY_BACKEND_TOKEN, null)
		set(v) = prefs.edit().putString(KEY_BACKEND_TOKEN, v).apply()

	/**
	 * 控制台密码。
	 *
	 * 底层用 CredentialStore（全局单一来源），
	 * 避免 SetupFlow 与 HomeViewModel 各自持有一份导致不同步。
	 */
	var webuiPassword: String?
		get() = CredentialStore.webUiPassword
		set(value) {
			if (value.isNullOrBlank()) return
			CredentialStore.setWebUiPassword(value)
		}


	var loggedInSelfId: String?
		get() = prefs.getString(KEY_SELF_ID, null)
		private set(v) = prefs.edit().putString(KEY_SELF_ID, v).apply()

	// ── AI 配置 ───────────────────────────────────────────────────────────────

	/** 模型 API Key。 */
	var apiKey: String?
		get() = prefs.getString(KEY_API_KEY, null)
		set(v) = prefs.edit().putString(KEY_API_KEY, v).apply()

	/** OpenAI 兼容端点，默认 DeepSeek。 */
	var apiBaseUrl: String
		get() = prefs.getString(KEY_API_BASE, null) ?: "https://api.deepseek.com/v1"
		set(v) = prefs.edit().putString(KEY_API_BASE, v).apply()

	var apiModel: String
		get() = prefs.getString(KEY_API_MODEL, null) ?: "deepseek-chat"
		set(v) = prefs.edit().putString(KEY_API_MODEL, v).apply()

	/** 人设提示词。 */
	var persona: String?
		get() = prefs.getString(KEY_PERSONA, null)
		set(v) = prefs.edit().putString(KEY_PERSONA, v).apply()

	// ── 主入口 ────────────────────────────────────────────────────────────────

	suspend fun runCurrent(
		onProgress: ((Long, Long) -> Unit)? = null,
		onQr: ((PicoOnebotClient.QrInfo) -> Unit)? = null,
		onLoggedIn: ((String) -> Unit)? = null
	): Outcome = withContext(Dispatchers.IO) {
		val step = currentStep
		val outcome = when (step) {
			Step.DEVICE_CHECK -> deviceCheck()
			Step.INSTALL -> installProtocol()
			Step.START_PROTOCOL -> startProtocol()
			Step.INIT_PASSWORD -> initPassword()
			Step.SCAN_LOGIN -> scanLogin(onQr, onLoggedIn)
			Step.CONFIGURE_BACKEND -> configureBackend()
			Step.CONFIGURE_AI -> configureAi()
			Step.DONE -> Outcome.Done(null)
		}
		if (outcome is Outcome.Done) {
			markPassed(step)
			outcome.next?.let { currentStep = it }
		}
		outcome
	}

	fun advanceFrom(step: Step) {
		markPassed(step)
		Step.values().getOrNull(step.order + 1)?.let { currentStep = it }
	}

	fun recheckCurrent(): Step = currentStep

	// ── 步骤实现 ──────────────────────────────────────────────────────────────

	private suspend fun deviceCheck(): Outcome {
		// 32 位能力是唯一硬门槛：手表 QQ 只有 armeabi-v7a 原生库
		if (!RuntimeDownloader.deviceSupports32Bit()) {
			return Outcome.Failed(
				reason = "本设备不支持 32 位应用（${Build.SUPPORTED_ABIS.joinToString()}）",
				hint = "手表 QQ 只提供 armeabi-v7a 原生库，PicoOnebot 依赖它。" +
					"可改用光速虚拟机 / VMOS Pro 等自带转译层的容器运行，或在电脑模拟器里部署。"
			)
		}

		if (Build.VERSION.SDK_INT < Requirements.MIN_SDK) {
			return Outcome.Failed(
				reason = "系统版本过低（Android ${Build.VERSION.RELEASE}，需 ${Requirements.MIN_SDK_NAME}+）",
				hint = "请升级系统，或换一台 Android ${Requirements.MIN_SDK_NAME} 及以上的设备。"
			)
		}

		val free = freeBytes(context.filesDir)
		if (free < Requirements.MIN_FREE_BYTES) {
			return Outcome.Failed(
				reason = "存储空间不足（剩余 ${fmtMb(free)}，至少需要 ${fmtMb(Requirements.MIN_FREE_BYTES)}）",
				hint = "请清理一些空间后重试。"
			)
		}

		return Outcome.Done(Step.INSTALL)
	}

	/**
	 * 安装协议端（无感）。
	 *
	 * 优先走 PackageInstaller.Session（不弹系统页），
	 * 缺权限时引导用户授权；仍失败则回落到 Intent 安装。
	 */
	private suspend fun installProtocol(): Outcome {
		if (isPicoInstalled()) {
			return Outcome.Done(Step.START_PROTOCOL)
		}
		// 包已装但系统索引还没跟上（刚从安装器返回）→ 给它一点时间
		if (awaitPackageInstalled(Requirements.PACKAGE, timeoutMs = 3_000)) {
			return Outcome.Done(Step.START_PROTOCOL)
		}

		val apk = userApkPath?.let(::File)?.takeIf { it.exists() }
			?: downloader.component("protocol")?.let { c ->
				File(downloader.componentDir("protocol"), c.fileName).takeIf { it.exists() }
			}
			?: return Outcome.Failed("找不到 PicoOnebot 安装包", "请返回上一步重试")

		// 权限检查
		if (!Installer.canInstallPackages(context)) {
			return Outcome.NeedUserAction(
				message = "需要先允许本应用「安装未知应用」，之后就能一键装好协议端。",
				actionLabel = "去授权",
				action = { Installer.requestInstallPermission(context) }
			)
		}

		val hadOriginal = isPackageInstalled(Requirements.PACKAGE)

		// 无感安装
		return when (val r = Installer.installViaSession(context, apk)) {
			is Installer.Result.Ok -> {
				// Session 已经 commit，等系统真正把包登记进 PackageManager。
				// 不等待的话，下一轮状态机会立刻查不到包而误判「未安装」。
				val installed = awaitPackageInstalled(Requirements.PACKAGE, timeoutMs = 12_000)
				if (installed) {
					Outcome.Done(Step.START_PROTOCOL)
				} else {
					Outcome.NeedUserAction(
						message = if (hadOriginal) {
							"正在替换设备上已装的手表 QQ / PicoOnebot，请稍候…"
						} else {
							"正在安装协议端，请稍候…"
						},
						actionLabel = "继续",
						action = { /* 由广播回调，UI 会收到 */ }
					)
				}
			}
			Installer.Result.NeedPermission -> Outcome.NeedUserAction(
				message = "系统拒绝安装，请授予「安装未知应用」权限。",
				actionLabel = "去授权",
				action = { Installer.requestInstallPermission(context) }
			)
			is Installer.Result.Failed -> {
				// 回落：Intent 安装
				when (val fb = Installer.installViaIntent(context, apk)) {
					is Installer.Result.NeedUserConfirm -> Outcome.NeedUserAction(
						message = "请在系统弹窗里点「安装」完成协议端安装。",
						actionLabel = "我已安装",
						action = { }
					)
					is Installer.Result.Failed -> Outcome.Failed(
						"安装失败：${fb.message}",
						"可手动到文件管理找到 Download/PicoOnebot*.apk 安装"
					)
					else -> Outcome.Failed("安装失败", "请重试")
				}
			}
			else -> Outcome.Failed("未知安装结果", "请重试")
		}
	}

			private suspend fun startProtocol(): Outcome {
		if (!isPackageInstalled(Requirements.PACKAGE)) {
			return Outcome.Failed("PicoOnebot 尚未安装", "请返回上一步完成安装")
		}

		// 端口活着就算启动成功（密码由下一步处理）
		if (portAlive(6099, 1_500)) {
			return Outcome.Done(Step.INIT_PASSWORD)
		}

		// 静默拉起（不把界面留在前台）
		PrivacyHelper.launchPicoSilently(context, android.app.Activity::class.java, returnToApp = false)
		kotlinx.coroutines.delay(4_000)
		if (portAlive(6099, 1_500)) {
			return Outcome.Done(Step.INIT_PASSWORD)
		}

		return Outcome.NeedUserAction(
			message = "接下来会打开协议端。" +
				"如果看到「扫码查看隐私政策，同意后方可提供服务」，请点「同意」。" +
				"同意一次之后就不用再点了。",
			actionLabel = "打开 PicoOnebot",
			action = { PrivacyHelper.bringPicoToFrontForLogin(context) }
		)
	}

		private suspend fun initPassword(): Outcome {
		val client = PicoOnebotClient()

		// 1) 先看是不是全新安装（必须先初始化密码）
		// 这一步必须在自愈之前：新安装时默认密码也进不去，不能先走自愈
		if (client.needsPasswordInit()) {
			val generated = randomPassword()
			return try {
				client.setCredential(PicoOnebotClient.INITIAL_PASSWORD)
				client.initializePassword(generated)
				webuiPassword = generated
				// 初始化后马上看看是不是已经登录了
				client.setCredential(generated)
				val st = runCatching { client.status() }.getOrNull()
				if (st?.online == true) {
					if (st.selfId.isNotEmpty()) loggedInSelfId = st.selfId
					markPassed(Step.SCAN_LOGIN)
					Outcome.Done(Step.CONFIGURE_BACKEND)
				} else {
					Outcome.Done(Step.SCAN_LOGIN)
				}
			} catch (e: Throwable) {
				Outcome.Failed(
					"初始化控制台密码失败：${e.message}",
					"可在 PicoOnebot 控制台手动改密码，然后重试"
				)
			}
		}

		// 2) 不是新安装 → 尝试凭据自愈（本地存的 → 默认 picopico）
		val cred = resolveConsoleCredential(client)
		if (cred != null) {
			webuiPassword = cred
			val st = runCatching { client.status() }.getOrNull()
			if (st?.online == true) {
				if (st.selfId.isNotEmpty()) loggedInSelfId = st.selfId
				markPassed(Step.SCAN_LOGIN)
				return Outcome.Done(Step.CONFIGURE_BACKEND)
			}
			return Outcome.Done(Step.SCAN_LOGIN)
		}

		// 3) 都不行 → 让用户重置协议端
		return Outcome.NeedUserAction(
			message = "协议端控制台密码与本地记录不一致（可能是重装过）。" +
				"重置协议端可以清空它的数据并恢复默认密码，但会要求重新扫码登录。",
			actionLabel = "重置协议端",
			action = { resetProtocol() }
		)
	}

	/** 卸载并重装协议端，清掉它的配置（含控制台密码）。 */
	private suspend fun resetProtocol() {
		runCatching {
			val pm = context.packageManager
			@Suppress("DEPRECATION")
			val pkg = Requirements.PACKAGE
			// 走系统的卸载入口（普通应用无静默权限）
			val intent = android.content.Intent(android.content.Intent.ACTION_DELETE)
				.setData(android.net.Uri.parse("package:$pkg"))
				.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(intent)
		}
	}

			private suspend fun scanLogin(
		onQr: ((PicoOnebotClient.QrInfo) -> Unit)?,
		onLoggedIn: ((String) -> Unit)? = null
	): Outcome {
		val client = PicoOnebotClient()
		client.setCredential(webuiPassword ?: PicoOnebotClient.INITIAL_PASSWORD)

		// 尝试凭据自愈（App 重装后密码丢了）
		resolveConsoleCredential(client)

		// 关键：PicoOnebot 的 QQ 登录是持久的（keystore）。
		// 重装 App / 重启进程后很可能已经是登录态，这时**不需要扫码**。
		if (runCatching { client.status() }.getOrNull()?.online == true) {
			val id = runCatching { client.status() }.getOrNull()?.selfId.orEmpty()
			if (id.isNotEmpty()) loggedInSelfId = id
			return Outcome.Done(Step.CONFIGURE_BACKEND)
		}

		// 未登录 → 检查登录页是否就绪（隐私政策已同意）
		if (!PrivacyHelper.isQrReady(client)) {
			PrivacyHelper.bringPicoToFrontForLogin(context)
			if (!PrivacyHelper.awaitQrReady(client, 6_000)) {
				return Outcome.NeedUserAction(
					message = "还没拿到二维码。请打开协议端，" +
						"把隐私政策那页点一下「同意」，然后回来。",
					actionLabel = "去同意隐私政策",
					action = { PrivacyHelper.bringPicoToFrontForLogin(context) }
				)
			}
		}

		val selfId = try {
			client.awaitLogin(timeoutMs = 3 * 60_000, onQr = { info -> onQr?.invoke(info) }, onLoggedIn = { id -> onLoggedIn?.invoke(id) })
		} catch (e: PicoOnebotClient.UnauthorizedException) {
			return Outcome.Failed("控制台凭据失效", "请回到上一步重新初始化密码")
		} catch (e: Throwable) {
			return Outcome.Failed("扫码流程中断：${e.message}", "请重试")
		} ?: return Outcome.Failed("二维码等待超时", "请重试，或用手机 QQ 尽快扫码")

		loggedInSelfId = selfId
		return Outcome.Done(Step.CONFIGURE_BACKEND)
	}

	/**
	 * 控制台凭据自愈：重装 App 会丢密码，但 PicoOnebot 里密码还在。
	 * 依次尝试：本地存的 → 默认 picopico。成功就记下来。
	 */
	private suspend fun resolveConsoleCredential(client: PicoOnebotClient): String? {
		val candidates = linkedSetOf<String>()
		webuiPassword?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
		candidates.add(PicoOnebotClient.INITIAL_PASSWORD)

		for (pwd in candidates) {
			client.setCredential(pwd)
			if (runCatching { client.status() }.isSuccess) {
				if (webuiPassword != pwd) webuiPassword = pwd
				return pwd
			}
		}
		return null
	}

	private suspend fun configureBackend(): Outcome {
		val client = PicoOnebotClient()
		client.setCredential(webuiPassword ?: PicoOnebotClient.INITIAL_PASSWORD)

		val ws = JSONObject()
			.put("name", "local-backend")
			.put("enable", true)
			.put("host", "127.0.0.1")
			.put("port", 3001)
			.put("messagePostFormat", "array")
			.put("reportSelfMessage", false)
			.put("debug", false)
		backendToken?.takeIf { it.isNotEmpty() }?.let { ws.put("token", it) }

		val network = JSONObject()
			.put("wsServers", JSONArray().put(ws))
			.put("wsClients", JSONArray())
			.put("httpServers", JSONArray())
			.put("httpClients", JSONArray())

		return try {
			val reply = client.updateConfig(JSONObject().put("network", network))
			prefs.edit().putString(KEY_LAST_CONFIG_REPLY, reply.toString()).apply()
			Outcome.Done(Step.DONE)
		} catch (e: Throwable) {
			Outcome.Failed("写配置失败：${e.message}", "可在 PicoOnebot 控制台手动添加 127.0.0.1:3001")
		}
	}

	/**
	 * 配置 AI：只做「有没有填 Key」的检查，真正的连通性由主页的 BotService 验证。
	 */
	private suspend fun configureAi(): Outcome {
		val key = apiKey?.trim().orEmpty()
		if (key.isEmpty()) {
			return Outcome.NeedUserAction(
				message = "还需要一个模型 API Key 才能让机器人说话。可以先跳过，之后在主页补。",
				actionLabel = "去填 API Key",
				action = { launchApp(Requirements.PACKAGE) }
			)
		}
		return Outcome.Done(Step.DONE)
	}


	/** 端口是否在监听（不依赖密码）。 */
	private fun portAlive(port: Int, timeoutMs: Int): Boolean = try {
		java.net.Socket().use { s ->
			s.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
			true
		}
	} catch (t: Throwable) {
		false
	}

	// ── 工具 ──────────────────────────────────────────────────────────────────

	/**
	 * 判断包是否已安装。
	 *
	 * 为什么要多重判据：
	 * PackageManager 有缓存，刚装完的包可能一瞬间查不到（尤其是走
	 * 系统安装器的情况，广播还没回来）。这里用三种方式交叉验证。
	 */
	private fun isPackageInstalled(pkg: String): Boolean {
		val pm = context.packageManager

		// 方式 1：getPackageInfo
		try {
			@Suppress("DEPRECATION")
			pm.getPackageInfo(pkg, 0)
			return true
		} catch (_: Exception) { }

		// 方式 2：getInstalledPackages 循序找（不走单包缓存）
		try {
			@Suppress("DEPRECATION")
			val all = pm.getInstalledPackages(0)
			if (all.any { it.packageName == pkg }) return true
		} catch (_: Exception) { }

		// 方式 3：直接问包管理器能不能解析到启动 Activity
		try {
			val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
				.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
				.setPackage(pkg)
			if (pm.queryIntentActivities(intent, 0).isNotEmpty()) return true
		} catch (_: Exception) { }

		return false
	}

	/**
	 * 带重试的安装检测。
	 *
	 * 刚装完的包有时需要几百毫秒才能被系统索引到，
	 * 这里每 250ms 重试一次，最多等 [timeoutMs]。
	 */
	private suspend fun awaitPackageInstalled(pkg: String, timeoutMs: Long = 8_000): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		var attempt = 0
		while (System.currentTimeMillis() < deadline) {
			if (isPackageInstalled(pkg)) return true
			attempt++
			kotlinx.coroutines.delay(250)
		}
		android.util.Log.w("SetupFlow", "包 $pkg 在 ${timeoutMs}ms 内未被检测到（尝试 $attempt 次）")
		return false
	}

	/**
	 * 判断设备上装的是不是 PicoOnebot（而非原版手表 QQ）。
	 *
	 * 依据：PicoOnebot 注入了自己的入口/组件；原版手表 QQ 的启动 Activity 是
	 * com.tencent.qqnt.watch.app.JumpActivity，而 PicoOnebot 会额外暴露
	 * pico.onebot 包下的组件。先用「能否连上 6099 控制台」做粗判，
	 * 兜底再检查是否有 pico.onebot 相关类。
	 */
	/**
	 * 判断设备上装的是不是 PicoOnebot（而非原版手表 QQ）。
	 *
	 * 依据：原版手表 QQ 也是 com.tencent.qqlite，无法从包名区分。
	 * 这里用「6099 控制台是否在监听」作为运行时判据，
	 * 未启动时退回「包已安装」的弱判断。
	 */
	private fun isPicoInstalled(): Boolean {
		// 包不存在 → 肯定没装
		if (!isPackageInstalled(Requirements.PACKAGE)) return false
		// 包在 → 即使 6099 没起来也算已安装（后续 START_PROTOCOL 会负责拉起）
		// 有 PicoOnebot 在跑 → 确定是它
		if (portAlive(6099, 800)) return true
		// 装了但没跑：保守返回 true，让后续 START_PROTOCOL 去拉起并验证
		return true
	}

	private fun launchInstaller(apk: File) {
		val uri: Uri = FileProvider.getUriForFile(
			context, "${context.packageName}.fileprovider", apk
		)
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(uri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}

	private fun launchApp(pkg: String) {
		context.packageManager.getLaunchIntentForPackage(pkg)?.let {
			it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(it)
		}
	}

	private fun freeBytes(dir: File): Long = try {
		StatFs(dir.absolutePath).availableBytes
	} catch (t: Throwable) {
		0L
	}

	private fun fmtMb(bytes: Long): String = "${bytes / 1024 / 1024} MB"

	private fun randomPassword(): String {
		val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
		return (1..16).map { alphabet.random() }.joinToString("")
	}

	private fun loadManifest(): JSONObject = try {
		context.assets.open("runtime-manifest.json")
			.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
	} catch (t: Throwable) {
		JSONObject()
	}

	fun lastConfigReply(): String? = prefs.getString(KEY_LAST_CONFIG_REPLY, null)

	object Requirements {
		const val MIN_SDK = 24
		const val MIN_SDK_NAME = "7.0"
		const val MIN_FREE_BYTES = 300L * 1024 * 1024
		/** PicoOnebot 与手表 QQ 同包名。 */
		const val PACKAGE = "com.tencent.qqlite"
	}

	companion object {
		private const val KEY_STEP = "step"
		private const val KEY_USER_APK = "user_apk"
		private const val KEY_BACKEND_TOKEN = "backend_token"
		private const val KEY_WEBUI_PASSWORD = "webui_password"
		private const val KEY_SELF_ID = "self_id"
		private const val KEY_LAST_CONFIG_REPLY = "last_config_reply"
		private const val KEY_API_KEY = "api_key"
		private const val KEY_API_BASE = "api_base"
		private const val KEY_API_MODEL = "api_model"
		private const val KEY_PERSONA = "persona"
	}
}
