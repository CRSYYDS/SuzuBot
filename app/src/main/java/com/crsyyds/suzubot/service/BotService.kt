package com.crsyyds.suzubot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.crsyyds.suzubot.backend.BotRuntime
import com.crsyyds.suzubot.pico.PicoOnebotClient
import com.crsyyds.suzubot.setup.SetupFlow
import com.crsyyds.suzubot.ui.home.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：让机器人在后台持续运行，并兼任 PicoOnebot 的看护。
 *
 * 为什么必须有它：
 *  - Android 8+ 后台进程会被限流/冻结，没有前台服务消息就收不到
 *  - ColorOS/MIUI 这类 ROM 更激进，实测 PicoOnebot 进程会被杀到只剩 MSF 子进程
 *
 * 它做三件事：
 *  1. 常驻通知，避免被系统回收
 *  2. 每 15 秒探测 6099；连续失败则尝试拉起 PicoOnebot
 *  3. 探测 3001 是否还在监听；不在就自动用 API 重新下发配置
 *     （PicoOnebot 重启后网络配置不一定会保留，实测会丢）
 */
class BotService : Service() {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var watchdog: Job? = null

	override fun onCreate() {
		super.onCreate()
		createChannel()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		startForeground(NOTIF_ID, buildNotification("正在启动…"))
		if (watchdog?.isActive != true) {
			watchdog = scope.launch { watchLoop() }
		}
		return START_STICKY
	}

	override fun onDestroy() {
		watchdog?.cancel()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	// ── 看护循环 ──────────────────────────────────────────────────────────────

	private suspend fun watchLoop() {
		val flow = SetupFlow(this)
		val client = PicoOnebotClient()

		// 凭据自愈：重装 App 会丢密码，但 PicoOnebot 里的密码还在。
		// 用候选密码逐个试，通了就写回去。
		val recovered = recoverCredential(client)
		if (recovered != null) {
			flow.webuiPassword = recovered
			Log.i(TAG, "webui credential recovered")
		} else {
			client.setCredential(flow.webuiPassword ?: PicoOnebotClient.INITIAL_PASSWORD)
		}
		client.setCredential(recovered ?: flow.webuiPassword ?: PicoOnebotClient.INITIAL_PASSWORD)
		var failCount = 0
		var lastRestartAt = 0L
		var lastConfigFixAt = 0L

		while (scope.isActive) {
			// 用端口探测判活：密码不对也不影响判断进程是否存活
			val consoleAlive = portAlive(CONSOLE_PORT)

			if (consoleAlive) {
				failCount = 0

				// 控制台活着，但 OneBot 端口可能没开（PicoOnebot 重启后配置会丢）
				if (!portAlive(ONEBOT_PORT)) {
					val now = System.currentTimeMillis()
					// 30 秒内最多修一次，避免狂刷 API
					if (now - lastConfigFixAt > 30_000) {
						lastConfigFixAt = now
						val ok = ensureOneBotServer(client)
						Log.i(TAG, "re-apply OneBot ws config: $ok")
						if (!ok) {
							// 常见原因：控制台密码与我们记录的不一致
							Log.w(TAG, "config failed; 密码可能不匹配，请在主页重新配置")
						}
					}
				}
				updateNotification(botSummary())
			} else {
				failCount++
				updateNotification("协议端未响应（第 $failCount 次）")
				// 连续 3 次（约 45 秒）判定为挂了，尝试拉起；10 分钟内最多拉一次
				if (failCount >= 3) {
					val now = System.currentTimeMillis()
					if (now - lastRestartAt > 10 * 60_000) {
						lastRestartAt = now
						Log.w(TAG, "协议端似乎已被杀，通知用户回 App 重启")
						// 不主动拉起（避免把 PicoOnebot 界面带到前台），
						// 只更新通知让用户知道。
						updateNotification("协议端已停止，点开 App 重新启动")
						failCount = 0
					}
				}
			}

			delay(15_000)
		}
	}

	/** 用控制台 API 把 127.0.0.1:3001 的 WS Server 配回去。 */
	private suspend fun ensureOneBotServer(client: PicoOnebotClient): Boolean {
		return try {
			val ws = org.json.JSONObject()
				.put("name", "local-backend")
				.put("enable", true)
				.put("host", "127.0.0.1")
				.put("port", ONEBOT_PORT)
				.put("messagePostFormat", "array")
				.put("reportSelfMessage", false)
				.put("debug", false)

			val network = org.json.JSONObject()
				.put("wsServers", org.json.JSONArray().put(ws))
				.put("wsClients", org.json.JSONArray())
				.put("httpServers", org.json.JSONArray())
				.put("httpClients", org.json.JSONArray())

			val reply = client.updateConfig(org.json.JSONObject().put("network", network))
			// 服务器回执原样记下来，方便排查
			Log.i(TAG, "config reply: $reply")
			reply.optBoolean("ok", false)
		} catch (t: Throwable) {
			Log.w(TAG, "ensureOneBotServer failed", t)
			false
		}
	}


	/**
	 * 尝试恢复控制台密码。
	 *
	 * 候选来源：
	 *  1. prefs 里已存的
	 *  2. 默认 picopico
	 *  3. 上次成功过的（存在 prefs 的 last_good_webui_password）
	 */
	private suspend fun recoverCredential(client: PicoOnebotClient): String? {
		val prefs = getSharedPreferences("setup", MODE_PRIVATE)
		val candidates = linkedSetOf<String>()
		prefs.getString("webui_password", null)?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
		prefs.getString("last_good_webui_password", null)?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
		candidates.add(PicoOnebotClient.INITIAL_PASSWORD)

		for (pwd in candidates) {
			client.setCredential(pwd)
			val ok = runCatching { client.status() }.isSuccess
			if (ok) {
				prefs.edit()
					.putString("webui_password", pwd)
					.putString("last_good_webui_password", pwd)
					.apply()
				return pwd
			}
		}
		return null
	}

	private fun botSummary(): String {
		val engine = BotRuntime.currentOrNull() ?: return "协议端在线 · 后端未启动"
		return when {
			!engine.connected -> "协议端在线 · 后端重连中"
			else -> "运行中 · 处理 ${engine.handledCount} 条"
		}
	}

	private fun portAlive(port: Int): Boolean = try {
		java.net.Socket().use { s ->
			s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
			true
		}
	} catch (t: Throwable) {
		false
	}

	/**
	 * 静默拉起协议端。
	 *
	 * 重要：不能把 PicoOnebot 的界面留在前台（主人明确要求）。
	 * 所以：发启动 Intent 后立刻把本应用的 Activity 拉回来。
	 */
	private fun launchPico() {
		runCatching {
			val intent = packageManager.getLaunchIntentForPackage(SetupFlow.Requirements.PACKAGE)
				?: return@runCatching
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(intent)

			// 1.2 秒后切回自己（服务已经在跑了，界面不需要）
			Thread {
				try {
					Thread.sleep(1_200)
					val home = Intent(this, HomeActivity::class.java).apply {
						addFlags(
							Intent.FLAG_ACTIVITY_NEW_TASK or
								Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
								Intent.FLAG_ACTIVITY_SINGLE_TOP
						)
					}
					startActivity(home)
				} catch (t: Throwable) {
					Log.w(TAG, "return to app failed", t)
				}
			}.apply { isDaemon = true }.start()
		}.onFailure { Log.w(TAG, "launch pico failed", it) }
	}

	// ── 通知 ──────────────────────────────────────────────────────────────────

	private fun createChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val ch = NotificationChannel(
				CHANNEL_ID, "机器人运行状态",
				NotificationManager.IMPORTANCE_LOW
			).apply {
				description = "保持机器人在后台运行"
				setShowBadge(false)
			}
			getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
		}
	}

	private fun buildNotification(text: String): Notification {
		val open = PendingIntent.getActivity(
			this, 0,
			Intent(this, HomeActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_notify_chat)
			.setContentTitle("SuzuBot")
			.setContentText(text)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setContentIntent(open)
			.build()
	}

	private fun updateNotification(text: String) {
		runCatching {
			getSystemService(NotificationManager::class.java)
				?.notify(NOTIF_ID, buildNotification(text))
		}
	}

	companion object {
		private const val TAG = "BotService"
		private const val CHANNEL_ID = "bot_running"
		private const val NOTIF_ID = 1001
		private const val ONEBOT_PORT = 3001
		private const val CONSOLE_PORT = 6099

		fun start(ctx: Context) {
			val i = Intent(ctx, BotService::class.java)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				ctx.startForegroundService(i)
			} else {
				ctx.startService(i)
			}
		}

		fun stop(ctx: Context) {
			ctx.stopService(Intent(ctx, BotService::class.java))
		}
	}
}
