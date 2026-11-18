package com.crsyyds.suzubot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crsyyds.suzubot.backend.BotRuntime
import com.crsyyds.suzubot.backend.ModelChainManager
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
	/** 协议端进程是否在跑（6099 有监听） */
	val picoRunning: Boolean = false,
	/** 正在拉起协议端 */
	val picoLaunching: Boolean = false,
	/** 控制台密码是否可用 */
	val consoleAuthed: Boolean = false
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

	private val flow = SetupFlow(app)
	private val chainManager = ModelChainManager(app)
	private val _state = MutableStateFlow(HomeUiState())
	val state: StateFlow<HomeUiState> = _state.asStateFlow()

	private val client = PicoOnebotClient()

	/** 启动协议端时回调（Activity 用它关闭“守卫窗口”） */
	var onPicoLaunching: (() -> Unit)? = null

	init {
		startPolling()
		refresh()
	}

	/**
	 * 凭据自愈。
	 *
	 * 场景：App 重装 / 数据被清 → webuiPassword 丢失，但 PicoOnebot 里密码还在。
	 * 依次试：本地存的 → 默认 picopico。
	 */
	private suspend fun resolveCredential(): String? {
		val candidates = linkedSetOf<String>()
		flow.webuiPassword?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
		candidates.add(PicoOnebotClient.INITIAL_PASSWORD)

		for (pwd in candidates) {
			client.setCredential(pwd)
			if (runCatching { client.status() }.isSuccess) {
				if (flow.webuiPassword != pwd) flow.webuiPassword = pwd
				return pwd
			}
		}
		return null
	}

	fun refresh() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true, error = null)
			val app = getApplication<Application>()
			var running = PrivacyHelper.isPicoRunning()

			// 双保险：端口探测可能误判，再直接试一次 API
			if (!running) {
				running = runCatching { client.ping(2_000) }.getOrDefault(false)
			}

			if (!running) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = "协议端未运行",
					picoRunning = false,
					consoleAuthed = false,
					modelsConfigured = chainManager.usable().isNotEmpty()
				)
				return@launch
			}

			// 密码自愈
			val cred = resolveCredential()
			if (cred == null) {
				_state.value = _state.value.copy(
					loading = false,
					status = null,
					error = "控制台密码不匹配",
					picoRunning = true,
					consoleAuthed = false,
					modelsConfigured = chainManager.usable().isNotEmpty()
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
					modelsConfigured = chainManager.usable().isNotEmpty()
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

	/**
	 * 启动协议端。
	 *
	 * 主人反馈的核心场景：后台被清理后重进 App，需要手动拉起 PicoOnebot。
	 */
	fun launchPico() {
		if (_state.value.picoLaunching) return
		viewModelScope.launch {
			_state.value = _state.value.copy(picoLaunching = true, botError = "")
			val app = getApplication<Application>()

			try {
				// 已经在跑
				if (PrivacyHelper.isPicoRunning()) {
					_state.value = _state.value.copy(picoLaunching = false, picoRunning = true)
					refresh()
					return@launch
				}


				onPicoLaunching?.invoke()
				// 注意：用 SetupActivity（exported=true）而不是 HomeActivity（exported=false）。
				// Android 8+ 从后台 startActivity 一个未导出的 Activity 会被静默丢弃。
				if (!PrivacyHelper.launchPicoSilently(app, com.crsyyds.suzubot.ui.setup.SetupActivity::class.java)) {
					_state.value = _state.value.copy(
						picoLaunching = false,
						botError = "无法拉起协议端，请确认已安装 PicoOnebot"
					)
					return@launch
				}

				// 等端口出现，最多 25 秒
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
					// 不再调 refresh()（因为用户现在在协议端界面），
					// 等 onResume 回来时会自动刷新。
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

	fun startBot() {
		val app = getApplication<Application>()
		if (chainManager.usable().isEmpty()) {
			_state.value = _state.value.copy(botError = "还没配置模型，请到「运维 → 模型配置」填 API Key")
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

	fun currentApiKey(): String = chainManager.usable().firstOrNull()?.apiKey.orEmpty()
	fun currentBaseUrl(): String = chainManager.usable().firstOrNull()?.baseUrl.orEmpty()
	fun currentModel(): String = chainManager.usable().firstOrNull()?.model.orEmpty()

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
					// 实时反映协议端存活（被杀掉时卡片会自动出现）
					picoRunning = running
				)
				delay(3000)
			}
		}
	}
}
