package com.crsyyds.suzubot.ui.persona

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crsyyds.suzubot.backend.BotRuntime
import com.crsyyds.suzubot.backend.FallbackChatClient
import com.crsyyds.suzubot.backend.ModelChainManager
import com.crsyyds.suzubot.persona.PersonaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 人设编辑（支持多份人设）。
 *
 * 页面结构：
 *  - 顶部：人设列表（一份激活，可切换/新增/删除/复制）
 *  - 中间：当前人设的正文编辑器
 *  - 底部：保存 / 自动优化
 */
data class PersonaUiState(
	val loading: Boolean = true,
	/** 全部人设（不含正文） */
	val personas: List<PersonaStore.Persona> = emptyList(),
	/** 当前编辑的 id */
	val editingId: String = "",
	/** 当前编辑的正文 */
	val text: String = "",
	val charCount: Int = 0,
	val savedFlash: Boolean = false,
	val optimizing: Boolean = false,
	val optimizeError: String = "",
	val optimizedText: String = "",
	val hasModel: Boolean = false,
	/** 新建人设对话框 */
	val showCreateDialog: Boolean = false,
	/** 重命名对话框 */
	val showRenameDialog: Boolean = false,
	val renameTarget: String = "",
	/** 当前编辑内容与已保存的不一致 */
	val dirty: Boolean = false,
	/** 待切换的目标人设 id（非空时弹确认） */
	val pendingSwitchId: String = ""
) {
	val canOptimize: Boolean get() = hasModel && text.isNotBlank() && !optimizing
	val editingPersona: PersonaStore.Persona?
		get() = personas.firstOrNull { it.id == editingId }
	val canDelete: Boolean get() = personas.size > 1
}

class PersonaViewModel(app: Application) : AndroidViewModel(app) {

	private val _state = MutableStateFlow(PersonaUiState())
	val state: StateFlow<PersonaUiState> = _state.asStateFlow()

	private val store = PersonaStore(app)
	private val chainManager = ModelChainManager(app)

	/** 当前编辑对象“已保存”的内容，用来判断是否有未保存修改。 */
	private var savedBaseline: String = ""

	fun load() {
		viewModelScope.launch {
			withContext(Dispatchers.IO) { store.ensureInitialized() }
			val list = store.list()
			val active = store.activeId() ?: list.firstOrNull()?.id.orEmpty()
			val content = if (active.isNotEmpty()) store.read(active) else ""
			savedBaseline = content
			_state.value = PersonaUiState(
				loading = false,
				personas = list,
				editingId = active,
				text = content,
				charCount = content.length,
				hasModel = chainManager.usable().isNotEmpty(),
				dirty = false
			)
		}
	}

	/**
	 * 切换编辑对象。
	 *
	 * **切换前会自动保存当前修改**（只要有改动），
	 * 避免用户改了一大段没保存就切走就白写了。
	 */
	/**
	 * 切换编辑对象。
	 *
	 * **切换前强制保存当前编辑内容**（不看 dirty 标志，
	 * 避免任何状态同步问题导致改动丢失）。
	 */
	fun select(id: String) {
		val s = _state.value
		if (id == s.editingId) return

		viewModelScope.launch {
			// 不管 dirty 如何，只要有正文就写回去（幂等操作，射安全）
			if (s.editingId.isNotEmpty() && s.text.isNotEmpty()) {
				withContext(Dispatchers.IO) { store.write(s.editingId, s.text) }
			}

			val content = withContext(Dispatchers.IO) { store.read(id) }
			withContext(Dispatchers.IO) { store.setActive(id) }
			savedBaseline = content

			_state.value = _state.value.copy(
				personas = store.list(),
				editingId = id,
				text = content,
				charCount = content.length,
				optimizedText = "",
				optimizeError = "",
				dirty = false,
				pendingSwitchId = ""
			)
		}
	}

	fun update(text: String) {
		_state.value = _state.value.copy(
			text = text,
			charCount = text.length,
			savedFlash = false,
			optimizedText = "",
			optimizeError = "",
			dirty = text != savedBaseline
		)
	}

	/** 保存当前编辑的人设并设为激活。 */
	fun save() {
		viewModelScope.launch {
			val s = _state.value
			if (s.editingId.isEmpty()) return@launch
			withContext(Dispatchers.IO) {
				store.write(s.editingId, s.text)
				store.setActive(s.editingId)
			}
			// 让运行中的引擎重载人设
			BotRuntime.stop()
			savedBaseline = s.text
			_state.value = _state.value.copy(
				savedFlash = true,
				optimizedText = "",
				personas = store.list(),
				dirty = false
			)
			delay(2000)
			_state.value = _state.value.copy(savedFlash = false)
		}
	}

	// ── 多份人设管理 ──────────────────────────────────────────────────────────

	fun openCreateDialog() {
		_state.value = _state.value.copy(showCreateDialog = true)
	}

	fun closeCreateDialog() {
		_state.value = _state.value.copy(showCreateDialog = false)
	}

	/** 新建一份人设（用内置猫娘作初值，或从当前复制）。 */
	fun createPersona(name: String, copyCurrent: Boolean = false) {
		viewModelScope.launch {
			val finalName = name.trim().ifEmpty { "新人设" }
			val from = if (copyCurrent) _state.value.text else null
			val id = withContext(Dispatchers.IO) { store.create(finalName, from) }
			val content = withContext(Dispatchers.IO) { store.read(id) }
			savedBaseline = content
			_state.value = _state.value.copy(
				personas = store.list(),
				editingId = id,
				text = content,
				charCount = content.length,
				showCreateDialog = false,
				optimizedText = "",
				optimizeError = "",
				dirty = false
			)
		}
	}

	fun deletePersona(id: String) {
		viewModelScope.launch {
			val ok = withContext(Dispatchers.IO) { store.delete(id) }
			if (!ok) return@launch
			val list = store.list()
			val active = store.activeId() ?: list.firstOrNull()?.id.orEmpty()
			val content = withContext(Dispatchers.IO) { store.read(active) }
			BotRuntime.stop()
			_state.value = _state.value.copy(
				personas = list,
				editingId = active,
				text = content,
				charCount = content.length,
				optimizedText = "",
				optimizeError = ""
			)
		}
	}

	fun duplicatePersona(id: String) {
		viewModelScope.launch {
			val src = store.list().firstOrNull { it.id == id }
			val newName = "${src?.name ?: "人设"} 副本"
			val newId = withContext(Dispatchers.IO) { store.duplicate(id, newName) }
				?: return@launch
			val content = withContext(Dispatchers.IO) { store.read(newId) }
			_state.value = _state.value.copy(
				personas = store.list(),
				editingId = newId,
				text = content,
				charCount = content.length
			)
		}
	}

	fun openRenameDialog(id: String) {
		val p = _state.value.personas.firstOrNull { it.id == id } ?: return
		initialRenameText = p.name
		_state.value = _state.value.copy(showRenameDialog = true, renameTarget = id)
	}

	/** 重命名对话框的初值（UI 用） */
	var initialRenameText: String = ""
		private set

	/** 用户确认丢弃修改并切换。 */
	fun confirmDiscardAndSwitch() {
		val target = _state.value.pendingSwitchId
		if (target.isEmpty()) return
		viewModelScope.launch {
			val content = withContext(Dispatchers.IO) { store.read(target) }
			withContext(Dispatchers.IO) { store.setActive(target) }
			savedBaseline = content
			_state.value = _state.value.copy(
				personas = store.list(),
				editingId = target,
				text = content,
				charCount = content.length,
				optimizedText = "",
				optimizeError = "",
				dirty = false,
				pendingSwitchId = ""
			)
		}
	}

	fun cancelPendingSwitch() {
		_state.value = _state.value.copy(pendingSwitchId = "")
	}

	fun closeRenameDialog() {
		_state.value = _state.value.copy(showRenameDialog = false, renameTarget = "")
	}

	fun confirmRename(newName: String) {
		val id = _state.value.renameTarget
		if (id.isEmpty()) return
		viewModelScope.launch {
			withContext(Dispatchers.IO) { store.rename(id, newName.trim().ifEmpty { "未命名" }) }
			_state.value = _state.value.copy(
				personas = store.list(),
				showRenameDialog = false,
				renameTarget = ""
			)
		}
	}

	/** 恢复当前人设为内置猫娘。 */
	fun resetToBuiltIn() {
		viewModelScope.launch {
			val builtIn = store.readBuiltInPersona()
			_state.value = _state.value.copy(
				text = builtIn,
				charCount = builtIn.length,
				optimizedText = "",
				optimizeError = "",
				dirty = builtIn != savedBaseline
			)
		}
	}

	// ── 自动优化（保留原有功能） ───────────────────────────────────────────────

	fun optimize() {
		val current = _state.value.text.trim()
		if (current.isEmpty()) {
			_state.value = _state.value.copy(optimizeError = "人设内容不能为空")
			return
		}

		val usable = chainManager.usable()
		if (usable.isEmpty()) {
			_state.value = _state.value.copy(
				optimizeError = "还没绑定主模型。请先到「运维 → 模型配置」填 API Key"
			)
			return
		}

		viewModelScope.launch {
			_state.value = _state.value.copy(
				optimizing = true,
				optimizeError = "",
				optimizedText = ""
			)

			val result = withContext(Dispatchers.IO) {
				runCatching {
					FallbackChatClient(usable).chat(
						messages = listOf(
							FallbackChatClient.Message("system", OPTIMIZE_SYSTEM_PROMPT),
							FallbackChatClient.Message(
								"user",
								"请优化下面这份 QQ 群机器人的人设提示词：\n\n```\n$current\n```"
							)
						),
						temperature = 0.7,
						maxTokens = 2048
					)
				}
			}

			val r = result.getOrNull()
			when {
				r == null -> _state.value = _state.value.copy(
					optimizing = false,
					optimizeError = "调用失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
				)
				!r.ok -> _state.value = _state.value.copy(
					optimizing = false,
					optimizeError = "模型返回错误：${r.error}"
				)
				r.text.isBlank() -> _state.value = _state.value.copy(
					optimizing = false,
					optimizeError = "模型返回了空内容，请重试"
				)
				else -> _state.value = _state.value.copy(
					optimizing = false,
					optimizedText = stripCodeFence(r.text.trim()),
					optimizeError = ""
				)
			}
		}
	}

	fun acceptOptimization() {
		val opt = _state.value.optimizedText
		if (opt.isBlank()) return
		_state.value = _state.value.copy(
			text = opt,
			charCount = opt.length,
			optimizedText = "",
			optimizeError = "",
			dirty = opt != savedBaseline
		)
	}

	fun discardOptimization() {
		_state.value = _state.value.copy(optimizedText = "", optimizeError = "")
	}

	private fun stripCodeFence(s: String): String {
		val trimmed = s.trim()
		if (!trimmed.startsWith("```")) return trimmed
		val lines = trimmed.lines()
		val body = lines.drop(1).dropLastWhile { it.trim().isEmpty() || it.trim() == "```" }
		return body.joinToString("\n").trim()
	}

	companion object {
		private val OPTIMIZE_SYSTEM_PROMPT = """
你是提示词工程专家，专门优化「QQ 群机器人」的人设提示词。

要求：
1. **保留原有性格内核**，不要换成另一个人。
2. 结构化成 Markdown，建议包含：身份 / 性格 / 说话方式 / 禁止项。
3. 适配 QQ 群聊：一次说 1-3 句，口语化，不写长篇大论。
4. 禁止出现：自称 AI、提模型名、客服腔、汇报式表达。
4.5 **保持原有角色类型**：原文是猫娘就还是猫娘（保留喵、蹭蹭、软软的语气）；
   原文是毒舌就还是毒舌。**绝不要把任何人设改写成客服/助手**。
5. 控制在 500-1500 字。
6. **只输出人设正文**，不要解释、前言、结语，不要包在代码块里。
""".trimIndent()
	}
}
