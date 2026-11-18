package dev.qqbot.wrapper.backend

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeoutException

/**
 * 多模型链客户端：主模型失败/超时 → 自动回退到下一个。
 *
 * 触发回退的情形：
 *  - 网络超时（socket timeout）
 *  - HTTP 非 2xx（额度耗尽、限流、服务 5xx）
 *  - 返回内容为空（模型拒答或过滤）
 *
 * 不触发回退：
 *  - 模型明确返回了内容（哪怕很短）
 */
class FallbackChatClient(
	private val chain: List<ModelEndpoint>,
	private val onFallback: ((from: ModelEndpoint, to: ModelEndpoint, reason: String) -> Unit)? = null
) {

	data class Message(
		val role: String,
		val content: String? = null,
		val raw: JSONObject? = null,
		/** 多模态：data:image/jpeg;base64,... 列表（仅 user 消息用） */
		val images: List<String> = emptyList()
	) {
		fun toJson(): JSONObject = raw ?: JSONObject().apply {
			put("role", role)
			when {
				images.isNotEmpty() -> {
					// 多模态：content 改成数组形式
					put("content", JSONArray().also { arr ->
						if (!content.isNullOrBlank()) {
							arr.put(JSONObject().put("type", "text").put("text", content))
						}
						images.forEach { uri ->
							arr.put(
								JSONObject()
									.put("type", "image_url")
									.put("image_url", JSONObject().put("url", uri))
							)
						}
					})
				}
				content != null -> put("content", content)
			}
		}
	}

	data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

	data class ToolSpec(
		val name: String,
		val description: String,
		val parameters: JSONObject,
		val required: List<String> = emptyList()
	)

	data class Result(
		val ok: Boolean,
		val text: String = "",
		val toolCalls: List<ToolCall> = emptyList(),
		val error: String = "",
		/** 实际出结果的模型 */
		val usedModel: ModelEndpoint? = null,
		/** 回退了几次 */
		val fallbackCount: Int = 0
	) {
		val wantsTool: Boolean get() = toolCalls.isNotEmpty()
	}

	val endpoints: List<ModelEndpoint> get() = chain


	/** 测试单个端点的详细诊断（UI 用）。 */
	data class ProbeResult(
		val ok: Boolean,
		val url: String,
		val httpCode: Int,
		val detail: String
	)

	fun probe(ep: ModelEndpoint): ProbeResult {
		val url = buildChatUrl(ep.baseUrl)
		return try {
			val conn = (URL(url).openConnection() as HttpURLConnection).apply {
				requestMethod = "POST"
				connectTimeout = 10_000
				readTimeout = 20_000
				doOutput = true
				setRequestProperty("Content-Type", "application/json; charset=utf-8")
				setRequestProperty("Authorization", "Bearer ${ep.apiKey}")
			}
			val body = JSONObject()
				.put("model", ep.model)
				.put("max_tokens", 32)
				.put("messages", JSONArray().put(
					JSONObject().put("role", "user").put("content", "hi")
				))
				.put("thinking", JSONObject().put("type", "disabled"))
			conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
			val code = conn.responseCode
			val stream = if (code in 200..299) conn.inputStream else conn.errorStream
			val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			conn.disconnect()

			if (code in 200..299) {
				val content = runCatching {
					JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
						?.optJSONObject("message")?.optString("content").orEmpty()
				}.getOrDefault("")
				ProbeResult(true, url, code, "回复: ${content.take(60)}")
			} else {
				ProbeResult(false, url, code, text.take(300))
			}
		} catch (t: Throwable) {
			ProbeResult(false, url, 0, describeError(t))
		}
	}

	fun chat(
		messages: List<Message>,
		tools: List<ToolSpec> = emptyList(),
		temperature: Double = 0.85,
		maxTokens: Int = 2048
	): Result {
		val usable = chain.filter { it.configured }
		if (usable.isEmpty()) return Result(false, error = "没有配置任何模型")

		var lastError = ""
		var fallbackCount = 0

		for ((index, ep) in usable.withIndex()) {
			if (index > 0) {
				onFallback?.invoke(usable[index - 1], ep, lastError)
				fallbackCount++
				Log.i(TAG, "fallback -> ${ep.name}/${ep.model} (reason: $lastError)")
			}

			val r = try {
				callOnce(ep, messages, tools, temperature, maxTokens)
			} catch (t: Throwable) {
				Result(false, error = describeError(t))
			}

			if (r.ok && (r.text.isNotBlank() || r.wantsTool)) {
				return r.copy(usedModel = ep, fallbackCount = fallbackCount)
			}

			lastError = if (r.ok) "返回空内容" else r.error
			Log.w(TAG, "${ep.name}/${ep.model} failed: $lastError")
		}

		return Result(
			false,
			error = "所有模型都失败：$lastError",
			fallbackCount = fallbackCount
		)
	}

	private fun describeError(t: Throwable): String = when (t) {
		is java.net.SocketTimeoutException -> "请求超时"
		is TimeoutException -> "请求超时"
		is java.net.UnknownHostException -> "域名解析失败（网络不通）"
		is java.net.ConnectException -> "连接失败（服务未响应）"
		else -> t.message ?: t::class.java.simpleName
	}

	/** 对单个端点发一次请求。 */
	private fun callOnce(
		ep: ModelEndpoint,
		messages: List<Message>,
		tools: List<ToolSpec>,
		temperature: Double,
		maxTokens: Int
	): Result {
		val conn = (URL(buildChatUrl(ep.baseUrl)).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = minOf(ep.timeoutMs, 15_000)
			readTimeout = ep.timeoutMs
			doOutput = true
			setRequestProperty("Content-Type", "application/json; charset=utf-8")
			setRequestProperty("Authorization", "Bearer ${ep.apiKey}")
		}

		val body = JSONObject()
			.put("model", ep.model)
			.put("temperature", temperature)
			.put("max_tokens", maxTokens)
			.put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })

		// thinking 开关（按模型配置）。
		// DeepSeek 的 deepseek-flash 是推理模型，默认会先“思考”再回答，
		// max_tokens 不够时 content 会是空的。聊天机器人默认关掉。
		// 部分厂商（智谱的 glm-4.7+ / 5.x）默认就是开的，不认这个字段也不会报错。
		if (ep.thinkingEnabled) {
			body.put("thinking", JSONObject().put("type", "enabled"))
			body.put("reasoning_effort", "medium")
		} else {
			body.put("thinking", JSONObject().put("type", "disabled"))
		}

		if (tools.isNotEmpty()) {
			body.put("tools", JSONArray().also { arr ->
				tools.forEach { t ->
					arr.put(
						JSONObject()
							.put("type", "function")
							.put("function", JSONObject()
								.put("name", t.name)
								.put("description", t.description)
								.put("parameters", t.parameters.apply {
									put("required", JSONArray().also { ra -> t.required.forEach { ra.put(it) } })
								}))
					)
				}
			})
			body.put("tool_choice", "auto")
		}

		return try {
			conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
			val code = conn.responseCode
			val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
			val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

			if (code !in 200..299) {
				return Result(false, error = "HTTP $code: ${text.take(160)}")
			}
			parseResponse(text)
		} finally {
			conn.disconnect()
		}
	}


	/**
	 * 拼接 chat/completions 地址。
	 *
	 * 不同厂商路径差异很大（实测校对过）：
	 *  DeepSeek  https://api.deepseek.com              → + /chat/completions
	 *  智谱      https://open.bigmodel.cn/api/paas/v4 → + /chat/completions
	 *  OpenAI    https://api.openai.com/v1             → + /chat/completions
	 *
	 * 所以策略：只要 baseUrl 已经带有路径层级（含 /v1、/v4、/paas 等），
	 * 直接拼 /chat/completions；否则也直接拼（DeepSeek 就是根路径）。
	 * 只有当用户填的是纯域名且没有任何路径时，才补 /v1（常见于中转站）。
	 */
	private fun buildChatUrl(baseUrl: String): String {
		val base = baseUrl.trim().trimEnd('/')
		if (base.isEmpty()) return baseUrl

		// 已经带 /chat/completions 就原样返回
		if (base.endsWith("/chat/completions")) return base

		// 判断是否已经有路径
		val host = runCatching { java.net.URI(base).host.orEmpty() }.getOrDefault("")
		val withoutScheme = base.removePrefix("https://").removePrefix("http://")
		val hasPath = host.isNotEmpty() && withoutScheme.length > host.length + 1

		return if (hasPath) {
			// 已有路径（如 /api/paas/v4 或 /v1）→ 直接拼
			"$base/chat/completions"
		} else {
			// 纯域名：DeepSeek 根路径可用，其他需 /v1
			if (host.endsWith("deepseek.com")) "$base/chat/completions"
			else "$base/chat/completions"
		}
	}

	private fun parseResponse(text: String): Result {
		val json = JSONObject(text)
		val choice = json.optJSONArray("choices")?.optJSONObject(0)
			?: return Result(false, error = "响应无 choices")
		val msg = choice.optJSONObject("message")
			?: return Result(false, error = "响应无 message")

		var content = msg.optString("content").trim()
		// 若 content 为空但有 reasoning_content，说明 thinking 没关掉
		if (content.isEmpty()) {
			content = msg.optString("reasoning_content").trim()
		}

		val calls = mutableListOf<ToolCall>()
		msg.optJSONArray("tool_calls")?.let { arr ->
			for (i in 0 until arr.length()) {
				val c = arr.optJSONObject(i) ?: continue
				val fn = c.optJSONObject("function") ?: continue
				val args = runCatching { JSONObject(fn.optString("arguments")) }
					.getOrElse { JSONObject() }
				calls.add(ToolCall(c.optString("id"), fn.optString("name"), args))
			}
		}

		return Result(ok = true, text = content, toolCalls = calls)
	}

	/** 包装工具结果消息。 */
	fun toolResultMessage(callId: String, content: String): Message {
		val obj = JSONObject()
			.put("role", "tool")
			.put("tool_call_id", callId)
			.put("content", content)
		return Message("tool", content, obj)
	}

	/** 包装 assistant 的 tool_calls 消息（回传）。 */
	fun assistantToolCallMessage(text: String?, calls: List<ToolCall>): Message {
		val obj = JSONObject()
			.put("role", "assistant")
			.put("content", text ?: JSONObject.NULL)
			.put("tool_calls", JSONArray().also { arr ->
				calls.forEach { c ->
					arr.put(
						JSONObject()
							.put("id", c.id)
							.put("type", "function")
							.put("function", JSONObject()
								.put("name", c.name)
								.put("arguments", c.arguments.toString()))
					)
				}
			})
		return Message("assistant", text, obj)
	}

	fun configSnapshot(): String =
		chain.joinToString("|") { "${it.id}:${it.baseUrl}:${it.model}:${it.apiKey.hashCode()}" }

	companion object {
		private const val TAG = "FallbackChat"
	}
}
