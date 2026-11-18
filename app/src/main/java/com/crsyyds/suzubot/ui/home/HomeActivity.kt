package com.crsyyds.suzubot.ui.home

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crsyyds.suzubot.ui.components.ButtonShape
import com.crsyyds.suzubot.ui.components.EmptyHint
import com.crsyyds.suzubot.ui.components.ExpandableSection
import com.crsyyds.suzubot.ui.components.KvRow
import com.crsyyds.suzubot.ui.components.PclChip
import com.crsyyds.suzubot.ui.components.PclCard
import com.crsyyds.suzubot.ui.components.PclDivider
import com.crsyyds.suzubot.ui.components.SectionTitle
import com.crsyyds.suzubot.ui.components.StatusDot
import com.crsyyds.suzubot.ui.theme.PclErr
import com.crsyyds.suzubot.ui.theme.PclOk
import com.crsyyds.suzubot.ui.theme.PclTextDim
import com.crsyyds.suzubot.ui.theme.PclWarn
import com.crsyyds.suzubot.ui.theme.QQBotTheme

/**
 * 主页：PCL 风格控制台。
 *
 * 结构（从上到下）：
 *  1. 协议端未运行 → 显眼的「启动协议端」卡片（主人反馈的核心问题）
 *  2. 运行状态（QQ 号、内存、消息数）
 *  3. 机器人后端（启动/停止）
 *  4. 运维（模型/persona/控制台/省电/重部署）
 *  5. 提示
 */
class HomeActivity : ComponentActivity() {

	private var refreshHook: (() -> Unit)? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		runCatching { com.crsyyds.suzubot.service.BotService.start(this) }

		setContent {
			QQBotTheme {
				val vm: HomeViewModel = viewModel()
				val state by vm.state.collectAsState()

				LaunchedEffect(Unit) {
					refreshHook = { vm.refresh() }
					// 启动协议端时开启守卫窗口（保证切回本应用）
					vm.onPicoLaunching = { armPicoLaunchGuard() }
					vm.refresh()
				}

				Surface(modifier = Modifier.fillMaxSize()) {
					HomeScreen(
						state = state,
						onRefresh = vm::refresh,
						onLaunchPico = vm::launchPico,
						onStartBot = vm::startBot,
						onStopBot = vm::stopBot,
						onOpenConsole = { openWebUi() },
						onBatterySettings = { openBatterySettings() },
						onOpenSetup = {
							startActivity(
								Intent(this, com.crsyyds.suzubot.ui.setup.SetupActivity::class.java)
							)
						},
						onOpenPersona = {
							startActivity(
								Intent(this, com.crsyyds.suzubot.ui.persona.PersonaActivity::class.java)
							)
						},
						onOpenModels = {
							startActivity(
								Intent(this, com.crsyyds.suzubot.ui.models.ModelsActivity::class.java)
							)
						},
						onOpenPicoUi = { openPicoUi() },
						onStarPico = { openGithub("pico") },
						onStarSuzu = { openGithub("suzu") },
						onResetProtocol = vm::resetProtocol
					)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()
		refreshHook?.invoke()
	}

	override fun onPause() {
		super.onPause()
		// 若正在“启动协议端”的窗口内被切走，稍后自动拉回来。
		// 这是对 PrivacyHelper 多轮切回的补充保险。
		if (picoLaunchGuardUntil > System.currentTimeMillis()) {
			mainHandler.postDelayed({
				if (!isFinishing && picoLaunchGuardUntil > System.currentTimeMillis()) {
					val i = Intent(this, com.crsyyds.suzubot.ui.setup.SetupActivity::class.java).apply {
						addFlags(
							Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
								Intent.FLAG_ACTIVITY_SINGLE_TOP
						)
					}
					runCatching { startActivity(i) }
				}
			}, 2_500)
		}
	}

	/** 启动协议端后的守卫窗口（毫秒时间戳）。 */
	private var picoLaunchGuardUntil: Long = 0L

	/** 由 ViewModel 在启动协议端时调用，开启 15 秒守卫。 */
	fun armPicoLaunchGuard() {
		picoLaunchGuardUntil = System.currentTimeMillis() + 15_000
	}

	private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

	private fun openWebUi() {
		val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://127.0.0.1:6099"))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		if (runCatching { startActivity(i) }.isFailure) {
			android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
		}
	}

	/**
	 * 打开 PicoOnebot 自己的 App 界面（不是网页控制台）。
	 *
	 * 用显式 ComponentName 拉起它的主 Activity；如果它没在运行，
	 * 这次启动会顺带把进程带起来。
	 */
	private fun openPicoUi() {
		val launched = com.crsyyds.suzubot.setup.PrivacyHelper.startPicoActivity(this)
		if (!launched) {
			android.widget.Toast.makeText(
				this, "无法打开协议端界面，确认已安装 PicoOnebot", android.widget.Toast.LENGTH_SHORT
			).show()
		}
	}

	/** 给两个开源项目点星星（跳浏览器 GitHub）。 */
	private fun openGithub(pkg: String) {
		val url = when (pkg) {
			"pico" -> "https://github.com/Exmeaning/PicoOnebot"
			else -> "https://github.com/CRSYYDS/SuzuBot"
		}
		val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		if (runCatching { startActivity(i) }.isFailure) {
			android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
		}
	}

	private fun openBatterySettings() {
		val candidates = listOf(
			Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
				.setData(android.net.Uri.parse("package:$packageName")),
			Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
			Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
				.setData(android.net.Uri.parse("package:$packageName"))
		)
		for (i in candidates) {
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if (runCatching { startActivity(i) }.isSuccess) return
		}
	}
}

// ── 页面 ──────────────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(
	state: HomeUiState,
	onRefresh: () -> Unit,
	onLaunchPico: () -> Unit,
	onStartBot: () -> Unit,
	onStopBot: () -> Unit,
	onOpenConsole: () -> Unit,
	onBatterySettings: () -> Unit,
	onOpenSetup: () -> Unit,
	onOpenPersona: () -> Unit,
	onOpenModels: () -> Unit,
	onOpenPicoUi: () -> Unit,
	onStarPico: () -> Unit,
	onStarSuzu: () -> Unit,
	onResetProtocol: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.windowInsetsPadding(WindowInsets.systemBars)
	) {
		TopBar(state, onRefresh)
		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp)
		) {
			Spacer(Modifier.height(4.dp))

			// 协议端没跑 → 最上面放启动卡
			if (!state.picoRunning) {
				PicoLaunchCard(state, onLaunchPico)
				Spacer(Modifier.height(12.dp))
			}

			StatusCard(state, onResetProtocol, onOpenPicoUi)
			Spacer(Modifier.height(12.dp))
			BotCard(state, onStartBot, onStopBot, onLaunchPico)
			Spacer(Modifier.height(12.dp))
			ActionsCard(
				onOpenConsole = onOpenConsole,
				onBatterySettings = onBatterySettings,
				onOpenSetup = onOpenSetup,
				onOpenPersona = onOpenPersona,
				onOpenModels = onOpenModels,
				onOpenPicoUi = onOpenPicoUi,
				highlightModels = !state.apiKeyConfigured,
				highlightPersona = !state.personaConfigured
			)
			Spacer(Modifier.height(12.dp))
			StarButtons(onStarPico, onStarSuzu)
			Spacer(Modifier.height(24.dp))
		}
	}
}

@Composable
private fun TopBar(state: HomeUiState, onRefresh: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Box(
			modifier = Modifier
				.size(38.dp)
				.clip(RoundedCornerShape(11.dp))
				.background(MaterialTheme.colorScheme.primary),
			contentAlignment = Alignment.Center
		) {
			Text("Q", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
		}
		Spacer(Modifier.width(11.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				"SuzuBot",
				style = MaterialTheme.typography.titleLarge,
				color = MaterialTheme.colorScheme.onBackground
			)
			Text(
				when {
					!state.picoRunning -> "协议端未运行"
					state.botConnected -> "运行中 · 已处理 ${state.botHandled} 条"
					else -> "协议端在线 · 后端未启动"
				},
				style = MaterialTheme.typography.bodySmall,
				color = when {
					!state.picoRunning -> PclErr
					state.botConnected -> PclOk
					else -> PclTextDim
				}
			)
		}
	}
}

// ── 协议端启动卡（关键：重进 App 后靠它拉起） ──────────────────────────────

@Composable
private fun PicoLaunchCard(state: HomeUiState, onLaunch: () -> Unit) {
	PclCard(accent = PclErr) {
		SectionTitle("协议端未运行") {
			StatusDot(PclErr, "离线")
		}
		Spacer(Modifier.height(6.dp))
		Text(
			"后台被清理时 QQ 协议端也会被杀掉。点下方按钮重新拉起。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)

		// 重要提示：启动后会跳到协议端界面
		Spacer(Modifier.height(10.dp))
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(8.dp))
				.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
				.padding(10.dp)
		) {
			Text(
				"⚠️",
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.padding(end = 8.dp)
			)
			Column {
				Text(
					"启动后会跳到 QQ 协议端界面",
					style = MaterialTheme.typography.bodySmall,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onPrimaryContainer
				)
				Text(
					"请按返回键回到本应用，并且不要从多任务里删掉它",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		if (state.botError.isNotEmpty()) {
			Spacer(Modifier.height(6.dp))
			Text(state.botError, style = MaterialTheme.typography.bodySmall, color = PclErr)
		}
		Spacer(Modifier.height(12.dp))
		Button(
			onClick = onLaunch,
			enabled = !state.picoLaunching,
			modifier = Modifier
				.fillMaxWidth()
				.height(48.dp),
			shape = ButtonShape
		) {
			if (state.picoLaunching) {
				CircularProgressIndicator(
					modifier = Modifier.size(16.dp),
					strokeWidth = 2.dp,
					color = MaterialTheme.colorScheme.onPrimary
				)
				Spacer(Modifier.width(8.dp))
				Text("正在启动…")
			} else {
				Text("启动协议端", fontWeight = FontWeight.SemiBold)
			}
		}
	}
}

// ── 运行状态 ──────────────────────────────────────────────────────────────────

@Composable
private fun StatusCard(
	state: HomeUiState,
	onResetProtocol: () -> Unit,
	onOpenPicoUi: () -> Unit
) {
	val online = state.status?.online == true
	val accent = when {
		!state.picoRunning -> PclErr
		online -> PclOk
		else -> PclWarn
	}

	PclCard(accent = accent) {
		SectionTitle("运行状态") {
			val (dotColor, label) = when {
				!state.picoRunning -> PclErr to "离线"
				online -> PclOk to "在线"
				else -> PclWarn to "未登录"
			}
			StatusDot(dotColor, label)
		}

		Spacer(Modifier.height(10.dp))

		when {
			state.status != null -> {
				val s = state.status
				KvRow("QQ 账号", s.selfId.ifEmpty { "—" })
				KvRow("昵称", s.nickname.ifEmpty { "—" })
				PclDivider(Modifier.padding(vertical = 8.dp))
				KvRow("内存占用", "${s.memoryMb} MB", MaterialTheme.colorScheme.primary)
				KvRow("消息", "收 ${s.msgRecv} / 发 ${s.msgSent}")
				KvRow("运行时长", formatUptime(s.uptimeSeconds))
				PclDivider(Modifier.padding(vertical = 8.dp))
				KvRow("协议", s.protocol)
				KvRow("宿主", "手表 QQ ${s.qqVersion}")
				KvRow("协议端", s.version)
			}

			state.error != null && state.picoRunning -> {
				Column {
					Text(
						"协议端在跑，但控制台密码不匹配。",
						style = MaterialTheme.typography.bodySmall,
						color = PclWarn
					)
					Spacer(Modifier.height(6.dp))
					Text(
						"这通常是 App 重装后，本地记录的密码丢了，" +
							"但协议端里的密码还在。" +
							"点下方按钮重置：清空协议端数据并恢复默认密码。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Spacer(Modifier.height(10.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Button(
							onClick = onResetProtocol,
							modifier = Modifier
								.weight(1f)
								.height(40.dp),
							shape = RoundedCornerShape(9.dp)
						) {
							Text("重置协议端数据", style = MaterialTheme.typography.bodySmall)
						}
						OutlinedButton(
							onClick = onOpenPicoUi,
							modifier = Modifier
								.weight(1f)
								.height(40.dp),
							shape = RoundedCornerShape(9.dp)
						) {
							Text("打开协议端", style = MaterialTheme.typography.bodySmall)
						}
					}
					Spacer(Modifier.height(4.dp))
					Text(
						"注：重置会让 QQ 退出登录，需要重新扫码。建议先试右边的「打开协议端」看看能不能在界面里改密码。",
						style = MaterialTheme.typography.bodySmall,
						color = PclTextDim
					)
				}
			}

			else -> EmptyHint("读取中…")
		}
	}
}

// ── 机器人后端 ────────────────────────────────────────────────────────────────

@Composable
private fun BotCard(
	state: HomeUiState,
	onStartBot: () -> Unit,
	onStopBot: () -> Unit,
	onLaunchPico: () -> Unit
) {
	val accent = when {
		state.botConnected -> PclOk
		state.modelsConfigured -> PclWarn
		else -> PclErr
	}

	PclCard(accent = accent) {
		SectionTitle("Suzu后端") {
			val (c, t) = when {
				state.botConnected -> PclOk to "运行中"
				state.modelsConfigured -> PclWarn to "待启动"
				else -> PclErr to "未配置"
			}
			StatusDot(c, t)
		}
		Spacer(Modifier.height(4.dp))
		Text(
			when {
				!state.modelsConfigured -> "还没有配置模型，请到「运维 → 模型配置」填 API Key"
				!state.picoRunning -> "协议端未运行，需要先启动它"
				state.botConnected -> "已连接 OneBot · 处理 ${state.botHandled} 条"
				else -> "已配置，点下方启动"
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)

		if (state.botError.isNotEmpty()) {
			Spacer(Modifier.height(8.dp))
			Text(state.botError, style = MaterialTheme.typography.bodySmall, color = PclErr)
		}

		Spacer(Modifier.height(14.dp))

		if (state.botConnected) {
			OutlinedButton(
				onClick = onStopBot,
				modifier = Modifier
					.fillMaxWidth()
					.height(44.dp),
				shape = ButtonShape
			) { Text("停止机器人") }
		} else {
			Button(
				onClick = {
					// 协议端没起时，点这个直接拉起协议端
					if (!state.picoRunning) onLaunchPico() else onStartBot()
				},
				modifier = Modifier
					.fillMaxWidth()
					.height(44.dp),
				shape = ButtonShape
			) {
				Text(
					if (!state.picoRunning) "启动协议端" else "启动机器人",
					fontWeight = FontWeight.SemiBold
				)
			}
		}
	}
}

// ── 运维 ──────────────────────────────────────────────────────────────────────

@Composable
private fun ActionsCard(
	onOpenConsole: () -> Unit,
	onBatterySettings: () -> Unit,
	onOpenSetup: () -> Unit,
	onOpenPersona: () -> Unit,
	onOpenModels: () -> Unit,
	onOpenPicoUi: () -> Unit,
	/** 首次配置时高亮引导 */
	highlightModels: Boolean,
	highlightPersona: Boolean
) {
	PclCard {
		SectionTitle("运维")
		Spacer(Modifier.height(6.dp))
		Column {
			// 高亮：模型配置
			GuideActionRow(
				title = "模型配置",
				subtitle = "主模型 + 自动回退（DeepSeek / 智谱 / 自定义）",
				onClick = onOpenModels,
				highlight = highlightModels,
				highlightText = "先配置这个"
			)
			PclDivider(Modifier.padding(vertical = 2.dp), alpha = 0.6f)
			// 高亮：人设
			GuideActionRow(
				title = "编辑机器人人设",
				subtitle = "性格、语气、禁止项（soul）",
				onClick = onOpenPersona,
				highlight = highlightPersona,
				highlightText = "建议完善"
			)
			PclDivider(Modifier.padding(vertical = 2.dp), alpha = 0.6f)
			ActionRow("打开 PicoOnebot 界面", "直接跳到协议端 App", onOpenPicoUi)
			PclDivider(Modifier.padding(vertical = 2.dp), alpha = 0.6f)
			ActionRow("打开 PicoOnebot 网页控制台", "在浏览器里管理", onOpenConsole)
			PclDivider(Modifier.padding(vertical = 2.dp), alpha = 0.6f)
			ActionRow("放宽省电限制", "防止安卓系统杀后台", onBatterySettings)
			PclDivider(Modifier.padding(vertical = 2.dp), alpha = 0.6f)
			ActionRow("重新运行部署向导", "换号 / 换 API / 修复环境", onOpenSetup)
			Spacer(Modifier.height(6.dp))
		}
	}
}

/**
 * 带引导高亮的操作行。
 *
 * 高亮时：左侧竖条 + 背景色 + 小标签，三重强调。
 */
@Composable
private fun GuideActionRow(
	title: String,
	subtitle: String,
	onClick: () -> Unit,
	highlight: Boolean,
	highlightText: String
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(8.dp))
			.then(
				if (highlight) {
					Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
				} else Modifier
			)
			.padding(vertical = 10.dp, horizontal = if (highlight) 8.dp else 0.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		if (highlight) {
			Box(
				modifier = Modifier
					.width(3.dp)
					.height(28.dp)
					.clip(RoundedCornerShape(2.dp))
					.background(MaterialTheme.colorScheme.primary)
			)
			Spacer(Modifier.width(8.dp))
		}
		Column(modifier = Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal
				)
				if (highlight) {
					Spacer(Modifier.width(6.dp))
					PclChip(highlightText, MaterialTheme.colorScheme.primary)
				}
			}
			Text(
				subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		OutlinedButton(
			onClick = onClick,
			shape = RoundedCornerShape(8.dp),
			modifier = Modifier.height(34.dp),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
		) {
			Text("打开", style = MaterialTheme.typography.bodySmall)
		}
	}
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(8.dp))
			.padding(vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(title, style = MaterialTheme.typography.bodyMedium)
			Text(
				subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		OutlinedButton(
			onClick = onClick,
			shape = RoundedCornerShape(8.dp),
			modifier = Modifier.height(34.dp),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
		) {
			Text("打开", style = MaterialTheme.typography.bodySmall)
		}
	}
}

// ── 点星星 ────────────────────────────────────────────────────────────────────

/**
 * 给两个上游开源项目点 star 的入口。
 *
 * 为什么要做这个：PicoOnebot 是无偿开源的项目，而 SuzuBot 也在开源，
 * 用一句「点个免费的小星星」提醒用户回馈一下，比埋在文档里有效得多。
 */
@Composable
private fun StarButtons(onStarPico: () -> Unit, onStarSuzu: () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		StarButton("给「PICOPICO」点一个免费的小星星⭐", onStarPico)
		StarButton("给「SuzuBot」点一个免费的小星星⭐", onStarSuzu)
	}
}

@Composable
private fun StarButton(text: String, onClick: () -> Unit) {
	OutlinedButton(
		onClick = onClick,
		modifier = Modifier
			.fillMaxWidth()
			.height(44.dp),
		shape = RoundedCornerShape(10.dp),
		contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
	) {
		Text(
			text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.primary
		)
	}
}

// ── 工具 ──────────────────────────────────────────────────────────────────────

private fun formatUptime(sec: Long): String {
	if (sec <= 0) return "—"
	val d = sec / 86400
	val h = (sec % 86400) / 3600
	val m = (sec % 3600) / 60
	return when {
		d > 0 -> "${d}天${h}小时"
		h > 0 -> "${h}小时${m}分"
		else -> "${m}分"
	}
}
