package com.crsyyds.suzubot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crsyyds.suzubot.backend.BotRuntime
import com.crsyyds.suzubot.backend.ModelChainManager
import com.crsyyds.suzubot.pico.CredentialStore
import com.crsyyds.suzubot.pico.PicoOnebotClient
import com.crsyyds.suzubot.setup.PrivacyHelper
import com.crsyyds.suzubot.setup.SetupFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
	val personaConfigured: Boolean = false
) {
	/** 首次配置：两项都没配 */
	val needsSetup: Boolean get() = !modelsConfigured || !personaConfigured
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

	private val flow = SetupFlow(app)
	private val chainManager = ModelChainManager(app)
	private val _state = MutableStateFlow(HomeUiState())
	val state: StateFlow<HomeUiState> = _state.asStateFlow()

	private val client = PicoOnebotClient()

	init {
		CredentialStore.init(app)
		startPolling()
		refresh()
	}

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
			val running = PrivacyHelper.isPicoRunning()

			if (!running) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = "协议端未运行",
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

	fun launchPico() {
		if (_state.value.picoLaunching) return
		viewModelScope.launch {
			_state.value = _state.value.copy(picoLaunching = true, botError = "")
			val app = getApplication<Application>()

			try {
				if (PrivacyHelper.isPicoRunning()) {
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
					if (PrivacyHelper.isPicoRunning()) {
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
	fun resetProtocol() {
		viewModelScope.launch {
			_state.value = _state.value.copy(botError = "正在重置协议端…")
			val app = getApplication<Application>()

			// 1) 清掉本地记录的密码（反正已经失效）
			CredentialStore.clear()

			// 2) 卸载协议端
			val uninstalled = runCatching {
				val pm = app.packageManager
				@Suppress("DEPRECATION")
				pm.getPackageInfo("com.tencent.qqlite", 0)
				// 用 Intent 让系统弹卸载确认（普通应用无静默卸载权限）
				val intent = android.content.Intent(android.content.Intent.ACTION_DELETE)
					.setData(android.net.Uri.parse("package:com.tencent.qqlite"))
					.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
				app.startActivity(intent)
				true
			}.getOrDefault(false)

			if (!uninstalled) {
				_state.value = _state.value.copy(
					botError = "无法拉起卸载界面，请到系统设置里手动卸载「PicoOnebot」"
				)
				return@launch
			}

			_state.value = _state.value.copy(
				botError = "已打开卸载界面。卸载完成后回到本应用，重新走一遍向导即可。"
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
			_state.value = _state.value.copy(botError = "协议端没在运行，先点「启动协议端」")
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
				val running = PrivacyHelper.isPicoRunning()
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
