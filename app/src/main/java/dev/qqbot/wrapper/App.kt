package dev.qqbot.wrapper

import android.app.Application

/**
 * 全局入口。
 *
 * 目前只做一件事：把未捕获异常落到文件里，方便主人真机实测时把日志发回来。
 * 老机型（Android 7/9）上崩溃现场比堆栈文字更值钱。
 */
class App : Application() {

	override fun onCreate() {
		super.onCreate()
		applyDebugConfig()
		installCrashHandler()
		// 引导流程已完成就把保活服务拉起来
		if (dev.qqbot.wrapper.setup.SetupFlow(this).isCompleted()) {
			runCatching { dev.qqbot.wrapper.service.BotService.start(this) }
		}
	}


	/**
	 * 调试用：若存在 /data/local/tmp/qqbot-config.txt，则把其中的 key=value 写进 prefs。
	 * 只在 debug 包生效，便于用 adb 免手输配置；正式包请用 UI 填写。
	 */
	private fun applyDebugConfig() {
		if (!BuildConfig.DEBUG) return
		val f = java.io.File("/data/local/tmp/qqbot-config.txt")
		if (!f.exists()) return
		runCatching {
			val map = f.readLines()
				.mapNotNull { line ->
					val i = line.indexOf('=')
					if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
				}
				.toMap()
			if (map.isEmpty()) return@runCatching
			getSharedPreferences("setup", MODE_PRIVATE).edit().apply {
				map["api_key"]?.let { putString("api_key", it) }
				map["api_base"]?.let { putString("api_base", it) }
				map["api_model"]?.let { putString("api_model", it) }
				map["webui_password"]?.let { putString("webui_password", it) }
				map["backend_token"]?.let { putString("backend_token", it) }
				apply()
			}
			android.util.Log.i("App", "debug config applied: ${map.keys}")
		}.onFailure { android.util.Log.w("App", "debug config failed", it) }
	}

	private fun installCrashHandler() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			runCatching {
				val dir = filesDir.resolve("crash").apply { mkdirs() }
				val file = dir.resolve("crash-${System.currentTimeMillis()}.txt")
				file.writeText(
					buildString {
						appendLine("time: ${java.util.Date()}")
						appendLine("thread: ${thread.name}")
						appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
						appendLine("sdk: ${android.os.Build.VERSION.SDK_INT}")
						appendLine("abis: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
						appendLine("---")
						appendLine(android.util.Log.getStackTraceString(throwable))
					}
				)
			}
			previous?.uncaughtException(thread, throwable)
		}
	}
}
