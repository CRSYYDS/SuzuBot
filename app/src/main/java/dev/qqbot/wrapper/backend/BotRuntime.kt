package dev.qqbot.wrapper.backend

import android.app.Application
import dev.qqbot.wrapper.setup.SetupFlow

/**
 * 机器人运行时的单例持有者。
 *
 * 让 ViewModel / 前台服务都能拿到同一个 BotEngine 实例，
 * 避免重复建连接（OneBot 的 WS 允许多连接，但没必要）。
 */
object BotRuntime {

	private var engine: BotEngine? = null

	@Synchronized
	fun get(app: Application): BotEngine {
		val cfg = loadConfig(app)
		val fingerprint = fingerprintOf(cfg)
		val existing = engine
		if (existing != null && existing.configSnapshot() == fingerprint) return existing

		existing?.stop()
		return BotEngine(app, cfg).also {
			engine = it
			it.start()
		}
	}

	private fun fingerprintOf(cfg: BotEngine.BotConfig): String =
		"${cfg.apiKey}|${cfg.baseUrl}|${cfg.model}|${cfg.systemPrompt.hashCode()}"

	@Synchronized
	fun currentOrNull(): BotEngine? = engine

	@Synchronized
	fun stop() {
		engine?.stop()
		engine = null
	}

	private fun loadConfig(app: Application): BotEngine.BotConfig {
		val flow = SetupFlow(app)
		// 模型配置已迁到 ModelChainManager，这里只带人设
		return BotEngine.BotConfig(
			systemPrompt = flow.persona?.takeIf { it.isNotBlank() } ?: BotEngine.DEFAULT_PERSONA
		)
	}
}
