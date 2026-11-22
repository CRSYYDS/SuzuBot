package com.crsyyds.suzubot.pico

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * PicoOnebot 控制台 API 客户端（WebUI 端口，默认 6099）。
 *
 * 契约来源：PicoOnebot `app/src/main/java/pico/onebot/net/WebApi.kt`
 *
 * 关键行为（来自源码，不是猜的）：
 *  - 鉴权走 `Authorization: Bearer <token>`，且 **密码本身就能当 token 用**
 *    （`login()` 里 `Config.verifyWebuiPassword(token)` 与 session 走同一入口）
 *  - 首次运行密码未初始化时，**除 `PUT /api/webui/token` 外所有接口都会被拦**
 *  - `GET /api/qr` 只读不刷新（前端 2s 轮询），`POST /api/qr/refresh` 才主动要新码（最多等 3s）
 *  - `PUT /api/config` 会返回「实际发生了什么」，调用方只许复述不许自己编
 */
class PicoOnebotClient(
	private val baseUrl: String = DEFAULT_BASE_URL,
	/** 当前使用的凭据：密码或 session，二者等价。 */
	private var credential: String? = null
) {

	// ── 数据模型 ──────────────────────────────────────────────────────────────

	/** `GET /api/status` */
	data class Status(
		val online: Boolean,
		val selfId: String,
		val nickname: String,
		val avatar: String,
		val uptimeSeconds: Long,
		val version: String,
		val qqVersion: String,
		val platform: String,
		val canRestartQq: Boolean,
		val protocol: String,
		val msgSent: Long,
		val msgRecv: Long,
		/** PicoOnebot 自己报的内存占用（MB）—— 直接拿来当「轻量」的证据。 */
		val memoryMb: Long
	)

	/** `GET /api/qr` */
	data class QrInfo(
		/** ok / waiting_qr / expired / online */
		val status: QrStatus,
		val online: Boolean,
		val selfId: String,
		/** 二维码解码后的 URL（可用于外部渲染或日志） */
		val url: String,
		/** 原始 data URI：`data:image/png;base64,…`，可直接喂给 ImageView */
		val imageDataUri: String,
		val ageSeconds: Int,
		/** 剩余有效秒数，可直接做倒计时；非 ok 状态为 0 */
		val expiresIn: Int,
		val scanned: Boolean,
		val hasFragment: Boolean,
		val hasStateFragment: Boolean
	) {
		/** 把 data URI 解成 Bitmap，失败返回 null。 */
		fun decodeImage(): Bitmap? {
			if (imageDataUri.isEmpty()) return null
			val comma = imageDataUri.indexOf(',')
			if (comma < 0) return null
			return try {
				val bytes = Base64.decode(imageDataUri.substring(comma + 1), Base64.DEFAULT)
				BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
			} catch (t: Throwable) {
				null
			}
		}
	}

	enum class QrStatus {
		OK, WAITING_QR, EXPIRED, ONLINE, UNKNOWN;

		companion object {
			fun of(raw: String): QrStatus = when (raw) {
				"ok" -> OK
				"waiting_qr" -> WAITING_QR
				"expired" -> EXPIRED
				"online" -> ONLINE
				else -> UNKNOWN
			}
		}
	}

	/** `GET /api/connections` 的单项。 */
	data class Connection(
		val type: String,
		val raw: JSONObject
	)

	/** 协议端抛出的可读异常。 */
	class ApiException(
		val httpCode: Int,
		val messageText: String
	) : IOException("PicoOnebot API $httpCode: $messageText")

	/** 401：凭据失效，需要重新设置密码或重新登录。 */
	class UnauthorizedException(msg: String = "凭据失效") : IOException(msg)

	// ── 凭据 ──────────────────────────────────────────────────────────────────

	/** 换一个凭据（密码或 session）。 */
	fun setCredential(token: String?) {
		credential = token
	}

	fun hasCredential(): Boolean = !credential.isNullOrEmpty()

	/**
	 * 首次使用：设置 WebUI 密码。
	 *
	 * 这是**唯一**在「密码未初始化」阶段放行的接口，所以必须是引导流程的第一步。
	 * 成功后会自动把新密码设为当前凭据。
	 */
	suspend fun initializePassword(newPassword: String) {
		require(newPassword.isNotEmpty()) { "密码不能为空" }
		val body = JSONObject().put("token", newPassword)
		request("PUT", "/api/webui/token", body)
		credential = newPassword
	}

	/** 密码直接当凭据用；若服务器要求 session，这里会换成 session。 */
	suspend fun login(password: String): String {
		val body = JSONObject().put("token", password)
		val res = request("POST", "/api/auth/login", body)
		val session = res.optString("token").ifEmpty {
			res.optString("session").ifEmpty { password }
		}
		credential = session
		return session
	}

	/** 校验某个密码是否正确（不保留凭据）。 */
	suspend fun verifyPassword(password: String): Boolean = try {
		val res = request("POST", "/api/auth/login", JSONObject().put("token", password))
		res.optBoolean("ok", true)
	} catch (e: UnauthorizedException) {
		false
	}

	// ── 状态 / 日志 ───────────────────────────────────────────────────────────

	suspend fun status(): Status {
		val j = request("GET", "/api/status")
		return Status(
			online = j.optBoolean("online"),
			selfId = j.optString("selfId"),
			nickname = j.optString("nickname"),
			avatar = j.optString("avatar"),
			uptimeSeconds = j.optLong("uptime"),
			version = j.optString("version"),
			qqVersion = j.optString("qqVersion"),
			platform = j.optString("platform"),
			canRestartQq = j.optBoolean("canRestartQq"),
			protocol = j.optString("protocol"),
			msgSent = j.optLong("msgSent"),
			msgRecv = j.optLong("msgRecv"),
			memoryMb = j.optLong("memoryMb")
		)
	}

	suspend fun connections(): List<Connection> {
		val arr = requestArray("GET", "/api/connections")
		return buildList {
			for (i in 0 until arr.length()) {
				val o = arr.optJSONObject(i) ?: continue
				add(Connection(o.optString("type", "unknown"), o))
			}
		}
	}

	/** 拉日志；limit 服务器侧会夹到 1..4096。 */
	suspend fun logs(limit: Int = 200): List<String> {
		val arr = requestArray("GET", "/api/logs?limit=$limit")
		return buildList {
			for (i in 0 until arr.length()) add(arr.optString(i))
		}
	}

	// ── 登录二维码 ────────────────────────────────────────────────────────────

	/**
	 * 取二维码。
	 * @param forceRefresh true 会触发 `POST /api/qr/refresh`（最多阻塞 3s 等新码）；
	 *                     false 走只读 `GET /api/qr`，适合 2 秒一次的轮询。
	 */
	suspend fun qr(forceRefresh: Boolean = false): QrInfo {
		val j = if (forceRefresh) {
			request("POST", "/api/qr/refresh")
		} else {
			request("GET", "/api/qr")
		}
		val statusRaw = j.optString("status")
		return QrInfo(
			status = QrStatus.of(statusRaw),
			online = j.optBoolean("online"),
			selfId = if (j.has("user_id")) j.optLong("user_id").takeIf { it > 0 }?.toString()
				?: j.optString("selfId") else j.optString("selfId"),
			url = j.optString("url"),
			imageDataUri = j.optString("image"),
			ageSeconds = j.optInt("ageSeconds"),
			expiresIn = j.optInt("expiresIn"),
			scanned = j.optBoolean("scanned"),
			hasFragment = j.optBoolean("hasFragment"),
			hasStateFragment = j.optBoolean("hasStateFragment")
		)
	}

	/**
	 * 轮询直到登录成功或超时。适合「显示二维码 + 等用户扫」的页面。
	 *
	 * @param intervalMs 轮询间隔，默认 2000（与官方前端一致）
	 * @param timeoutMs  总超时，默认 5 分钟
	 * @param onQr       每次拿到二维码回调（主线程外，UI 更新请自行切线程）
	 * @return 登录成功后的 selfId，超时返回 null
	 */
	/**
	 * 轮询直到登录成功或超时。适合「显示二维码 + 等用户扫」的页面。
	 *
	 * @param intervalMs 轮询间隔，默认 2000（与官方前端一致）
	 * @param timeoutMs  总超时，默认 5 分钟
	 * @param onQr       每次拿到二维码回调（主线程外，UI 更新请自行切线程）
	 * @param onLoggedIn 登录成功那一刻回调（用于自动切回本应用）
	 * @return 登录成功后的 selfId，超时返回 null
	 */
	suspend fun awaitLogin(
		intervalMs: Long = 2_000,
		timeoutMs: Long = 5 * 60_000,
		onQr: ((QrInfo) -> Unit)? = null,
		onLoggedIn: ((String) -> Unit)? = null
	): String? {
		val deadline = System.currentTimeMillis() + timeoutMs
		var forceRefresh = true
		var lastScanned = false

		while (System.currentTimeMillis() < deadline) {
			val info = try {
				qr(forceRefresh)
			} catch (e: UnauthorizedException) {
				throw e
			} catch (e: IOException) {
				delay(intervalMs)
				continue
			}
			forceRefresh = false

			onQr?.invoke(info)

			if (info.online) {
				val id = info.selfId.ifEmpty { runCatching { status().selfId }.getOrDefault("") }
				onLoggedIn?.invoke(id)
				return id
			}

			// 扫码后变成「等待确认」时，提醒 UI
			if (info.scanned && !lastScanned) {
				lastScanned = true
			}

			if (info.status == QrStatus.EXPIRED) forceRefresh = true
			if (info.status == QrStatus.WAITING_QR) forceRefresh = true
			delay(intervalMs)
		}
		return null
	}

	// ── 配置 ──────────────────────────────────────────────────────────────────

	/** 读当前配置（读出来的密码类字段已被服务器抹掉）。 */
	suspend fun config(): JSONObject = request("GET", "/api/config")

	/**
	 * 写配置。只传要改的部分，服务器做 merge。
	 *
	 * 典型用法（把 OneBot 正向 WS 指到本地后端）：
	 * ```
	 * client.updateConfig(JSONObject().put("network", JSONObject()
	 *     .put("httpServers", JSONArray())
	 *     .put("websocketServers", JSONArray().put(JSONObject()
	 *         .put("name", "local")
	 *         .put("enable", true)
	 *         .put("host", "127.0.0.1")
	 *         .put("port", 3001)
	 *         .put("token", backendToken)))))
	 * ```
	 *
	 * @return 服务器回执原文（含 restart_required 等），**请如实展示给用户**
	 */
	suspend fun updateConfig(patch: JSONObject): JSONObject =
		request("PUT", "/api/config", patch)

	// ── 生命周期 ──────────────────────────────────────────────────────────────

	/** 重启协议端（内部会重启宿主 QQ），调用前建议提示用户会短暂掉线。 */
	suspend fun restart(): JSONObject = request("POST", "/api/restart")

	/** 退出 QQ 账号登录（保留协议端进程）。 */
	suspend fun logoutAccount(): JSONObject = request("POST", "/api/account/logout")

	/** 退出 WebUI 会话（不影响 QQ）。 */
	suspend fun logoutWebUi(): JSONObject = request("POST", "/api/logout")

	// ── 健康检查 ──────────────────────────────────────────────────────────────

	/** 控制台是否可达（不要求凭据正确）。 */
	suspend fun ping(timeoutMs: Int = 1_500): Boolean = withContext(Dispatchers.IO) {
		try {
			val conn = open("GET", "/api/status", timeoutMs)
			val code = conn.responseCode
			conn.disconnect()
			code in 200..499  // 401 也算「活着」
		} catch (t: Throwable) {
			false
		}
	}

	/** 等待控制台起来（刚启动 PicoOnebot 时用）。 */
	suspend fun awaitReady(
		timeoutMs: Long = 60_000,
		intervalMs: Long = 1_000
	): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (ping()) return true
			delay(intervalMs)
		}
		return false
	}

	// ── HTTP 基础 ─────────────────────────────────────────────────────────────

	private suspend fun request(
		method: String,
		path: String,
		body: JSONObject? = null
	): JSONObject = withContext(Dispatchers.IO) {
		exec(method, path, body?.toString()) { text ->
			JSONObject(text.ifEmpty { "{}" })
		}
	}

	private suspend fun requestArray(
		method: String,
		path: String
	): JSONArray = withContext(Dispatchers.IO) {
		exec(method, path, null) { text ->
			JSONArray(text.ifEmpty { "[]" })
		}
	}

	private fun <T> exec(
		method: String,
		path: String,
		body: String?,
		parse: (String) -> T
	): T {
		val conn = open(method, path)
		try {
			if (body != null) {
				conn.doOutput = true
				conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
				conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			}
			val code = conn.responseCode
			val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
				?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
				?: ""

			when {
				code in 200..299 -> return parse(text)
				code == 401 -> throw UnauthorizedException(text.ifEmpty { "密码不正确或会话已失效" })
				else -> throw ApiException(code, text.ifEmpty { "HTTP $code" })
			}
		} finally {
			conn.disconnect()
		}
	}

	private fun open(method: String, path: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): HttpURLConnection {
		val url = URL(baseUrl.trimEnd('/') + path)
		return (url.openConnection() as HttpURLConnection).apply {
			requestMethod = method
			connectTimeout = timeoutMs
			readTimeout = timeoutMs
			instanceFollowRedirects = true
			setRequestProperty("Accept", "application/json")
			credential?.takeIf { it.isNotEmpty() }?.let {
				setRequestProperty("Authorization", "Bearer $it")
			}
		}
	}

	/**
	 * 探测控制台是否处于「必须先修改密码」状态（全新安装）。
	 *
	 * 实测的服务端行为（PicoOnebot 0.16 / 0.17）：
	 *   无认证        → HTTP 401 {"ok":false,"message":"unauthorized"}
	 *   默认密码 picopico → HTTP 428 {"ok":false,"mustChangePassword":true,...}
	 *
	 * 所以**必须带上默认密码**去探，带无认证只会拿到 401。
	 *
	 * 为何不能用 config()：/config 在 mustChangePassword 状态下也是 428，
	 * 抛异常后取 getOrDefault(false) 会把「需要初始化」误判成「不需要」。
	 */
	suspend fun needsPasswordInit(timeoutMs: Int = 3_000): Boolean = withContext(Dispatchers.IO) {
		try {
			// 不走 open()（它会带上当前 credential 覆盖我们要测的头）
			val url = java.net.URL(baseUrl.trimEnd('/') + "/api/status")
			val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
				requestMethod = "GET"
				connectTimeout = timeoutMs
				readTimeout = timeoutMs
				instanceFollowRedirects = true
				setRequestProperty("Accept", "application/json")
				setRequestProperty("Authorization", "Bearer $INITIAL_PASSWORD")
			}
			val code = conn.responseCode
			val body = runCatching {
				val stream = if (code in 200..299) conn.inputStream else conn.errorStream
				stream?.bufferedReader()?.use { it.readText() }.orEmpty()
			}.getOrDefault("")
			conn.disconnect()

			if (body.isBlank()) return@withContext code == 428

			val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
				?: return@withContext false

			// 428 + mustChangePassword 是权威信号
			if (code == 428) return@withContext true
			obj.optBoolean("mustChangePassword", false)
		} catch (t: Throwable) {
			false
		}
	}

	companion object {
		const val DEFAULT_BASE_URL = "http://127.0.0.1:6099"
		const val DEFAULT_TIMEOUT_MS = 15_000

		/** 官方默认初始密码。 */
		const val INITIAL_PASSWORD = "picopico"
	}
}

