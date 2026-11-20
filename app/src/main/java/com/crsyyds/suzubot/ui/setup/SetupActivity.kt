package com.crsyyds.suzubot.ui.setup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crsyyds.suzubot.setup.SetupFlow
import com.crsyyds.suzubot.ui.home.HomeActivity
import com.crsyyds.suzubot.ui.components.CardShape
import com.crsyyds.suzubot.ui.components.ButtonShape
import com.crsyyds.suzubot.ui.components.PclCard
import com.crsyyds.suzubot.ui.components.PclDivider
import com.crsyyds.suzubot.ui.components.StatusDot
import com.crsyyds.suzubot.ui.components.healthColor
import com.crsyyds.suzubot.ui.theme.PclErr
import com.crsyyds.suzubot.ui.theme.PclOk
import com.crsyyds.suzubot.ui.theme.PclTextDim
import com.crsyyds.suzubot.ui.theme.PclWarn
import com.crsyyds.suzubot.ui.theme.QQBotTheme

/**
 * 首次引导页。
 *
 * 设计取向：一次只做一件事，当前步骤占据页面主体，进度、错误、二维码都在同一张卡里，
 * 避免用户在多个页面之间来回找「我卡在哪一步了」。
 */
class SetupActivity : ComponentActivity() {

	private var vm: SetupViewModel? = null

	/** SAF：挑一个 APK 文件。 */
	private val pickApk = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri == null) {
			vm?.onLocalApkCancelled()
			return@registerForActivityResult
		}
		// 复制到私有目录：SAF 的 uri 只在本次授权内有效，安装器读不到
		runCatching {
			val dir = File(filesDir, "runtime/picked").apply { mkdirs() }
			val out = File(dir, "picked-${System.currentTimeMillis()}.apk")
			contentResolver.openInputStream(uri)?.use { input ->
				FileOutputStream(out).use { fos -> input.copyTo(fos) }
			} ?: error("无法读取所选文件")
			out.absolutePath
		}.onSuccess { path ->
			vm?.onLocalApkPicked(path)
		}.onFailure { e ->
			vm?.onLocalApkCancelled()
			android.util.Log.e(TAG, "copy picked apk failed", e)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		// 注意：super.onCreate 必须在任何分支之前调用，
		// 否则提前 return 会触发 SuperNotCalledException 直接崩溃。
		super.onCreate(savedInstanceState)

		// 已完成配置的话，直接去主页（避免从协议端切回时停在引导页）
		runCatching {
			val flow = com.crsyyds.suzubot.setup.SetupFlow(this)
			if (flow.isCompleted()) {
				startActivity(
					android.content.Intent(this, com.crsyyds.suzubot.ui.home.HomeActivity::class.java)
				)
				finish()
				return
			}
		}

		enableEdgeToEdge()

		setContent {
			QQBotTheme {
				val viewModel: SetupViewModel = viewModel()
				vm = viewModel
				val state by viewModel.state.collectAsState()

				LaunchedEffect(viewModel) {
					viewModel.filePickerLauncher = { mime ->
						runCatching { pickApk.launch(arrayOf(mime, "*/*")) }
					}
					// 扫码成功后，把本应用拉回前台（不要停在 PicoOnebot 界面）
					viewModel.onLoginSuccess = { selfId ->
						runOnUiThread { bringSelfToFront() }
						android.util.Log.i("SetupActivity", "登录成功: $selfId，已拉回前台")
					}
				}

				LaunchedEffect(Unit) {
					if (viewModel.isSetupCompleted()) {
						startActivity(
							android.content.Intent(this@SetupActivity, HomeActivity::class.java)
						)
						finish()
						return@LaunchedEffect
					}
					viewModel.run()
				}

				Surface(modifier = Modifier.fillMaxSize()) {
					SetupScreen(
						state = state,
						onAction = viewModel::onAction,
						onRetry = viewModel::resumeAfterUserAction,
						onSubmitExternalPassword = { pwd ->
							// 只走内存：交给 ViewModel 转给 SetupFlow 校验并持久化，
							// UI 侧不写 prefs、不写日志、不进 Bundle。
							viewModel.run(externalConsolePassword = pwd)
						},
						onFinish = {
							com.crsyyds.suzubot.service.BotService.start(this@SetupActivity)
							startActivity(
								android.content.Intent(this@SetupActivity, HomeActivity::class.java)
							)
							finish()
						}
					)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()
		// 从安装器 / PicoOnebot 返回时重跑当前步骤，让状态机自己判断条件是否已满足
		// 每次回到前台都尝试保活
		runCatching { com.crsyyds.suzubot.service.BotService.start(this) }
		vm?.let { v ->
			val s = v.state.value
			if (s.phase == SetupUiState.Phase.WaitingUser || s.phase == SetupUiState.Phase.Error) {
				v.resumeAfterUserAction()
			}
		}
	}

	/**
	 * 把本应用拉回前台。
	 *
	 * 场景：扫码时 PicoOnebot 的界面在前面，
	 * 登录成功后应自动切回我们的引导页。
	 */
	private fun bringSelfToFront() {
		runCatching {
			val intent = android.content.Intent(this, SetupActivity::class.java).apply {
				addFlags(
					android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
						android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
				)
			}
			startActivity(intent)
		}.onFailure {
			android.util.Log.w("SetupActivity", "bringSelfToFront failed", it)
		}
	}

	companion object {
		private const val TAG = "SetupActivity"
	}
}

@Composable
private fun SetupScreen(
	state: SetupUiState,
	onAction: () -> Unit,
	onRetry: () -> Unit,
	onSubmitExternalPassword: (String) -> Unit,
	onFinish: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.windowInsetsPadding(WindowInsets.systemBars)
			.padding(horizontal = 20.dp)
	) {
		Spacer(Modifier.height(16.dp))
		Header(state)
		Spacer(Modifier.height(20.dp))

		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
		) {
			StepCard(state, onSubmitExternalPassword)
			Spacer(Modifier.height(16.dp))
			StepList(state)
		}

		BottomBar(state, onAction, onRetry, onFinish)
		Spacer(Modifier.height(16.dp))
	}
}

@Composable
private fun Header(state: SetupUiState) {
	Column {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(
				modifier = Modifier
					.size(36.dp)
					.clip(RoundedCornerShape(10.dp))
					.background(MaterialTheme.colorScheme.primary),
				contentAlignment = Alignment.Center
			) {
				Text("Q", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
			}
			Spacer(Modifier.width(12.dp))
			Column {
				Text("SuzuBot", style = MaterialTheme.typography.titleLarge)
				Text(
					"部署向导 · ${state.stepNumber}/${state.pathTotal}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		Spacer(Modifier.height(14.dp))
		LinearProgressIndicator(
			progress = { state.stepProgress },
			modifier = Modifier
				.fillMaxWidth()
				.height(6.dp)
				.clip(RoundedCornerShape(3.dp))
		)
	}
}

@Composable
private fun StepCard(
	state: SetupUiState,
	onSubmitExternalPassword: (String) -> Unit
) {
	PclCard(
		accent = when (state.phase) {
			SetupUiState.Phase.Error -> PclErr
			SetupUiState.Phase.Finished -> PclOk
			SetupUiState.Phase.Running -> MaterialTheme.colorScheme.primary
			else -> PclWarn
		}
	) {
		Column(modifier = Modifier.padding(20.dp)) {
			Text(
				state.stepTitle,
				style = MaterialTheme.typography.titleLarge,
				color = MaterialTheme.colorScheme.onSurface
			)
			Spacer(Modifier.height(10.dp))

			when (state.phase) {
				SetupUiState.Phase.Running -> RunningBody(state)
				SetupUiState.Phase.WaitingUser -> WaitingBody(state)
				SetupUiState.Phase.Error -> ErrorBody(state, onSubmitExternalPassword)
				SetupUiState.Phase.Finished -> FinishedBody()
				SetupUiState.Phase.Idle -> IdleBody()
			}

			// 注意：这里**不再显示登录二维码**。
			// 首次登录必须在 PicoOnebot 自己的界面上点「同意隐私政策」，
			// 二维码就在那个界面里，我们这边再显示一遍是重复的。
			// 扫码阶段的状态由 SetupFlow.scanLogin 轮询 /api/status 检测。
		}
	}
}

@Composable
private fun RunningBody(state: SetupUiState) {
	Text(state.message.ifEmpty { "处理中…" }, style = MaterialTheme.typography.bodyMedium)
	if (state.progress > 0f) {
		Spacer(Modifier.height(14.dp))
		LinearProgressIndicator(
			progress = { state.progress },
			modifier = Modifier
				.fillMaxWidth()
				.height(8.dp)
				.clip(RoundedCornerShape(4.dp))
		)
		Spacer(Modifier.height(6.dp))
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
			Text(state.progressText, style = MaterialTheme.typography.bodySmall)
		}
	} else {
		Spacer(Modifier.height(12.dp))
		CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
	}
}

@Composable
private fun WaitingBody(state: SetupUiState) {
	Text(state.message, style = MaterialTheme.typography.bodyMedium)
	Spacer(Modifier.height(10.dp))
	Surface(
		shape = RoundedCornerShape(10.dp),
		color = MaterialTheme.colorScheme.surfaceVariant,
		modifier = Modifier.fillMaxWidth()
	) {
		Text(
			"完成外部操作后回到这里，会自动继续。",
			modifier = Modifier.padding(12.dp),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

@Composable
private fun ErrorBody(
	state: SetupUiState,
	onSubmitExternalPassword: (String) -> Unit
) {
	Text(
		state.message,
		style = MaterialTheme.typography.bodyMedium,
		color = PclErr
	)
	if (state.hint.isNotEmpty()) {
		Spacer(Modifier.height(12.dp))
		Card(
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
			),
			shape = RoundedCornerShape(10.dp)
		) {
			Column(modifier = Modifier.padding(12.dp)) {
				Text(
					"怎么解决",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.error
				)
				Spacer(Modifier.height(6.dp))
				Text(
					state.hint,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}

	// 外部运行时（纯 64 位设备）的特殊兜底：
	// 「初始化控制台」失败往往是因为虚拟机里的 PicoOnebot 早就设过自己的控制台密码，
	// 而本机既没有本地记录也猜不到默认值。这时只多给一个手填入口，
	// 密码本身交给 SetupFlow 校验并持久化，UI 不落盘、不进 Bundle、不写日志。
	if (state.externalRuntime && state.step == SetupFlow.Step.INIT_PASSWORD) {
		Spacer(Modifier.height(12.dp))
		ExternalConsolePasswordField(onSubmitExternalPassword)
	}
}

/**
 * 外部运行时的控制台密码输入。
 *
 * 密码只保存在 Compose 的**非 saveable** 内存状态里（[remember]，不是 rememberSaveable），
 * 所以不会随进程重建写进 Bundle；提交后由 ViewModel 直接转交 SetupFlow。
 */
@Composable
private fun ExternalConsolePasswordField(onSubmit: (String) -> Unit) {
	var pwd by remember { mutableStateOf("") }
	val canSubmit = pwd.isNotEmpty()

	Column(modifier = Modifier.fillMaxWidth()) {
		Text(
			"外接运行时：填写 PicoOnebot 控制台密码",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurface
		)
		Spacer(Modifier.height(4.dp))
		Text(
			"如果在这个虚拟机的 PicoOnebot 里已经设过控制台密码，在这里填一次即可，" +
				"校验通过后会自动记住，不用再填。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Spacer(Modifier.height(8.dp))
		OutlinedTextField(
			value = pwd,
			onValueChange = { pwd = it },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			shape = RoundedCornerShape(10.dp),
			label = { Text("控制台密码", style = MaterialTheme.typography.bodySmall) },
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
			colors = OutlinedTextFieldDefaults.colors(
				focusedBorderColor = MaterialTheme.colorScheme.primary,
				unfocusedBorderColor = MaterialTheme.colorScheme.outline
			),
			textStyle = MaterialTheme.typography.bodyMedium
		)
		Spacer(Modifier.height(8.dp))
		OutlinedButton(
			onClick = {
				// 原样提交，UI 不做 trim / 不补默认值：
				// 归一化与候选表都在 SetupFlow 里，这里悄悄改字符只会让校验更难查。
				onSubmit(pwd)
			},
			enabled = canSubmit,
			modifier = Modifier
				.fillMaxWidth()
				.height(44.dp),
			shape = RoundedCornerShape(12.dp)
		) { Text("提交密码并重试") }
	}
}

@Composable
private fun IdleBody() {
	Text("准备中…", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FinishedBody() {
	Text(
		"环境已就绪。之后可以在主页查看运行状态、改了配置或换 API Key。",
		style = MaterialTheme.typography.bodyMedium
	)
}

/** 步骤清单：已完成打勾，当前高亮，未开始灰。 */
@Composable
private fun StepList(state: SetupUiState) {
	val steps = state.pathSteps
	val current = state.displayStep
	val externalFinished = state.externalRuntime && state.phase == SetupUiState.Phase.Finished
	PclCard {
		Column {
			steps.forEachIndexed { idx, s ->
				val done = externalFinished || s.order < current.order
				val active = !done && s == current
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 9.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					Box(
						modifier = Modifier
							.size(22.dp)
							.clip(CircleShape)
							.background(
								when {
									done -> MaterialTheme.colorScheme.primary
									active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
									else -> MaterialTheme.colorScheme.surfaceVariant
								}
							),
						contentAlignment = Alignment.Center
					) {
						Text(
							text = if (done) "✓" else "${idx + 1}",
							style = MaterialTheme.typography.bodySmall,
							color = when {
								done -> MaterialTheme.colorScheme.onPrimary
								active -> MaterialTheme.colorScheme.primary
								else -> MaterialTheme.colorScheme.onSurfaceVariant
							},
							fontWeight = FontWeight.SemiBold
						)
					}
					Spacer(Modifier.width(12.dp))
					Text(
						setupStepTitle(s, state.externalRuntime),
						style = MaterialTheme.typography.bodyMedium,
						color = when {
							active -> MaterialTheme.colorScheme.onSurface
							done -> MaterialTheme.colorScheme.onSurfaceVariant
							else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
						},
						fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
					)
				}
				if (idx < steps.size - 1) {
					HorizontalDivider(
						modifier = Modifier.padding(start = 50.dp),
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
					)
				}
			}
		}
	}

}

@Composable
private fun BottomBar(
	state: SetupUiState,
	onAction: () -> Unit,
	onRetry: () -> Unit,
	onFinish: () -> Unit
) {
	when (state.phase) {
		SetupUiState.Phase.Error -> {
			Button(
				onClick = onRetry,
				modifier = Modifier
					.fillMaxWidth()
					.height(52.dp),
				shape = RoundedCornerShape(14.dp)
			) { Text("重试") }
		}

		SetupUiState.Phase.WaitingUser -> {
			Button(
				onClick = onAction,
				modifier = Modifier
					.fillMaxWidth()
					.height(52.dp),
				shape = RoundedCornerShape(14.dp)
			) { Text(state.actionLabel ?: "继续") }
		}

		SetupUiState.Phase.Finished -> {
			Button(
				onClick = onFinish,
				modifier = Modifier
					.fillMaxWidth()
					.height(52.dp),
				shape = RoundedCornerShape(14.dp)
			) { Text("进入主页") }
		}

		SetupUiState.Phase.Running -> {
			Button(
				onClick = {},
				enabled = false,
				modifier = Modifier
					.fillMaxWidth()
					.height(52.dp),
				shape = RoundedCornerShape(14.dp),
				colors = ButtonDefaults.buttonColors(
					disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
				)
			) {
				CircularProgressIndicator(
					modifier = Modifier.size(18.dp),
					strokeWidth = 2.dp,
					color = MaterialTheme.colorScheme.onPrimary
				)
				Spacer(Modifier.width(10.dp))
				Text("处理中")
			}
		}

		SetupUiState.Phase.Idle -> {
			OutlinedButton(
				onClick = onAction,
				modifier = Modifier
					.fillMaxWidth()
					.height(52.dp),
				shape = RoundedCornerShape(14.dp)
			) { Text("继续") }
		}
	}
}
