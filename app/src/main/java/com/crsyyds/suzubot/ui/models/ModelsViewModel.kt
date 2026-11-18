package com.crsyyds.suzubot.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crsyyds.suzubot.backend.BotRuntime
import com.crsyyds.suzubot.backend.FallbackChatClient
import com.crsyyds.suzubot.backend.ModelChainManager
import com.crsyyds.suzubot.backend.ModelEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelsUiState(
	val chain: List<ModelEndpoint> = emptyList(),
	val editingIndex: Int = -1,
	val savedFlash: Boolean = false,
	val testResult: String = "",
	val testing: Boolean = false
) {
	val anyConfigured: Boolean get() = chain.any { it.configured }
}

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

	private val manager = ModelChainManager(app)
	private val _state = MutableStateFlow(ModelsUiState())
	val state: StateFlow<ModelsUiState> = _state.asStateFlow()

	init {
		manager.migrateLegacy()
		load()
	}

	fun load() {
		_state.value = _state.value.copy(chain = manager.load())
	}

	/** 新增预设（DeepSeek / 智谱 / 自定义）。 */
	fun addPreset(kind: String) {
		val list = _state.value.chain.toMutableList()
		if (kind != "custom" && list.any { it.kind == kind }) return
		val preset = ModelEndpoint.preset(kind)
		val e = if (kind == "custom") {
			preset.copy(id = "custom-${System.currentTimeMillis()}")
		} else preset
		list.add(e)
		_state.value = _state.value.copy(chain = list, editingIndex = list.lastIndex)
	}

	fun remove(index: Int) {
		val list = _state.value.chain.toMutableList()
		if (index in list.indices) {
			list.removeAt(index)
			_state.value = _state.value.copy(
				chain = list,
				editingIndex = if (list.isEmpty()) -1
				else list.lastIndex.coerceAtMost(if (_state.value.editingIndex < 0) 0 else _state.value.editingIndex)
			)
		}
	}

	fun move(from: Int, to: Int) {
		val list = _state.value.chain.toMutableList()
		if (from !in list.indices || to !in list.indices) return
		val item = list.removeAt(from)
		list.add(to, item)
		_state.value = _state.value.copy(chain = list, editingIndex = to)
	}

	fun update(index: Int, endpoint: ModelEndpoint) {
		val list = _state.value.chain.toMutableList()
		if (index in list.indices) {
			list[index] = endpoint
			_state.value = _state.value.copy(chain = list)
		}
	}

	fun setEditing(index: Int) {
		_state.value = _state.value.copy(editingIndex = index, testResult = "")
	}

	/** 当前预设可选的模型列表（给 UI 做快捷选择）。 */
	fun modelsFor(index: Int): List<String> {
		val e = _state.value.chain.getOrNull(index) ?: return emptyList()
		return ModelEndpoint.modelsFor(e.kind)
	}

	/**
	 * 测试单个模型。
	 * 失败时给出 **URL / HTTP 码 / 原始响应**，方便定位问题。
	 */
	fun test(index: Int) {
		val e = _state.value.chain.getOrNull(index) ?: return
		if (!e.configured) {
			_state.value = _state.value.copy(testResult = "请先填完整 Key / 地址 / 模型名")
			return
		}
		viewModelScope.launch {
			_state.value = _state.value.copy(testing = true, testResult = "测试中…")
			val r = withContext(Dispatchers.IO) {
				runCatching { FallbackChatClient(listOf(e)).probe(e) }
					.getOrElse { FallbackChatClient.ProbeResult(false, e.baseUrl, 0, it.message ?: "异常") }
			}
			val msg = buildString {
				append(if (r.ok) "✅ 可用" else "❌ 失败")
				append("\n请求地址: ")
				append(r.url)
				if (r.httpCode > 0) append("\nHTTP ").append(r.httpCode)
				append("\n")
				append(r.detail)
			}
			_state.value = _state.value.copy(testing = false, testResult = msg)
		}
	}

	fun save() {
		manager.save(_state.value.chain)
		BotRuntime.stop()
		_state.value = _state.value.copy(savedFlash = true)
		viewModelScope.launch {
			delay(2000)
			_state.value = _state.value.copy(savedFlash = false)
		}
	}
}
