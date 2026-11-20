package com.crsyyds.suzubot.backend

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OneBot v11 客户端（服务于 PicoOnebot）。
 *
 * 说明：Android 没有内置 WebSocket 客户端，这里手写了一个最小 RFC6455 实现
 * （仅文本帧 + 客户端掩码），避免引入额外依赖、把 APK 撑大。
 *
 * 支持的 action 见 docs/picoonebot-capabilities.md。
 */
class OneBotClient(
	private val scope: CoroutineScope,
	private val host: String = "127.0.0.1",
	private val port: Int = 3001,
	private val accessToken: String = ""
) {

	interface Listener {
		fun onConnected(selfId: String)
		fun onDisconnected(reason: String)
		fun onMessage(msg: IncomingMessage)
		/** 通知类事件（目前 PicoOnebot 覆盖有限）。 */
		fun onNotice(raw: JSONObject) {}
		/** 元事件（心跳/生命周期）。 */
		fun onMetaEvent(raw: JSONObject) {}
	}

	data class IncomingMessage(
		val messageType: String,
		val messageId: Long,
		val userId: Long,
		val groupId: Long,
		val nickname: String,
		val card: String,
		val role: String,
		val text: String,
		val images: List<String>,
		val raw: JSONObject
	) {
		val isGroup: Boolean get() = messageType == "group"
		val sessionKey: String get() = if (isGroup) "g:$groupId" else "u:$userId"
		val displayName: String get() = card.ifEmpty { nickname }
	}

	var listener: Listener? = null

	private var job: Job? = null
	@Volatile private var socket: Socket? = null
	@Volatile private var connected = false
	@Volatile private var selfId: String = ""
	@Volatile private var selfNick: String = ""

	private var echoSeq = 0
	private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()

	val isConnected: Boolean get() = connected
	fun currentSelfId(): String = selfId
	fun currentSelfNick(): String = selfNick

	// ── 连接管理 ──────────────────────────────────────────────────────────────

	fun start() {
		if (job?.isActive == true) return
		job = scope.launch {
			var backoff = 1000L
			while (isActive) {
				try {
					runSession()
					backoff = 1000L
				} catch (t: Throwable) {
					if (!isActive) break
					Log.w(TAG, "WS session ended: ${t.message}")
				}
				connected = false
				listener?.onDisconnected("连接断开")
				delay(backoff)
				backoff = (backoff * 2).coerceAtMost(30_000L)
			}
		}
	}

	fun stop() {
		job?.cancel()
		job = null
		runCatching { socket?.close() }
		socket = null
		connected = false
	}

	private fun runSession() {
		val s = Socket()
		s.connect(java.net.InetSocketAddress(host, port), 8_000)
		s.soTimeout = 0
		socket = s

		// 用 android.util.Base64（API 8+），不能用 java.util.Base64（API 26+，Android 6/7 上会 NoSuchMethodError）
		// NO_WRAP 必须加：否则长字符串会自动插入换行，握手头就坏了
		val key = Base64.encodeToString(
			ByteArray(16).also { java.util.Random().nextBytes(it) },
			Base64.NO_WRAP
		)
		val path = if (accessToken.isEmpty()) "/" else "/?access_token=$accessToken"
		val req = buildString {
			append("GET $path HTTP/1.1\r\n")
			append("Host: $host:$port\r\n")
			append("Upgrade: websocket\r\n")
			append("Connection: Upgrade\r\n")
			append("Sec-WebSocket-Key: $key\r\n")
			append("Sec-WebSocket-Version: 13\r\n")
			append("\r\n")
		}
		s.getOutputStream().apply { write(req.toByteArray()); flush() }

		val input = s.getInputStream()
		val header = readHttpHeader(input)
		val expect = Base64.encodeToString(
			MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()),
			Base64.NO_WRAP
		)
		if (!header.contains("101") || !header.contains(expect, ignoreCase = true)) {
			throw java.io.IOException("WebSocket 握手失败: ${header.lines().firstOrNull()}")
		}

		connected = true
		scope.launch {
			runCatching {
				val res = callApi("get_login_info", JSONObject())
				val data = res.optJSONObject("data")
				val id = data?.optLong("user_id")?.toString() ?: ""
				selfNick = data?.optString("nickname").orEmpty()
				if (id.isNotEmpty() && id != "0") selfId = id
				listener?.onConnected(selfId)
			}.onFailure { listener?.onConnected("") }
		}

		readLoop(input)
	}

	private fun readHttpHeader(input: InputStream): String {
		val sb = StringBuilder()
		var prev = -1
		while (true) {
			val c = input.read()
			if (c == -1) break
			sb.append(c.toChar())
			if (prev == '\r'.code && c == '\n'.code && sb.endsWith("\r\n\r\n")) break
			prev = c
		}
		return sb.toString()
	}

	// ── 帧处理 ────────────────────────────────────────────────────────────────

	private fun readLoop(input: InputStream) {
		while (true) {
			val b0 = input.read()
			if (b0 == -1) break
			val b1 = input.read()
			if (b1 == -1) break

			val fin = (b0 and 0x80) != 0
			val opcode = b0 and 0x0F
			val masked = (b1 and 0x80) != 0
			var len = (b1 and 0x7F).toLong()

			if (len == 126L) {
				len = ((input.read() shl 8) or input.read()).toLong()
			} else if (len == 127L) {
				var l = 0L
				for (i in 0 until 8) l = (l shl 8) or input.read().toLong()
				len = l
			}

			val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
			val payload = ByteArray(len.toInt())
			input.readFully(payload)
			if (mask != null) {
				for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
			}

			when (opcode) {
				0x1, 0x2 -> if (fin) handleFrame(String(payload, Charsets.UTF_8))
				0x8 -> return
				0x9 -> sendFrame(0xA, payload)
				else -> Unit
			}
		}
	}

	private fun InputStream.readFully(b: ByteArray) {
		var off = 0
		while (off < b.size) {
			val n = read(b, off, b.size - off)
			if (n <= 0) throw java.io.IOException("stream closed")
			off += n
		}
	}

	private fun handleFrame(text: String) {
		val obj = runCatching { JSONObject(text) }.getOrNull() ?: return

		if (obj.has("echo")) {
			val echo = obj.optString("echo").toIntOrNull() ?: return
			pending.remove(echo)?.invoke(obj)
			return
		}

		when (obj.optString("post_type")) {
			"message" -> {
				val sender = obj.optJSONObject("sender")
				val seg = parseSegments(obj)
				listener?.onMessage(
					IncomingMessage(
						messageType = obj.optString("message_type"),
						messageId = obj.optLong("message_id"),
						userId = obj.optLong("user_id"),
						groupId = obj.optLong("group_id"),
						nickname = sender?.optString("nickname").orEmpty(),
						card = sender?.optString("card").orEmpty(),
						role = sender?.optString("role").orEmpty(),
						text = seg.first,
						images = seg.second,
						raw = obj
					)
				)
			}
			"notice" -> listener?.onNotice(obj)
			"meta_event" -> listener?.onMetaEvent(obj)
			else -> Unit
		}
	}

	/** 把 OneBot 的 message 数组归一化成 (纯文本, 图片URL列表)。 */
	private fun parseSegments(obj: JSONObject): Pair<String, List<String>> {
		val m = obj.opt("message")
		if (m is String) return m to emptyList()
		if (m !is JSONArray) return "" to emptyList()

		val sb = StringBuilder()
		val images = mutableListOf<String>()
		for (i in 0 until m.length()) {
			val seg = m.optJSONObject(i) ?: continue
			val data = seg.optJSONObject("data") ?: continue
			when (seg.optString("type")) {
				"text" -> sb.append(data.optString("text"))
				"at" -> {
					val qq = data.optString("qq")
					sb.append(if (qq == "all") "@全体成员 " else "@$qq ")
				}
				"image" -> {
					val url = data.optString("url").ifEmpty { data.optString("file") }
					if (url.isNotEmpty()) images.add(url)
					sb.append("[图片]")
				}
				"face" -> sb.append("[表情:${data.optString("id")}]")
				"mface" -> sb.append("[市场表情]")
				"record" -> sb.append("[语音]")
				"video" -> sb.append("[视频]")
				"reply" -> Unit
				"forward" -> {
					val id = data.optString("id").ifEmpty { data.optString("resid") }
					sb.append("[转发消息 id=$id]")
				}
				"json" -> sb.append("[卡片]")
				else -> Unit
			}
		}
		return sb.toString().trim() to images
	}

	// ── 发送 ──────────────────────────────────────────────────────────────────

	private fun sendFrame(opcode: Int, payload: ByteArray) {
		val s = socket ?: return
		runCatching {
			val out = s.getOutputStream()
			val len = payload.size
			val header = ByteArrayOutputStream()
			header.write(0x80 or opcode)
			when {
				len < 126 -> header.write(0x80 or len)
				len <= 0xFFFF -> {
					header.write(0x80 or 126)
					header.write((len shr 8) and 0xFF)
					header.write(len and 0xFF)
				}
				else -> {
					header.write(0x80 or 127)
					for (i in 7 downTo 0) header.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
				}
			}
			val mask = ByteArray(4).also { java.util.Random().nextBytes(it) }
			header.write(mask)
			val masked = ByteArray(len) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
			out.write(header.toByteArray())
			out.write(masked)
			out.flush()
		}
	}

	/** 调 OneBot API，返回整个响应 JSON。 */
	suspend fun callApi(action: String, params: JSONObject, timeoutMs: Long = 20_000): JSONObject {
		if (!connected) {
			return JSONObject().put("status", "failed").put("message", "未连接")
		}
		val echo = ++echoSeq
		val frame = JSONObject()
			.put("action", action)
			.put("params", params)
			.put("echo", echo.toString())

		val deferred = kotlinx.coroutines.CompletableDeferred<JSONObject>()
		pending[echo] = { deferred.complete(it) }
		sendFrame(0x1, frame.toString().toByteArray())

		val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
		pending.remove(echo)
		return result ?: JSONObject().put("status", "failed").put("message", "timeout")
	}

	/** 判断调用是否成功。 */
	private fun JSONObject.ok(): Boolean =
		optString("status") == "ok" || optInt("retcode", -1) == 0

	// ── 高层 API ──────────────────────────────────────────────────────────────

	/** 发文本消息。 */
	suspend fun sendText(msg: IncomingMessage, text: String): Boolean {
		val segments = JSONArray().put(
			JSONObject().put("type", "text").put("data", JSONObject().put("text", text))
		)
		return sendSegments(msg, segments)
	}

	/** 带引用的回复。 */
	suspend fun replyText(msg: IncomingMessage, text: String, replyToId: Long): Boolean {
		val segments = JSONArray()
			.put(JSONObject().put("type", "reply").put("data", JSONObject().put("id", replyToId.toString())))
			.put(JSONObject().put("type", "text").put("data", JSONObject().put("text", text)))
		return sendSegments(msg, segments)
	}

	/** 发图片（URL 或 base64）。 */
	suspend fun sendImage(msg: IncomingMessage, file: String): Boolean {
		val segments = JSONArray().put(
			JSONObject().put("type", "image").put("data", JSONObject().put("file", file))
		)
		return sendSegments(msg, segments)
	}

	private suspend fun sendSegments(msg: IncomingMessage, segments: JSONArray): Boolean {
		val params = JSONObject().put("message", segments)
		val action = if (msg.isGroup) {
			params.put("group_id", msg.groupId)
			"send_group_msg"
		} else {
			params.put("user_id", msg.userId)
			"send_private_msg"
		}
		return runCatching { callApi(action, params).ok() }.getOrDefault(false)
	}

	/** 撤回消息。 */
	suspend fun deleteMsg(messageId: Long): Boolean =
		runCatching {
			callApi("delete_msg", JSONObject().put("message_id", messageId)).ok()
		}.getOrDefault(false)

	/** 群列表。 */
	suspend fun groupList(): List<JSONObject> {
		val res = callApi("get_group_list", JSONObject())
		val arr = res.optJSONArray("data") ?: return emptyList()
		return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
	}

	/** 群成员列表。 */
	suspend fun groupMemberList(groupId: Long): List<JSONObject> {
		val res = callApi("get_group_member_list", JSONObject().put("group_id", groupId), 30_000)
		val arr = res.optJSONArray("data") ?: return emptyList()
		return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
	}

	/** 好友列表。 */
	suspend fun friendList(): List<JSONObject> {
		val res = callApi("get_friend_list", JSONObject())
		val arr = res.optJSONArray("data") ?: return emptyList()
		return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
	}

	/** 展开合并转发。 */
	suspend fun getForwardMsg(forwardId: String): JSONObject =
		callApi(
			"get_forward_msg",
			JSONObject().put("message_id", forwardId).put("id", forwardId),
			30_000
		)

	/** 取单条消息。 */
	suspend fun getMsg(messageId: Long): JSONObject =
		callApi("get_msg", JSONObject().put("message_id", messageId))

	/** 群成员信息。 */
	suspend fun groupMemberInfo(groupId: Long, userId: Long): JSONObject =
		callApi(
			"get_group_member_info",
			JSONObject().put("group_id", groupId).put("user_id", userId)
		)

	companion object {
		private const val TAG = "OneBotClient"
		private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
	}
}
