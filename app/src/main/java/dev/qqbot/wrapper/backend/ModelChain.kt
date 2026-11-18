package dev.qqbot.wrapper.backend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型配置：主模型 + 若干回退模型。
 *
 * **端点与模型名均取自官方文档（2026-11 核对）**：
 *
 * DeepSeek（https://api-docs.deepseek.com/）
 *   base_url: https://api.deepseek.com     ← 注意：不带 /v1
 *   model:    deepseek-flash               ← 当前主力（跑 V4.1-Flash）
 *             deepseek-v4-pro
 *   注：旧名 deepseek-chat / deepseek-v4-flash 已下线或转发，别用
 *
 * 智谱 GLM（https://docs.bigmodel.cn/）
 *   base_url: https://open.bigmodel.cn/api/paas/v4
 *   model:    glm-4-flash（免费，最稳）/ glm-4.6 / glm-4.7-flash / glm-5
 *   注：glm-4-flashx 收费且要余额；glm-4.5-flash 已下线但旧 ID 仍能路由
 *
 * 自定义：任意 OpenAI 兼容端点，用户自填。
 */
data class ModelEndpoint(
	val id: String,
	val name: String,
	/** deepseek / zhipu / custom */
	val kind: String,
	val baseUrl: String,
	val apiKey: String,
	val model: String,
	val timeoutMs: Int = 45_000,
	/** 是否开启深度思考（thinking / reasoning） */
	val thinkingEnabled: Boolean = false,
	/** 是否支持图片输入（多模态） */
	val visionEnabled: Boolean = false
) {
	val configured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

	fun toJson(): JSONObject = JSONObject()
		.put("id", id).put("name", name).put("kind", kind)
		.put("baseUrl", baseUrl).put("apiKey", apiKey)
		.put("model", model).put("timeoutMs", timeoutMs)
		.put("thinkingEnabled", thinkingEnabled)
		.put("visionEnabled", visionEnabled)

	companion object {
		fun fromJson(o: JSONObject): ModelEndpoint = ModelEndpoint(
			id = o.optString("id", "m-${System.currentTimeMillis()}"),
			name = o.optString("name", "未命名"),
			kind = o.optString("kind", "custom"),
			baseUrl = o.optString("baseUrl", ""),
			apiKey = o.optString("apiKey", ""),
			model = o.optString("model", ""),
			timeoutMs = o.optInt("timeoutMs", 45_000),
			thinkingEnabled = o.optBoolean("thinkingEnabled", false),
			visionEnabled = o.optBoolean("visionEnabled", false)
		)

		/** 预设模板（官方文档核对过的值）。 */
		fun preset(kind: String): ModelEndpoint = when (kind) {
			"deepseek" -> ModelEndpoint(
				id = "deepseek",
				name = "DeepSeek",
				kind = "deepseek",
				baseUrl = "https://api.deepseek.com",   // 不带 /v1
				apiKey = "",
				model = "deepseek-flash",
				thinkingEnabled = false,   // 默认关：联天机器人不需要推理过程
				visionEnabled = false      // deepseek-flash 不支持图片
			)
			"zhipu" -> ModelEndpoint(
				id = "zhipu",
				name = "智谱 GLM",
				kind = "zhipu",
				baseUrl = "https://open.bigmodel.cn/api/paas/v4",
				apiKey = "",
				model = "glm-4-flash",
				thinkingEnabled = false,
				visionEnabled = false
			)
			else -> ModelEndpoint(
				id = "custom",
				name = "自定义",
				kind = "custom",
				baseUrl = "",
				apiKey = "",
				model = ""
			)
		}

		/** 每个预设的可选模型，给 UI 下拉用。 */
		/**
		 * 模型清单。标注了看图能力（V = vision，T = thinking）。
		 *
		 * 依据：
		 *  DeepSeek  https://api-docs.deepseek.com/  —— deepseek-flash / deepseek-v4-pro 不支持图片
		 *  智谱      https://docs.bigmodel.cn/     —— glm-4v* / glm-4.6v* / glm-5v* 为视觉模型
		 */
		fun modelCatalog(kind: String): List<ModelOption> = when (kind) {
			"deepseek" -> listOf(
				ModelOption("deepseek-flash", "deepseek-flash（快速，推理可关）", vision = false, thinking = true),
				ModelOption("deepseek-v4-pro", "deepseek-v4-pro（高质）", vision = false, thinking = true)
			)
			"zhipu" -> listOf(
				ModelOption("glm-4-flash", "glm-4-flash（免费文本）", vision = false, thinking = false),
				ModelOption("glm-4.7-flash", "glm-4.7-flash（免费新档）", vision = false, thinking = true),
				ModelOption("glm-4.6", "glm-4.6", vision = false, thinking = true),
				ModelOption("glm-4.7", "glm-4.7", vision = false, thinking = true),
				ModelOption("glm-5", "glm-5", vision = false, thinking = true),
				ModelOption("glm-5-turbo", "glm-5-turbo", vision = false, thinking = true),
				ModelOption("glm-4v-flash", "glm-4v-flash（看图免费）", vision = true, thinking = false),
				ModelOption("glm-4.6v-flash", "glm-4.6v-flash（看图免费）", vision = true, thinking = false),
				ModelOption("glm-4.6v", "glm-4.6v（看图）", vision = true, thinking = true),
				ModelOption("glm-4v-plus", "glm-4v-plus（看图增强）", vision = true, thinking = false)
			)
			else -> emptyList()
		}

		fun modelsFor(kind: String): List<String> = modelCatalog(kind).map { it.id }

		val PRESET_KINDS = listOf("deepseek", "zhipu", "custom")
	}
}

/** 模型选项与能力标注。 */
data class ModelOption(
	val id: String,
	val label: String,
	/** 支持图片输入 */
	val vision: Boolean,
	/** 支持深度思考 / 推理 */
	val thinking: Boolean
)

class ModelChainManager(private val context: Context) {

	private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

	fun load(): List<ModelEndpoint> {
		val raw = prefs.getString(KEY_CHAIN, null) ?: return defaultChain()
		return runCatching {
			val arr = JSONArray(raw)
			(0 until arr.length()).mapNotNull { i ->
				arr.optJSONObject(i)?.let { ModelEndpoint.fromJson(it) }
			}
		}.getOrElse { defaultChain() }
	}

	fun save(chain: List<ModelEndpoint>) {
		val arr = JSONArray()
		chain.forEach { arr.put(it.toJson()) }
		prefs.edit().putString(KEY_CHAIN, arr.toString()).apply()
	}

	fun usable(): List<ModelEndpoint> = load().filter { it.configured }

	/**
	 * 迁移旧版单模型配置，**并修正常见的错误值**。
	 *
	 * 老版本 App 写进去的是 api.deepseek.com/v1 + deepseek-chat，
	 * 这两个都是错的，这里自动纠正。
	 */
	fun migrateLegacy() {
		val setup = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
		val key = setup.getString("api_key", null)?.trim().orEmpty()
		if (key.isEmpty()) return
		if (prefs.contains(KEY_CHAIN)) {
			// 已有链，但仍要修正错误值
			fixKnownBadValues()
			return
		}

		val oldBase = setup.getString("api_base", null) ?: ""
		val oldModel = setup.getString("api_model", null) ?: ""
		val kind = when {
			oldBase.contains("deepseek") -> "deepseek"
			oldBase.contains("bigmodel") || oldBase.contains("zhipu") -> "zhipu"
			else -> "custom"
		}
		val preset = ModelEndpoint.preset(kind)
		val fixedBase = if (kind == "deepseek" && oldBase.contains("api.deepseek.com")) {
			"https://api.deepseek.com"
		} else oldBase.ifEmpty { preset.baseUrl }
		val fixedModel = when {
			kind == "deepseek" && (oldModel == "deepseek-chat" || oldModel.isEmpty()) -> "deepseek-flash"
			kind == "zhipu" && oldModel.isEmpty() -> "glm-4-flash"
			else -> oldModel.ifEmpty { preset.model }
		}
		save(listOf(preset.copy(apiKey = key, baseUrl = fixedBase, model = fixedModel)))
	}

	/** 修正已存的错误配置（deepseek-chat / 带 /v1 的地址）。 */
	private fun fixKnownBadValues() {
		val chain = load()
		var changed = false
		val fixed = chain.map { e ->
			var out = e
			if (out.kind == "deepseek") {
				val base = out.baseUrl
				if (base.contains("api.deepseek.com") && base.trimEnd('/').endsWith("/v1")) {
					out = out.copy(baseUrl = "https://api.deepseek.com")
					changed = true
				}
				if (out.model == "deepseek-chat" || out.model == "deepseek-v4-flash") {
					out = out.copy(model = "deepseek-flash")
					changed = true
				}
			}
			out
		}
		if (changed) save(fixed)
	}

	private fun defaultChain(): List<ModelEndpoint> = listOf(ModelEndpoint.preset("deepseek"))

	companion object {
		private const val KEY_CHAIN = "chain"
	}
}
