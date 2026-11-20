package com.crsyyds.suzubot.ui.setup

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crsyyds.suzubot.runtime.PicoRuntimeMode
import com.crsyyds.suzubot.setup.SetupFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val nativeSteps = SetupFlow.Step.values().filter { it != SetupFlow.Step.DONE }
private val externalSteps = nativeSteps - setOf(SetupFlow.Step.INSTALL, SetupFlow.Step.CONFIGURE_AI)

internal fun setupStepTitle(step: SetupFlow.Step, externalRuntime: Boolean): String {
	if (!externalRuntime) return step.title
	return when (step) {
		SetupFlow.Step.START_PROTOCOL -> "连接 PicoOnebot"
		SetupFlow.Step.INIT_PASSWORD -> "校验控制台密码"
		else -> step.title
	}
}

/**
 * 引导页 UI 状态。
 */
data class SetupUiState(
	val step: SetupFlow.Step = SetupFlow.Step.DEVICE_CHECK,
	val phase: Phase = Phase.Idle,
	val message: String = "",
	val hint: String = "",
	val actionLabel: String? = null,
	val progress: Float = 0f,
	val progressText: String = "",
	val qrBitmap: Bitmap? = null,
	val qrCountdown: Int = 0,
	val qrScanned: Boolean = false,

	/**
	 * 设备是否只能走外部运行时（纯 64 位，见 [PicoRuntimeMode.EXTERNAL_ARM64]）。
	 *
	 * 它决定两件事：外部路径的步骤清单 / 总数 / 进度按实际会走的步骤算，
	 * 以及「初始化控制台」失败时给出手填密码入口。控制台密码本身**不进**这个对象。
	 */
	val externalRuntime: Boolean = false
) {
	enum class Phase { Running, WaitingUser, Error, Idle, Finished }

	val canRetry: Boolean get() = phase == Phase.Error
	val isBusy: Boolean get() = phase == Phase.Running

	val pathSteps: List<SetupFlow.Step> get() = if (externalRuntime) externalSteps else nativeSteps
	val pathTotal: Int get() = if (externalRuntime) pathSteps.size else SetupFlow.Step.total

	val displayStep: SetupFlow.Step
		get() = if (externalRuntime) when (step) {
			SetupFlow.Step.INSTALL -> SetupFlow.Step.START_PROTOCOL
			SetupFlow.Step.CONFIGURE_AI -> SetupFlow.Step.CONFIGURE_BACKEND
			else -> step
		} else step

	val stepNumber: Int
		get() = if (displayStep == SetupFlow.Step.DONE) pathTotal else pathSteps.indexOf(displayStep) + 1

	val stepTitle: String get() = setupStepTitle(displayStep, externalRuntime)
	val stepProgress: Float
		get() = when {
			phase == Phase.Finished || displayStep == SetupFlow.Step.DONE -> 1f
			else -> (stepNumber - 1).toFloat() / pathSteps.size
		}
}

class SetupViewModel(app: Application) : AndroidViewModel(app) {

	private val flow = SetupFlow(app)
	private val _state = MutableStateFlow(SetupUiState())
	val state: StateFlow<SetupUiState> = _state.asStateFlow()

	private var pendingAction: (suspend () -> Unit)? = null

	/** 当前是否在等用户挑一个本地 APK */
	var awaitingFile: Boolean = false
		private set

	/** UI 侧注入的 SAF 拉起器 */
	var filePickerLauncher: ((String) -> Unit)? = null

	/**
	 * 登录成功回调。
	 * Activity 用它把应用拉回前台（因为扫码时 PicoOnebot 的界面在前面）。
	 */
	var onLoginSuccess: ((String) -> Unit)? = null

	init {
		_state.value = _state.value.copy(
			step = flow.currentStep,
			externalRuntime = PicoRuntimeMode.current() == PicoRuntimeMode.EXTERNAL_ARM64
		)
	}

	fun isSetupCompleted(): Boolean = flow.isCompleted()

	/**
	 * 跑当前步骤。
	 *
	 * @param externalConsolePassword 外部运行时（纯 64 位设备）下用户手填的
	 *   PicoOnebot 控制台密码。它只作为**本次调用**的参数直接转交给
	 *   [SetupFlow.runCurrent]，由状态机负责校验与持久化；
	 *   ViewModel 自己不保存、不写 prefs、不塞 Bundle、也不打日志。
	 */
	fun run(externalConsolePassword: String? = null) {
		if (_state.value.isBusy) return
		viewModelScope.launch {
			_state.value = _state.value.copy(
				phase = SetupUiState.Phase.Running,
				message = _state.value.stepTitle + "…",
				hint = "",
				actionLabel = null,
				progress = 0f,
				progressText = ""
			)

			val outcome = try {
				flow.runCurrent(
					// 外部运行时手填的控制台密码：原样交给状态机去校验并持久化，
					// UI 层不落盘、不进 Bundle、不打日志。
					externalConsolePassword = externalConsolePassword,

					onProgress = { done, total ->
						val p = if (total > 0) done.toFloat() / total.toFloat() else 0f
						_state.value = _state.value.copy(
							progress = p.coerceIn(0f, 1f),
							progressText = "${mb(done)} / ${mb(total)}"
						)
					},

					onLoggedIn = { id ->
						_state.value = _state.value.copy(
							message = "登录成功（$id），正在返回…",
							qrBitmap = null
						)
						onLoginSuccess?.invoke(id)
					}
				)
			} catch (t: Throwable) {
				SetupFlow.Outcome.Failed(
					reason = "意外错误：${t.message ?: t::class.java.simpleName}",
					hint = "请点「重试」；若反复失败，把这条信息反馈到项目 Issues"
				)
			}

			apply(outcome)
		}
	}

	private fun apply(outcome: SetupFlow.Outcome) {
		when (outcome) {
			is SetupFlow.Outcome.Done -> {
				val next = outcome.next
				if (next == null || next == SetupFlow.Step.DONE) {
					_state.value = _state.value.copy(
						phase = SetupUiState.Phase.Finished,
						message = "全部完成，可以开始使用了",
						qrBitmap = null
					)
				} else {
					_state.value = _state.value.copy(
						phase = SetupUiState.Phase.Idle,
						step = next,
						message = "",
						qrBitmap = null,
						progress = 0f,
						progressText = ""
					)
					run()
				}
			}

			is SetupFlow.Outcome.NeedUserAction -> {
				pendingAction = outcome.action
				_state.value = _state.value.copy(
					phase = SetupUiState.Phase.WaitingUser,
					message = outcome.message,
					actionLabel = outcome.actionLabel,
					progress = 0f
				)
			}

			is SetupFlow.Outcome.NeedsUserFile -> {
				awaitingFile = true
				_state.value = _state.value.copy(
					phase = SetupUiState.Phase.WaitingUser,
					message = "自动获取不可用：${outcome.reason}",
					hint = "请点下方按钮选择 PicoOnebot 的 APK。",
					actionLabel = "选择本地 APK",
					progress = 0f
				)
				pendingAction = null
			}

			is SetupFlow.Outcome.Progress -> {
				_state.value = _state.value.copy(
					phase = SetupUiState.Phase.Running,
					message = outcome.message
				)
			}

			is SetupFlow.Outcome.Failed -> {
				_state.value = _state.value.copy(
					phase = SetupUiState.Phase.Error,
					message = outcome.reason,
					hint = outcome.hint,
					actionLabel = null,
					progress = 0f
				)
			}
		}
	}

	fun onAction() {
		if (awaitingFile) {
			filePickerLauncher?.invoke("application/vnd.android.package-archive")
			return
		}

		val act = pendingAction
		if (act == null) {
			run()
			return
		}
		viewModelScope.launch {
			try {
				act()
			} catch (t: Throwable) {
				_state.value = _state.value.copy(
					phase = SetupUiState.Phase.Error,
					message = "无法执行该操作：${t.message}",
					hint = "请手动到系统里完成这一步，然后点「重试」"
				)
				return@launch
			}
			_state.value = _state.value.copy(
				message = "完成上述操作后，点下方「继续」",
				actionLabel = "继续"
			)
			pendingAction = null
		}
	}

	fun resumeAfterUserAction() {
		pendingAction = null
		run()
	}

	fun onLocalApkPicked(localPath: String) {
		awaitingFile = false
		flow.userApkPath = localPath
		run()
	}

	fun onLocalApkCancelled() {
		awaitingFile = false
		_state.value = _state.value.copy(
			phase = SetupUiState.Phase.Error,
			message = "未选择安装包",
			hint = "可以点「重试」自动下载，或重新选择本地文件。",
			actionLabel = null
		)
	}

	private fun mb(b: Long): String = when {
		b <= 0 -> "0 MB"
		b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
		else -> "%d MB".format(b / 1024 / 1024)
	}
}
