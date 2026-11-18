package com.crsyyds.suzubot.pico

import android.content.Context
import android.content.SharedPreferences

/**
 * 控制台密码的单一可信来源。
 *
 * 为什么需要它：
 * 之前密码存在 SetupFlow 的 prefs 里，但 SetupFlow 每次都是 `new SetupFlow(context)`，
 * 新建实例读到的可能是过期缓存；HomeViewModel 又各自持有一份，两者不同步，
 * 导致「明明登录了却显示密码不匹配」。
 *
 * 这里把密码收敛到一个全局对象：内存缓存 + 立即落盘，任何地方读到的都是最新值。
 */
object CredentialStore {

	private const val PREFS = "credentials"
	private const val KEY_WEBUI = "webui_password"
	private const val KEY_LAST_GOOD = "last_good_webui_password"
	private const val KEY_BACKEND_TOKEN = "backend_token"

	@Volatile private var cachedWebUi: String? = null
	private var prefs: SharedPreferences? = null

	fun init(context: Context) {
		if (prefs == null) {
			prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			cachedWebUi = prefs?.getString(KEY_WEBUI, null)
		}
	}

	/** 当前控制台密码（可能为 null）。 */
	val webUiPassword: String?
		get() = cachedWebUi ?: prefs?.getString(KEY_WEBUI, null)

	/** 最近一次验证成功的密码。 */
	val lastGoodPassword: String?
		get() = prefs?.getString(KEY_LAST_GOOD, null)

	/** 写入密码（同时更新内存缓存与磁盘）。 */
	fun setWebUiPassword(pwd: String) {
		cachedWebUi = pwd
		prefs?.edit()?.putString(KEY_WEBUI, pwd)?.apply()
	}

	/** 记录一个验证成功的密码。 */
	fun markGood(pwd: String) {
		setWebUiPassword(pwd)
		prefs?.edit()?.putString(KEY_LAST_GOOD, pwd)?.apply()
	}

	fun clear() {
		cachedWebUi = null
		prefs?.edit()?.clear()?.apply()
	}

	var backendToken: String?
		get() = prefs?.getString(KEY_BACKEND_TOKEN, null)
		set(value) {
			prefs?.edit()?.putString(KEY_BACKEND_TOKEN, value)?.apply()
		}

	/**
	 * 按优先级列出可尝试的密码：当前 → 最近可用 → 默认。
	 * 已去重，空值被过滤。
	 */
	fun candidates(): List<String> = buildList {
		webUiPassword?.takeIf { it.isNotBlank() }?.let { add(it) }
		lastGoodPassword?.takeIf { it.isNotBlank() }?.let { add(it) }
		add(PicoOnebotClient.INITIAL_PASSWORD)
	}.distinct()
}
