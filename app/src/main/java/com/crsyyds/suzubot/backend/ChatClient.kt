package com.crsyyds.suzubot.backend

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek / OpenAI 兼容客户端，支持 function calling。
 *
 * 只做「给定消息 + 工具定义 → 返回回复或工具调用」，流式暂不支持。
 */
class ChatClient(
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val apiKey: String,
	private val model: String = DEFAULT_MODEL
) {

	data class Message(val role: String, val content: String? = null, val raw: JSONObject? = null) {
		fun toJson(): JSONObject = raw ?: JSONObject().apply {
			put("role", role)
			if (content != null) put("content", content)
		}
	}

	/** 模型请求调用某个工具。 */
	data class ToolCall(
		val id: String,
		val name: String,
		val arguments: JSONObject
	)

	data class Result(
		val ok: Boolean,
		val text: String = "",
		val toolCalls: List<ToolCall> = emptyList(),
		val error: String = "",
		val finishReason: String = ""
	) {
		val wantsTool: Boolean get() = toolCalls.isNotEmpty()
	}

	/** 工具定义（OpenAI function schema）。 */
	data class ToolSpec(
		val name: String,
		val description: String,
		val parameters: JSONObject,
		val required: List<String> = emptyList()
	)

	fun chat(
		messages: List<Message>,
		tools: List<ToolSpec> = emptyList(),
		temperature: Double = 0.85,
		maxTokens: Int = 2048
	): Result {
		if (apiKey.isBlank()) return Result(false, error = "未配置 API Key")

		val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = 15_000
			readTimeout = 120_000
			doOutput = true
			setRequestProperty("Content-Type", "application/json; charset=utf-8")
			setRequestProperty("Authorization", "Bearer $apiKey")
		}

		val body = JSONObject()
			.put("model", model)
			.put("temperature", temperature)
			.put("max_tokens", maxTokens)
			.put("messages", JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })

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
				Log.w(TAG, "HTTP $code: ${text.take(300)}")
				return Result(false, error = "API $code: ${text.take(200)}")
			}

			parseResponse(text)
		} catch (t: Throwable) {
			Log.w(TAG, "chat failed", t)
			Result(false, error = t.message ?: "网络错误")
		} finally {
			conn.disconnect()
		}
	}

	private fun parseResponse(text: String): Result {
		val json = JSONObject(text)
		val choice = json.optJSONArray("choices")?.optJSONObject(0)
			?: return Result(false, error = "响应无 choices")
		val msg = choice.optJSONObject("message")
			?: return Result(false, error = "响应无 message")

		val content = msg.optString("content").trim()
		val finish = choice.optString("finish_reason")

		val calls = mutableListOf<ToolCall>()
		val arr = msg.optJSONArray("tool_calls")
		if (arr != null) {
			for (i in 0 until arr.length()) {
				val c = arr.optJSONObject(i) ?: continue
				val fn = c.optJSONObject("function") ?: continue
				val argsText = fn.optString("arguments")
				val args = runCatching { JSONObject(argsText) }.getOrElse { JSONObject() }
				calls.add(
					ToolCall(
						id = c.optString("id"),
						name = fn.optString("name"),
						arguments = args
					)
				)
			}
		}

		return Result(
			ok = true,
			text = content,
			toolCalls = calls,
			finishReason = finish
		)
	}

	/** 把工具执行结果包装成 tool 消息。 */
	fun toolResultMessage(callId: String, content: String): Message {
		val obj = JSONObject()
			.put("role", "tool")
			.put("tool_call_id", callId)
			.put("content", content)
		return Message("tool", content, obj)
	}

	/** 把模型的 tool_calls 原样包装成 assistant 消息（回传给模型）。 */
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

	companion object {
		private const val TAG = "ChatClient"
		const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
		const val DEFAULT_MODEL = "deepseek-chat"
	}
}
