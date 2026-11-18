package com.crsyyds.suzubot.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 接收 PackageInstaller 的安装结果。
 *
 * 系统会往这个 receiver 发一个带 EXTRA_STATUS 的广播：
 *  STATUS_SUCCESS  → 装好了
 *  STATUS_FAILURE  → 装失败（EXTRA_STATUS_MESSAGE 有原因）
 *  STATUS_PENDING_USER_ACTION → 需要用户确认（附一个 Intent）
 */
class InstallResultReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		val status = intent.getIntExtra(
			PackageInstaller.EXTRA_STATUS,
			PackageInstaller.STATUS_FAILURE
		)
		val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
		val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()

		Log.i(TAG, "install status=$status pkg=$pkg msg=$message")

		when (status) {
			PackageInstaller.STATUS_SUCCESS -> {
				Events.emit(Event.Success(pkg))
			}

			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				// 需要在当前 APK 的上下文里弹安装确认
				@Suppress("DEPRECATION")
				val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
				if (confirm != null) {
					confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					runCatching { context.startActivity(confirm) }
						.onFailure { Events.emit(Event.Failed(it.message ?: "无法拉起确认界面")) }
				} else {
					Events.emit(Event.Failed("缺少确认 Intent"))
				}
			}

			else -> {
				Events.emit(Event.Failed(message.ifEmpty { "安装失败（status=$status）" }))
			}
		}
	}

	/** 安装事件通道，给 UI 订阅。 */
	sealed class Event {
		data class Success(val pkg: String) : Event()
		data class Failed(val message: String) : Event()
	}

	object Events {
		private val _flow = MutableSharedFlow<Event>(extraBufferCapacity = 4)
		val flow: SharedFlow<Event> = _flow.asSharedFlow()

		internal fun emit(e: Event) {
			runCatching { _flow.tryEmit(e) }
		}
	}

	companion object {
		private const val TAG = "InstallResultReceiver"
	}
}
