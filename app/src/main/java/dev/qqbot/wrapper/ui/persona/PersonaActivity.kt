package dev.qqbot.wrapper.ui.persona

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.qqbot.wrapper.persona.PersonaStore
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
 * 人设编辑（支持多份）。
 *
 * 布局：
 *  顶栏：标题 + 已保存标记
 *  人设列表：每行 名字 / 字数 / 激活标记，右侧操作（复制、重命名、删除）
 *  编辑区：Markdown 正文
 *  优化区：✨ 用主模型自动优化 / 未绑定模型提示
 *  底部：保存 / 恢复内置
 */
class PersonaActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		setContent {
			QQBotTheme {
				val vm: PersonaViewModel = viewModel()
				val state by vm.state.collectAsState()

				LaunchedEffect(Unit) { vm.load() }

				Surface(modifier = Modifier.fillMaxSize()) {
					PersonaScreen(
						state = state,
						vm = vm,
						onBack = { finish() }
					)
				}
			}
		}
	}
}

@Composable
private fun PersonaScreen(
	state: PersonaUiState,
	vm: PersonaViewModel,
	onBack: () -> Unit
) {
	// 新建对话框
	if (state.showCreateDialog) {
		CreatePersonaDialog(
			hasCurrent = state.text.isNotBlank(),
			onConfirm = { name, copy -> vm.createPersona(name, copy) },
			onDismiss = vm::closeCreateDialog
		)
	}

	// 重命名对话框
	if (state.showRenameDialog) {
		RenameDialog(
			initial = vm.initialRenameText,
			onConfirm = vm::confirmRename,
			onDismiss = vm::closeRenameDialog
		)
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.windowInsetsPadding(WindowInsets.systemBars)
	) {
		// 顶栏
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
				Text("机器人人设", style = MaterialTheme.typography.titleLarge)
				Text(
					"有趣的灵魂万里挑一",
					style = MaterialTheme.typography.bodySmall,
					color = PclTextDim
				)
			}
			AnimatedVisibility(state.savedFlash, enter = fadeIn(), exit = fadeOut()) {
				PclChip("已保存", PclOk)
			}
			if (state.dirty && !state.savedFlash) {
				PclChip("未保存", PclWarn)
			}
		}

		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp)
		) {
			// ── 人设列表 ──────────────────────────────────────────────────────
			PclCard {
				SectionTitle("人设列表") {
					PclChip("${state.personas.size}", MaterialTheme.colorScheme.primary)
				}
				Spacer(Modifier.height(8.dp))

				if (state.loading) {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.height(80.dp),
						contentAlignment = Alignment.Center
					) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
				} else {
					state.personas.forEachIndexed { idx, p ->
						val active = p.id == state.editingId
						PersonaRow(
							persona = p,
							active = active,
							canDelete = state.canDelete,
							onSelect = { vm.select(p.id) },
							onDuplicate = { vm.duplicatePersona(p.id) },
							onRename = { vm.openRenameDialog(p.id) },
							onDelete = { vm.deletePersona(p.id) }
						)
						if (idx < state.personas.lastIndex) {
							PclDivider(Modifier.padding(vertical = 2.dp), alpha = 0.5f)
						}
					}
				}

				Spacer(Modifier.height(10.dp))
				OutlinedButton(
					onClick = vm::openCreateDialog,
					modifier = Modifier
						.fillMaxWidth()
						.height(40.dp),
					shape = RoundedCornerShape(9.dp)
				) {
					Text("＋ 新增人设", style = MaterialTheme.typography.bodySmall)
				}
			}

			Spacer(Modifier.height(12.dp))

			// ── 编辑区 ────────────────────────────────────────────────────────
			PclCard(accent = MaterialTheme.colorScheme.primary) {
				SectionTitle(state.editingPersona?.name ?: "人设文本") {
					if (state.dirty) {
						PclChip("有改动", PclWarn)
					} else {
						PclChip("已保存", PclOk)
					}
				}
				Spacer(Modifier.height(6.dp))
				Text(
					"支持 Markdown。保存后立即生效（会让后端重连一次）。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Spacer(Modifier.height(12.dp))

				if (state.loading) {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.height(200.dp),
						contentAlignment = Alignment.Center
					) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) }
				} else {
					OutlinedTextField(
						value = state.text,
						onValueChange = vm::update,
						modifier = Modifier
							.fillMaxWidth()
							.height(420.dp),
						shape = RoundedCornerShape(10.dp),
						textStyle = MaterialTheme.typography.bodySmall,
						placeholder = {
							Text(
								"从这里开始写你的人设…\n" +
									"可以只写简单一句话 下面有优化提示词按钮可以点 可以自动优化喵~\n\n" +
									"例如：\n# 角色：kskbl\n## 性格\n- 喜欢降维打击\n" +
									"## 说话\n- 一次 1-3 句，句尾带「喵」\n## 禁止\n- 不自称 AI",
								style = MaterialTheme.typography.bodySmall
							)
						},
						colors = OutlinedTextFieldDefaults.colors(
							focusedBorderColor = MaterialTheme.colorScheme.primary,
							unfocusedBorderColor = MaterialTheme.colorScheme.outline
						)
					)
				}

				Spacer(Modifier.height(8.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween
				) {
					Text(
						"${state.charCount} 字符",
						style = MaterialTheme.typography.bodySmall,
						color = PclTextDim
					)
					Text(
						"建议 500-1500 字",
						style = MaterialTheme.typography.bodySmall,
						color = PclTextDim
					)
				}

				Spacer(Modifier.height(12.dp))

				// ── 自动优化 ──────────────────────────────────────────────────
				if (state.hasModel) {
					OutlinedButton(
						onClick = vm::optimize,
						enabled = state.canOptimize,
						modifier = Modifier
							.fillMaxWidth()
							.height(42.dp),
						shape = RoundedCornerShape(9.dp)
					) {
						if (state.optimizing) {
							CircularProgressIndicator(
								modifier = Modifier.size(14.dp),
								strokeWidth = 2.dp,
								color = MaterialTheme.colorScheme.primary
							)
							Spacer(Modifier.width(8.dp))
							Text("正在优化…", style = MaterialTheme.typography.bodySmall)
						} else {
							Text("✨ 用主模型自动优化", style = MaterialTheme.typography.bodySmall)
						}
					}
				} else {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(8.dp))
							.background(PclWarn.copy(alpha = 0.12f))
							.padding(10.dp)
					) {
						Text("⚠️", modifier = Modifier.padding(end = 8.dp))
						Text(
							"还没绑定主模型，无法自动优化。请先到「运维 → 模型配置」填 API Key。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}

				if (state.optimizeError.isNotEmpty()) {
					Spacer(Modifier.height(8.dp))
					Text(state.optimizeError, style = MaterialTheme.typography.bodySmall, color = PclErr)
				}

				// 优化建议
				if (state.optimizedText.isNotEmpty()) {
					Spacer(Modifier.height(12.dp))
					PclDivider()
					Spacer(Modifier.height(10.dp))
					Text(
						"优化建议（${state.optimizedText.length} 字）",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.primary
					)
					Spacer(Modifier.height(8.dp))
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = 260.dp)
							.clip(RoundedCornerShape(9.dp))
							.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
							.padding(10.dp)
					) {
						Text(
							state.optimizedText,
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.verticalScroll(rememberScrollState())
						)
					}
					Spacer(Modifier.height(10.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Button(
							onClick = vm::acceptOptimization,
							modifier = Modifier
								.weight(1f)
								.height(40.dp),
							shape = RoundedCornerShape(9.dp)
						) { Text("采纳", style = MaterialTheme.typography.bodySmall) }
						OutlinedButton(
							onClick = vm::discardOptimization,
							modifier = Modifier
								.weight(1f)
								.height(40.dp),
							shape = RoundedCornerShape(9.dp)
						) { Text("丢弃", style = MaterialTheme.typography.bodySmall) }
					}
				}
			}

			Spacer(Modifier.height(12.dp))

			// ── 写作要点 ──────────────────────────────────────────────────────
			PclCard {
				SectionTitle("写作要点")
				Spacer(Modifier.height(8.dp))
				listOf(
					"写清性格：话多还是话少、爱不爱接梗、毒不毒舌",
					"写清说话方式：一次几句、用不用口语词",
					"写清禁止项：不自称 AI、不提模型名、不写小作文",
					"避免写太长，太多规则会让它变得僵硬"
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

		// 底部操作
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			OutlinedButton(
				onClick = vm::resetToBuiltIn,
				modifier = Modifier
					.weight(1f)
					.height(48.dp),
				shape = ButtonShape,
				enabled = !state.loading
			) { Text("恢复内置") }
			Button(
				onClick = vm::save,
				modifier = Modifier
					.weight(1f)
					.height(48.dp),
				shape = ButtonShape,
				enabled = !state.loading && state.editingId.isNotEmpty()
			) {
				Text(
					if (state.dirty) "保存改动" else "已保存",
					fontWeight = FontWeight.SemiBold
				)
			}
		}
	}
}

// ── 单行 ──────────────────────────────────────────────────────────────────────

@Composable
private fun PersonaRow(
	persona: PersonaStore.Persona,
	active: Boolean,
	canDelete: Boolean,
	onSelect: () -> Unit,
	onDuplicate: () -> Unit,
	onRename: () -> Unit,
	onDelete: () -> Unit
) {
	var menuOpen by remember { mutableStateOf(false) }

	Column {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(8.dp))
				.clickable { onSelect() }
				.padding(vertical = 10.dp, horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Box(
				modifier = Modifier
					.size(7.dp)
					.clip(CircleShape)
					.background(
						if (active) MaterialTheme.colorScheme.primary
						else MaterialTheme.colorScheme.outline
					)
			)
			Spacer(Modifier.width(10.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					persona.name,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
				)
				if (active) {
					Text(
						"使用中",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary
					)
				}
			}
			TextButton(onClick = { menuOpen = !menuOpen }) {
				Text("⋯", style = MaterialTheme.typography.titleMedium)
			}
		}

		AnimatedVisibility(menuOpen, enter = fadeIn(), exit = fadeOut()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 17.dp, bottom = 8.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp)
			) {
				SmallAction("复制") { menuOpen = false; onDuplicate() }
				SmallAction("重命名") { menuOpen = false; onRename() }
				if (canDelete) {
					SmallAction("删除", danger = true) { menuOpen = false; onDelete() }
				}
			}
		}
	}
}

@Composable
private fun SmallAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
	OutlinedButton(
		onClick = onClick,
		shape = RoundedCornerShape(7.dp),
		modifier = Modifier.height(30.dp),
		contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
	) {
		Text(
			text,
			style = MaterialTheme.typography.bodySmall,
			color = if (danger) PclErr else MaterialTheme.colorScheme.primary
		)
	}
}

// ── 对话框 ────────────────────────────────────────────────────────────────────

@Composable
private fun CreatePersonaDialog(
	hasCurrent: Boolean,
	onConfirm: (name: String, copyCurrent: Boolean) -> Unit,
	onDismiss: () -> Unit
) {
	var name by remember { mutableStateOf("") }
	var copyCurrent by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("新增人设") },
		text = {
			Column {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text("名字", style = MaterialTheme.typography.bodySmall) },
					placeholder = { Text("例如：kskbl", style = MaterialTheme.typography.bodySmall) },
					singleLine = true,
					shape = RoundedCornerShape(9.dp),
					modifier = Modifier.fillMaxWidth(),
					colors = OutlinedTextFieldDefaults.colors(
						focusedBorderColor = MaterialTheme.colorScheme.primary
					)
				)
				if (hasCurrent) {
					Spacer(Modifier.height(10.dp))
					Row(
						modifier = Modifier
							.clip(RoundedCornerShape(8.dp))
							.clickable { copyCurrent = !copyCurrent }
							.padding(vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Box(
							modifier = Modifier
								.size(18.dp)
								.clip(RoundedCornerShape(5.dp))
								.background(
									if (copyCurrent) MaterialTheme.colorScheme.primary
									else MaterialTheme.colorScheme.surfaceVariant
								),
							contentAlignment = Alignment.Center
						) {
							if (copyCurrent) {
								Text("✓", style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onPrimary)
							}
						}
						Spacer(Modifier.width(8.dp))
						Text("基于当前人设复制", style = MaterialTheme.typography.bodySmall)
					}
				}
				Text(
					if (copyCurrent) "会复制当前编辑的内容作为初值"
					else "新人设从空白开始，自己写",
					style = MaterialTheme.typography.bodySmall,
					color = PclTextDim
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(name, copyCurrent) }) { Text("创建") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		}
	)
}

@Composable
private fun RenameDialog(
	initial: String,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit
) {
	var name by remember { mutableStateOf(initial) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("重命名人设") },
		text = {
			OutlinedTextField(
				value = name,
				onValueChange = { name = it },
				label = { Text("名字", style = MaterialTheme.typography.bodySmall) },
				singleLine = true,
				shape = RoundedCornerShape(9.dp),
				modifier = Modifier.fillMaxWidth(),
				colors = OutlinedTextFieldDefaults.colors(
					focusedBorderColor = MaterialTheme.colorScheme.primary
				)
			)
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(name) }) { Text("确定") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		}
	)
}
