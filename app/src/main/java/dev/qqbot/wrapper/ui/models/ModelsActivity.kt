package dev.qqbot.wrapper.ui.models

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.qqbot.wrapper.backend.ModelEndpoint
import dev.qqbot.wrapper.ui.components.ButtonShape
import dev.qqbot.wrapper.ui.components.PclCard
import dev.qqbot.wrapper.ui.components.PclChip
import dev.qqbot.wrapper.ui.components.PclDivider
import dev.qqbot.wrapper.ui.components.SectionTitle
import dev.qqbot.wrapper.ui.theme.PclErr
import dev.qqbot.wrapper.ui.theme.PclOk
import dev.qqbot.wrapper.ui.theme.PclTextDim
import dev.qqbot.wrapper.ui.theme.PclWarn
import dev.qqbot.wrapper.ui.theme.QQBotTheme

/**
 * 模型配置页：主模型 + 回退模型。
 *
 * 三种预设：DeepSeek / 智谱 GLM / 自定义 OpenAI 兼容端点。
 * 链的顺序即优先级 —— 第 1 个是主模型，后面是回退。
 */
class ModelsActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			QQBotTheme {
				val vm: ModelsViewModel = viewModel()
				val state by vm.state.collectAsState()
				LaunchedEffect(Unit) { vm.load() }
				Surface(modifier = Modifier.fillMaxSize()) {
					ModelsScreen(
						state = state,
						onAdd = vm::addPreset,
						onRemove = vm::remove,
						onMove = vm::move,
						onEdit = vm::setEditing,
						onUpdate = vm::update,
						onTest = vm::test,
						onSave = vm::save,
						onBack = { finish() }
					)
				}
			}
		}
	}
}

@Composable
private fun ModelsScreen(
	state: ModelsUiState,
	onAdd: (String) -> Unit,
	onRemove: (Int) -> Unit,
	onMove: (Int, Int) -> Unit,
	onEdit: (Int) -> Unit,
	onUpdate: (Int, ModelEndpoint) -> Unit,
	onTest: (Int) -> Unit,
	onSave: () -> Unit,
	onBack: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.windowInsetsPadding(WindowInsets.systemBars)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Box(
				modifier = Modifier
					.size(36.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { onBack() },
				contentAlignment = Alignment.Center
			) { Text("←", color = MaterialTheme.colorScheme.primary) }
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text("模型配置", style = MaterialTheme.typography.titleLarge)
				Text(
					"主模型失败会自动切到下一个",
					style = MaterialTheme.typography.bodySmall,
					color = PclTextDim
				)
			}
			AnimatedVisibility(state.savedFlash, enter = fadeIn(), exit = fadeOut()) {
				PclChip("已保存", PclOk)
			}
		}

		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp)
		) {
			// 已有模型链
			state.chain.forEachIndexed { index, ep ->
				ModelCard(
					index = index,
					endpoint = ep,
					isPrimary = index == 0,
					editing = state.editingIndex == index,
					testing = state.testing && state.editingIndex == index,
					testResult = if (state.editingIndex == index) state.testResult else "",
					canMoveUp = index > 0,
					canMoveDown = index < state.chain.lastIndex,
					onEdit = { onEdit(index) },
					onUpdate = { onUpdate(index, it) },
					onTest = { onTest(index) },
					onRemove = { onRemove(index) },
					onMoveUp = { onMove(index, index - 1) },
					onMoveDown = { onMove(index, index + 1) }
				)
				Spacer(Modifier.height(10.dp))
			}

			// 添加预设
			PclCard {
				SectionTitle("添加模型")
				Spacer(Modifier.height(4.dp))
				Text(
					"第 1 个是主模型，后面的是回退。超时或出错会自动切换。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Spacer(Modifier.height(10.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					PresetChip("DeepSeek", "deepseek", state, onAdd)
					PresetChip("智谱 GLM", "zhipu", state, onAdd)
					PresetChip("自定义", "custom", state, onAdd)
				}
			}

			Spacer(Modifier.height(12.dp))

			PclCard {
				SectionTitle("说明")
				Spacer(Modifier.height(6.dp))
				listOf(
					"回退触发条件：请求超时、HTTP 报错、模型返回空内容",
					"DeepSeek 端点：api.deepseek.com/v1",
					"智谱端点：open.bigmodel.cn/api/paas/v4",
					"自定义端点需填完整的 /v1 路径",
					"每个模型可单独设置超时（默认 45 秒）"
				).forEach { t ->
					Row(modifier = Modifier.padding(vertical = 4.dp)) {
						Box(
							modifier = Modifier
								.padding(top = 7.dp, end = 8.dp)
								.size(5.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.primary)
						)
						Text(
							t,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}

			Spacer(Modifier.height(20.dp))
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Button(
				onClick = onSave,
				modifier = Modifier
					.fillMaxWidth()
					.height(48.dp),
				shape = ButtonShape,
				enabled = state.anyConfigured
			) { Text("保存并生效", fontWeight = FontWeight.SemiBold) }
		}
	}
}

@Composable
private fun PresetChip(
	label: String,
	kind: String,
	state: ModelsUiState,
	onAdd: (String) -> Unit
) {
	val exists = state.chain.any { it.kind == kind && kind != "custom" }
	FilterChip(
		selected = false,
		onClick = { if (!exists) onAdd(kind) },
		label = { Text(label, style = MaterialTheme.typography.bodySmall) },
		enabled = !exists,
		shape = RoundedCornerShape(8.dp),
		colors = FilterChipDefaults.filterChipColors(
			selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
			disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
		)
	)
}

@Composable
private fun ModelCard(
	index: Int,
	endpoint: ModelEndpoint,
	isPrimary: Boolean,
	editing: Boolean,
	testing: Boolean,
	testResult: String,
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	onEdit: () -> Unit,
	onUpdate: (ModelEndpoint) -> Unit,
	onTest: () -> Unit,
	onRemove: () -> Unit,
	onMoveUp: () -> Unit,
	onMoveDown: () -> Unit
) {
	var apiKey by remember(endpoint.id) { mutableStateOf(endpoint.apiKey) }
	var baseUrl by remember(endpoint.id) { mutableStateOf(endpoint.baseUrl) }
	var model by remember(endpoint.id) { mutableStateOf(endpoint.model) }
	var timeout by remember(endpoint.id) { mutableStateOf((endpoint.timeoutMs / 1000).toString()) }

	val accent = when {
		!endpoint.configured -> PclWarn
		isPrimary -> MaterialTheme.colorScheme.primary
		else -> PclTextDim
	}

	PclCard(accent = accent) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				if (isPrimary) "主模型" else "回退 ${index}",
				style = MaterialTheme.typography.labelLarge,
				color = if (isPrimary) MaterialTheme.colorScheme.primary else PclTextDim
			)
			Spacer(Modifier.width(8.dp))
			PclChip(endpoint.name)
			Spacer(Modifier.weight(1f))
			if (endpoint.configured) {
				PclChip("已配置", PclOk)
			}
		}

		Spacer(Modifier.height(6.dp))
		Text(
			if (endpoint.model.isNotEmpty()) endpoint.model else "未选择模型",
			style = MaterialTheme.typography.titleMedium
		)
		Text(
			endpoint.baseUrl.ifEmpty { "未设置地址" },
			style = MaterialTheme.typography.bodySmall,
			color = PclTextDim
		)

		Spacer(Modifier.height(10.dp))

		AnimatedVisibility(
			visible = editing,
			enter = androidx.compose.animation.expandVertically() + fadeIn(),
			exit = androidx.compose.animation.shrinkVertically() + fadeOut()
		) {
			Column {
				PclField(apiKey, { apiKey = it; onUpdate(endpoint.copy(apiKey = it)) }, "API Key", "sk-…", true)
				Spacer(Modifier.height(8.dp))
				PclField(baseUrl, { baseUrl = it; onUpdate(endpoint.copy(baseUrl = it)) }, "接口地址", "https://…/v1")
				Spacer(Modifier.height(8.dp))
				PclField(model, { model = it; onUpdate(endpoint.copy(model = it)) }, "模型名", "deepseek-chat")
				Spacer(Modifier.height(8.dp))
				PclField(timeout, {
					timeout = it.filter { c -> c.isDigit() }
					val sec = timeout.toIntOrNull() ?: 45
					onUpdate(endpoint.copy(timeoutMs = sec.coerceIn(5, 300) * 1000))
				}, "超时（秒）", "45", false, KeyboardType.Number)

				Spacer(Modifier.height(10.dp))

				// 能力开关
				CapabilitySwitch(
					label = "支持图片（多模态）",
					hint = "开启后，群友发的图会下载并传给模型",
					checked = endpoint.visionEnabled,
					onChange = { onUpdate(endpoint.copy(visionEnabled = it)) }
				)
				CapabilitySwitch(
					label = "深度思考",
					hint = "开启后模型会先推理再回答（更慢、更费 token）",
					checked = endpoint.thinkingEnabled,
					onChange = { onUpdate(endpoint.copy(thinkingEnabled = it)) }
				)

				if (testResult.isNotEmpty()) {
					Spacer(Modifier.height(8.dp))
					Text(
						testResult,
						style = MaterialTheme.typography.bodySmall,
						color = if (testResult.startsWith("✅")) PclOk else PclErr
					)
				}

				Spacer(Modifier.height(10.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedButton(
						onClick = onTest,
						modifier = Modifier
							.weight(1f)
							.height(40.dp),
						shape = RoundedCornerShape(9.dp),
						enabled = !testing
					) { Text(if (testing) "测试中…" else "测试", style = MaterialTheme.typography.bodySmall) }
					OutlinedButton(
						onClick = onRemove,
						modifier = Modifier
							.weight(1f)
							.height(40.dp),
						shape = RoundedCornerShape(9.dp)
					) { Text("删除", style = MaterialTheme.typography.bodySmall, color = PclErr) }
				}
			}
		}

		if (!editing) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedButton(
					onClick = onEdit,
					modifier = Modifier
						.weight(1f)
						.height(38.dp),
					shape = RoundedCornerShape(9.dp)
				) { Text("编辑", style = MaterialTheme.typography.bodySmall) }
				if (canMoveUp) {
					OutlinedButton(
						onClick = onMoveUp,
						modifier = Modifier.height(38.dp),
						shape = RoundedCornerShape(9.dp)
					) { Text("↑", style = MaterialTheme.typography.bodySmall) }
				}
				if (canMoveDown) {
					OutlinedButton(
						onClick = onMoveDown,
						modifier = Modifier.height(38.dp),
						shape = RoundedCornerShape(9.dp)
					) { Text("↓", style = MaterialTheme.typography.bodySmall) }
				}
			}
		}
	}
}

@Composable
private fun CapabilitySwitch(
	label: String,
	hint: String,
	checked: Boolean,
	onChange: (Boolean) -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.bodyMedium)
			Text(
				hint,
				style = MaterialTheme.typography.bodySmall,
				color = PclTextDim
			)
		}
		androidx.compose.material3.Switch(
			checked = checked,
			onCheckedChange = onChange,
			colors = androidx.compose.material3.SwitchDefaults.colors(
				checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
				checkedTrackColor = MaterialTheme.colorScheme.primary
			)
		)
	}
}

@Composable
private fun PclField(
	value: String,
	onChange: (String) -> Unit,
	label: String,
	placeholder: String,
	isPassword: Boolean = false,
	keyboard: KeyboardType = KeyboardType.Text
) {
	OutlinedTextField(
		value = value,
		onValueChange = onChange,
		label = { Text(label, style = MaterialTheme.typography.bodySmall) },
		placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
		singleLine = true,
		shape = RoundedCornerShape(10.dp),
		visualTransformation = if (isPassword) PasswordVisualTransformation()
		else androidx.compose.ui.text.input.VisualTransformation.None,
		keyboardOptions = KeyboardOptions(keyboardType = keyboard),
		modifier = Modifier.fillMaxWidth(),
		colors = OutlinedTextFieldDefaults.colors(
			focusedBorderColor = MaterialTheme.colorScheme.primary,
			unfocusedBorderColor = MaterialTheme.colorScheme.outline
		),
		textStyle = MaterialTheme.typography.bodyMedium
	)
}
