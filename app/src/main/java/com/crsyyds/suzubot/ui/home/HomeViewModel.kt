package com.crsyyds.suzubot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crsyyds.suzubot.backend.BotRuntime
import com.crsyyds.suzubot.backend.ModelChainManager
import com.crsyyds.suzubot.pico.CredentialStore
import com.crsyyds.suzubot.pico.PicoOnebotClient
import com.crsyyds.suzubot.runtime.PicoRuntimeMode
import com.crsyyds.suzubot.setup.PrivacyHelper
import com.crsyyds.suzubot.setup.SetupFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
	val loading: Boolean = false,
	val status: PicoOnebotClient.Status? = null,
	val error: String? = null,
	val botConnected: Boolean = false,
	val botSelfId: String = "",
	val botHandled: Int = 0,
	val botError: String = "",
	val modelsConfigured: Boolean = false,
	/** 协议端进程是否在跑 */
	val picoRunning: Boolean = false,
	/** 正在拉起协议端 */
	val picoLaunching: Boolean = false,
	/** 控制台密码是否可用 */
	val consoleAuthed: Boolean = false,
	/** 是否已经填过 API Key（用于引导高亮） */
	val apiKeyConfigured: Boolean = false,
	/** 是否已经自定义过人设（用于引导高亮） */
	val personaConfigured: Boolean = false,
	/** 是否跑在外部运行环境（纯 64 位设备，协议端由用户自己起）。 */
	val externalRuntime: Boolean = false,
	/** 外部模式「启动」按钮的提示；空 = 无提示。 */
	val launchNotice: String = "",
	/** 正在验证控制台密码 */
	val credentialSaving: Boolean = false,
	/** 控制台密码验证反馈：null = 无，true = 成功，false = 失败 */
	val credentialOk: Boolean? = null,
	val credentialMessage: String = ""
) {
	/** 首次配置：两项都没配 */
	val needsSetup: Boolean get() = !modelsConfigured || !personaConfigured
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

	private val flow = SetupFlow(app)
	private val chainManager = ModelChainManager(app)
	private val _state = MutableStateFlow(HomeUiState(externalRuntime = isExternal()))
	val state: StateFlow<HomeUiState> = _state.asStateFlow()

	private val client = PicoOnebotClient()

	init {
		CredentialStore.init(app)
		startPolling()
		refresh()
	}

	/** 外部运行环境：本机不装、不拉起、不重置协议端。 */
	private fun isExternal(): Boolean =
		PicoRuntimeMode.current() == PicoRuntimeMode.EXTERNAL_ARM64

	/** `isPicoRunning()` 会开 TCP socket，必须离开主线程，否则外部模式会误报离线。 */
	private suspend fun picoRunningOffMain(): Boolean =
		withContext(Dispatchers.IO) { PrivacyHelper.isPicoRunning() }

	/**
	 * 解析可用的控制台密码。
	 *
	 * 关键改进：
	 *  1. 密码来自 CredentialStore（全局单一来源），避免与 SetupFlow 不同步
	 *  2. 只有「明确的 401」才换下一个密码；网络错误直接上报，不当成密码错
	 *  3. 自愈成功后立刻写回磁盘
	 */
	private suspend fun resolveCredential(): String? {
		val candidates = CredentialStore.candidates()
		var lastAuthError: Throwable? = null

		for (pwd in candidates) {
			client.setCredential(pwd)
			val r = runCatching { client.status() }
			if (r.isSuccess) {
				CredentialStore.markGood(pwd)
				return pwd
			}
			val err = r.exceptionOrNull()
			if (err is PicoOnebotClient.UnauthorizedException) {
				lastAuthError = err
				continue   // 这个密码不对，试下一个
			}
			// 非认证错误（网络/超时）→ 直接抛，让上层报「连接失败」而不是「密码错」
			throw err ?: IllegalStateException("读取状态失败")
		}

		if (lastAuthError != null) return null
		return null
	}

	fun refresh() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true, error = null)
			val running = picoRunningOffMain()
			val external = _state.value.externalRuntime

			// 控制台一旦可达，外部模式的「先去启动」提示就过期了
			if (running) _state.value = _state.value.copy(launchNotice = "")

			if (!running) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = if (external) "外部协议端未连接" else "协议端未运行",
					picoRunning = false,
					consoleAuthed = false,
					modelsConfigured = chainManager.usable().isNotEmpty(),
					apiKeyConfigured = chainManager.usable().isNotEmpty(),
					personaConfigured = hasCustomPersona()
				)
				return@launch
			}

			// 密码自愈
			val cred = try {
				resolveCredential()
			} catch (e: Throwable) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = "连接控制台失败：${e.message}",
					picoRunning = true,
					consoleAuthed = false,
					modelsConfigured = chainManager.usable().isNotEmpty()
				)
				return@launch
			}

			if (cred == null) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = "控制台密码不匹配",
					picoRunning = true,
					consoleAuthed = false,
					modelsConfigured = chainManager.usable().isNotEmpty(),
					apiKeyConfigured = chainManager.usable().isNotEmpty(),
					personaConfigured = hasCustomPersona()
				)
				return@launch
			}

			try {
				val s = client.status()
				_state.value = _state.value.copy(
					loading = false,
					status = s,
					error = null,
					picoRunning = true,
					consoleAuthed = true,
					modelsConfigured = chainManager.usable().isNotEmpty(),
					apiKeyConfigured = chainManager.usable().isNotEmpty(),
					personaConfigured = hasCustomPersona()
				)
			} catch (e: Throwable) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = "读取状态失败：${e.message}",
					picoRunning = true,
					consoleAuthed = false
				)
			}
		}
	}

	/** 是否已经自定义过人设（files/persona/ 下有文件）。 */
	private fun hasCustomPersona(): Boolean = runCatching {
		val dir = java.io.File(getApplication<Application>().filesDir, "persona")
		dir.exists() && dir.listFiles()?.any { it.name.endsWith(".md") } == true
	}.getOrDefault(false)

	/** 外部模式的启动动作：本机不装、不拉起、不开守卫，只重新探测一次。 */
	fun launchPico() {
		if (_state.value.picoLaunching) return

		if (_state.value.externalRuntime) {
			_state.value = _state.value.copy(
				picoLaunching = false,
				launchNotice = "请先在独立运行环境里启动 PicoOnebot，" +
					"确认控制台监听 127.0.0.1:6099，再回来点「重新检测」。"
			)
			refresh()
			return
		}

		viewModelScope.launch {
			_state.value = _state.value.copy(picoLaunching = true, botError = "")
			val app = getApplication<Application>()

			try {
				if (picoRunningOffMain()) {
					_state.value = _state.value.copy(picoLaunching = false, picoRunning = true)
					refresh()
					return@launch
				}

				onPicoLaunching?.invoke()
				if (!PrivacyHelper.launchPicoSilently(app, com.crsyyds.suzubot.ui.setup.SetupActivity::class.java)) {
					_state.value = _state.value.copy(
						picoLaunching = false,
						botError = "无法拉起协议端，请确认已安装 PicoOnebot"
					)
					return@launch
				}

				var ok = false
				for (i in 0 until 50) {
					delay(500)
					if (picoRunningOffMain()) {
						ok = true
						break
					}
				}

				if (ok) {
					delay(1000)
					resolveCredential()
					_state.value = _state.value.copy(picoLaunching = false, picoRunning = true)
				} else {
					_state.value = _state.value.copy(
						picoLaunching = false,
						picoRunning = false,
						botError = "启动超时。若看到了隐私政策页，请点「同意」后重试。"
					)
				}
			} catch (t: Throwable) {
				_state.value = _state.value.copy(
					picoLaunching = false,
					botError = t.message ?: "启动失败"
				)
			}
		}
	}

	/**
	 * 重置协议端：卸载并重装，清空它的数据（含控制台密码）。
	 *
	 * 用于：App 重装后本地密码丢失，但协议端里的密码还在，
	 * 导致永远无法认证。重置后恢复默认密码 picopico。
	 */
	/**
	 * 重置协议端数据。
	 *
	 * 场景：App 曾经用随机密码初始化过协议端，后来 App 被卸载，
	 * 密码永久丢失，双方都进不去。只能清空协议端数据重来。
	 *
	 * 流程：卸载 → 用户确认 → 自动重装 → 设新密码（确定性的）
	 */
	/**
	 * 重置协议端（彻底清空密码）。
	 *
	 * 场景：PicoOnebot 里留着不可知的旧密码，App 这边也没记录，
	 * 双方都进不去。只能把协议端连数据一起卸掉重装。
	 *
	 * 实现：用 PackageInstaller.uninstall()，不靠系统弹窗（那个在 ColorOS 上不可靠）。
	 */
	/**
	 * 重置协议端（引导用户手动卸载）。
	 *
	 * 为什么不能自动卸载：
	 *  实测 OPPO ColorOS 上，第三方调 PackageInstaller.uninstall() 会让
	 *  com.android.packageinstaller 直接崩溃（system_app_crash），
	 *  连带着我们的 App 一起闪退。ACTION_DELETE 也不可靠（界面不一定弹）。
	 *
	 * 所以改成：打开系统「应用信息」页，让用户自己点卸载。
	 * 这是唯一不会出问题的路径，而且用户点完就能看到结果。
	 */
	/**
	 * 重置协议端。
	 *
	 * 实测结论（关键）：
	 *  PicoOnebot 的控制台密码存在 /data/user_de/0/com.tencent.qqlite/，
	 *  这是**设备加密存储**，卸载 App 时 Android 不保证清理，
	 *  普通应用也无权删除它。
	 *
	 *  所以「卸载重装」解决不了密码问题——密码会从 DE 存储里恢复。
	 *
	 *  唯一可靠的路：让用户在系统设置里对 PicoOnebot 做「清除数据」，
	 *  那个操作会同时清 DE 存储。
	 */
	fun resetProtocol() {
		// 外部模式下协议端不在本应用管理范围内：不跳应用详情页、不卸载、不清数据
		if (_state.value.externalRuntime) {
			_state.value = _state.value.copy(
				botError = "外部运行环境不会卸载或清除协议端数据，请改用上面的密码验证。"
			)
			return
		}

		viewModelScope.launch {
			val app = getApplication<Application>()

			// 直接跳到 PicoOnebot 的应用详情页
			val ok = runCatching {
				val intent = android.content.Intent(
					android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
				).setData(android.net.Uri.parse("package:${SetupFlow.Requirements.PACKAGE}"))
					.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
				app.startActivity(intent)
				true
			}.getOrDefault(false)

			_state.value = _state.value.copy(
				botError = if (ok) {
					"已打开 PicoOnebot 的应用详情页。\n\n" +
						"在那里点「存储占用」→「清除数据」，" +
						"然后回到本应用重试。\n\n" +
						"（注意：只点「卸载」不行，必须是「清除数据」）"
				} else {
					"请手动去：设置 → 应用管理 → 找到 PicoOnebot → " +
						"存储占用 → 清除数据"
				}
			)
		}
	}

	/**
	 * 轮询等待用户完成卸载，然后自动重装。
	 *
	 * 为什么要轮询：用户去系统界面操作，我们收不到回调，
	 * 只能自己盯着包是不是没了。
	 */
	private suspend fun waitForUninstallAndReinstall(app: Application) {
		// 最多等 5 分钟（用户可能要找一会儿菜单）
		val deadline = System.currentTimeMillis() + 5 * 60_000
		var sawInstalled = true

		while (System.currentTimeMillis() < deadline) {
			delay(2_000)
			val installed = com.crsyyds.suzubot.runtime.Installer
				.isInstalled(app, SetupFlow.Requirements.PACKAGE)

			if (installed) {
				sawInstalled = true
				continue
			}

			// 刚发现被卸载
			if (sawInstalled) {
				_state.value = _state.value.copy(botError = "检测到已卸载，正在重装…")
				delay(1_500)   // 等系统安静一下
				val done = reinstallProtocol(app)
				_state.value = _state.value.copy(
					botError = if (done) {
						"已重装协议端。请点「启动协议端」，然后重新扫码登录。"
					} else {
						"重装失败，请重新走一遍部署向导。"
					},
					picoRunning = picoRunningOffMain()
				)
				return
			}
		}
	}

	/** 从 assets 取包并重装协议端。 */
	private suspend fun reinstallProtocol(app: Application): Boolean {
		val apk = runCatching {
			val manifest = org.json.JSONObject(
				app.assets.open("runtime-manifest.json").bufferedReader().use { it.readText() }
			)
			val downloader = com.crsyyds.suzubot.runtime.RuntimeDownloader(app, manifest)
			val comp = downloader.component("protocol") ?: return false
			when (val r = downloader.ensure(comp)) {
				is com.crsyyds.suzubot.runtime.RuntimeDownloader.Result.Ok -> r.file
				else -> null
			}
		}.getOrNull() ?: return false

		return when (val r = com.crsyyds.suzubot.runtime.Installer
				.installViaSession(app, apk)) {
			is com.crsyyds.suzubot.runtime.Installer.Result.Ok -> {
				// 等安装完成
				for (i in 0 until 24) {
					delay(500)
					if (com.crsyyds.suzubot.runtime.Installer
							.isInstalled(app, SetupFlow.Requirements.PACKAGE)) {
						return true
					}
				}
				false
			}
			else -> false
		}
	}

	/** 校验外部控制台密码，通过后写入本机的 CredentialStore。 */
	fun saveConsoleCredential(input: String) {
		if (!_state.value.externalRuntime || _state.value.credentialSaving) return
		if (input.isBlank()) {
			_state.value = _state.value.copy(credentialOk = false, credentialMessage = "请先输入控制台密码")
			return
		}

		viewModelScope.launch {
			_state.value = _state.value.copy(credentialSaving = true, credentialOk = null, credentialMessage = "")

			// 15 秒超时前先判活，别让用户干等
			if (!picoRunningOffMain()) {
				_state.value = _state.value.copy(
					credentialSaving = false,
					credentialOk = false,
					credentialMessage = "127.0.0.1:6099 无响应，先在外部环境里启动协议端"
				)
				return@launch
			}

			val result = runCatching {
				val status = PicoOnebotClient(credential = input).status()
				check(status.version.isNotBlank())
			}

			if (result.isSuccess) {
				CredentialStore.markGood(input)
				_state.value = _state.value.copy(
					credentialSaving = false,
					credentialOk = true,
					credentialMessage = "验证通过，已保存到本机"
				)
				refresh()
				return@launch
			}

			val err = result.exceptionOrNull()
			_state.value = _state.value.copy(
				credentialSaving = false,
				credentialOk = false,
				credentialMessage = if (err is PicoOnebotClient.UnauthorizedException) {
					"密码被控制台拒绝（401），请确认是那个环境里正在用的密码"
				} else {
					"无法验证外部 PicoOnebot 控制台"
				}
			)
		}
	}

	fun startBot() {
		val app = getApplication<Application>()
		if (chainManager.usable().isEmpty()) {
			_state.value = _state.value.copy(botError = "还没配置模型，请到「模型配置」填 API Key")
			return
		}
		if (!_state.value.picoRunning) {
			_state.value = _state.value.copy(
				botError = if (_state.value.externalRuntime) {
					"外部协议端没连上，先在独立运行环境里启动它"
				} else {
					"协议端没在运行，先点「启动协议端」"
				}
			)
			return
		}
		viewModelScope.launch {
			runCatching { BotRuntime.get(app) }
				.onFailure { _state.value = _state.value.copy(botError = it.message ?: "启动失败") }
		}
	}

	fun stopBot() {
		BotRuntime.stop()
		_state.value = _state.value.copy(botConnected = false, botSelfId = "")
	}

	/** 由 Activity 注入：启动协议端时开启守卫窗口。 */
	var onPicoLaunching: (() -> Unit)? = null

	private fun startPolling() {
		viewModelScope.launch {
			while (isActive) {
				val engine = BotRuntime.currentOrNull()
				val running = picoRunningOffMain()
				_state.value = _state.value.copy(
					botConnected = engine?.connected ?: false,
					botSelfId = engine?.selfId.orEmpty(),
					botHandled = engine?.handledCount ?: 0,
					botError = engine?.lastError?.takeIf { it.isNotBlank() } ?: _state.value.botError,
					picoRunning = running
				)
				delay(3000)
			}
		}
	}
}
