package com.crsyyds.suzubot.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话历史持久化。
 *
 * 原版：LismQQ-bot `src/pi-host.js` 里的 SessionManager + transcript 机制
 *
 * 每个 QQ 会话（群/私聊）一份 transcript，重启能恢复；
 * 超过 maxMessages 时按配置做「摘要压缩」或「硬丢最旧」。
 *
 * 存储格式：每个会话一个 JSON 文件，放在 filesDir/sessions/ 下。
 */
class SessionStore(
	private val context: Context,
	private val maxMessages: Int = 120,
	private val summarize: Boolean = true
) {

	data class Msg(val role: String, val content: String, val ts: Long = System.currentTimeMillis())

	inner class Transcript(val key: String) {
		val messages = mutableListOf<Msg>()

		fun add(role: String, content: String) {
			messages.add(Msg(role, content))
			if (messages.size > maxMessages) {
				if (summarize) {
					compress()
				} else {
					val overflow = messages.size - maxMessages
					messages.subList(0, overflow).clear()
				}
			}
		}

		/**
		 * 把最旧的一段压成一条摘要。
		 *
		 * 注：原版会真的调模型做摘要。这里先做「结构化压缩」，
		 * 把最旧 N 条合并成一段省略提示，避免额外 API 调用。
		 */
		private fun compress() {
			val keep = maxMessages / 2
			val drop = messages.size - keep
			if (drop <= 0) return
			val dropped = messages.subList(0, drop).toList()
			val head = dropped.take(6).joinToString("；") {
				val who = if (it.role == "user") "对方" else "我"
				"$who:${it.content.take(40)}"
			}
			val summary = Msg(
				role = "system",
				content = "【此前对话摘要】共 ${dropped.size} 条，摘要：$head …（更早内容已省略）"
			)
			messages.subList(0, drop).clear()
			messages.add(0, summary)
		}

		/** 取最近 n 条（不含 system）。 */
		fun recent(n: Int): List<Msg> = messages.filter { it.role != "system" }.takeLast(n)

		fun clear() = messages.clear()
	}

	private val cache = ConcurrentHashMap<String, Transcript>()

	private fun dir(): File = File(context.filesDir, "sessions").also { it.mkdirs() }

	private fun fileFor(key: String): File {
		val safe = key.replace(Regex("[^A-Za-z0-9_.-]"), "_")
		return File(dir(), "$safe.json")
	}

	@Synchronized
	fun get(key: String): Transcript {
		return cache.getOrPut(key) { load(key) ?: Transcript(key) }
	}

	@Synchronized
	fun save(key: String) {
		val t = cache[key] ?: return
		runCatching {
			val arr = JSONArray()
			t.messages.forEach { m ->
				arr.put(
					JSONObject()
						.put("role", m.role)
						.put("content", m.content)
						.put("ts", m.ts)
				)
			}
			fileFor(key).writeText(arr.toString(), Charsets.UTF_8)
		}.onFailure { Log.w(TAG, "save session $key failed", it) }
	}

	@Synchronized
	fun saveAll() {
		// cache.keys 是 KeySetView（API 24+），用 toTypedArray 规避
		cache.keys.toTypedArray().forEach { save(it) }
	}

	@Synchronized
	fun clear(key: String) {
		cache[key]?.clear()
		runCatching { fileFor(key).delete() }
	}

	fun listSessions(): List<String> = dir().listFiles()
		?.filter { it.extension == "json" }
		?.map { it.nameWithoutExtension }
		?: emptyList()

	private fun load(key: String): Transcript? {
		val f = fileFor(key)
		if (!f.exists()) return null
		return runCatching {
			val arr = JSONArray(f.readText(Charsets.UTF_8))
			val t = Transcript(key)
			for (i in 0 until arr.length()) {
				val o = arr.getJSONObject(i)
				t.messages.add(
					Msg(
						role = o.optString("role"),
						content = o.optString("content"),
						ts = o.optLong("ts")
					)
				)
			}
			t
		}.onFailure { Log.w(TAG, "load session $key failed", it) }.getOrNull()
	}

	companion object {
		private const val TAG = "SessionStore"
	}
}
