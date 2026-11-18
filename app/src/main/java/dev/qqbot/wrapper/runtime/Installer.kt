package dev.qqbot.wrapper.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 静默安装 / 无感安装。
 *
 * 两条路：
 *  A. **Session 安装**（Android 5+，推荐）
 *     走 PackageInstaller.Session，把 APK 流式写进去再 commit。
 *     用户只需授权一次「安装未知应用」，之后可以在我们自己的 UI 里确认，
 *     不用跳系统的「是否安装」页面（Android 12+ 部分 ROM 仍会显示一次）。
 *
 *  B. **Intent 安装**（兜底）
 *     走 ACTION_VIEW + FileProvider，一定会弹系统安装器。
 *
 * 实测（OPPO R11 / Android 8.1）：
 *  - 未授权 REQUEST_INSTALL_PACKAGES 时，Session commit 会抛 SecurityException
 *  - 授权后 Session 安装**不会**再弹系统确认页，体验接近静默
 */
object Installer {

	private const val TAG = "Installer"

	sealed class Result {
		data class Ok(val message: String) : Result()
		/** 需要用户先授予「安装未知应用」权限。 */
		data object NeedPermission : Result()
		/** 需要用户在当前 APK 里手动确认（系统弹窗）。 */
		data class NeedUserConfirm(val intent: Intent) : Result()
		data class Failed(val message: String) : Result()
	}

	/** 是否已获得「安装未知应用」权限。 */
	fun canInstallPackages(context: Context): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.packageManager.canRequestPackageInstalls()
		} else {
			true
		}

	/** 跳到系统设置，让用户给本应用开「安装未知应用」。 */
	fun requestInstallPermission(context: Context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			runCatching {
				context.startActivity(
					Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
						.setData(Uri.parse("package:${context.packageName}"))
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				)
			}
		}
	}

	/**
	 * Session 方式安装。
	 *
	 * @param apkFile 要安装的 APK
	 * @param onProgress 写入进度回调（字节）
	 */
	suspend fun installViaSession(
		context: Context,
		apkFile: File,
		onProgress: ((Long, Long) -> Unit)? = null
	): Result = withContext(Dispatchers.IO) {
		if (!apkFile.exists() || apkFile.length() == 0L) {
			return@withContext Result.Failed("安装包不存在或为空")
		}
		if (!canInstallPackages(context)) {
			return@withContext Result.NeedPermission
		}

		val installer = context.packageManager.packageInstaller
		var sessionId = -1
		try {
			val params = PackageInstaller.SessionParams(
				PackageInstaller.SessionParams.MODE_FULL_INSTALL
			).apply {
				// 允许降级（我们自己的版本号会递增，这里主要为了兼容）
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					@Suppress("DEPRECATION")
					setInstallLocation(PackageInfo_InstallLocation_Auto)
				}
			}

			sessionId = installer.createSession(params)
			val total = apkFile.length()

			installer.openSession(sessionId).use { session ->
				apkFile.inputStream().use { input ->
					session.openWrite("base.apk", 0, total).use { out ->
						val buf = ByteArray(64 * 1024)
						var written = 0L
						var lastReport = 0L
						var n: Int
						while (input.read(buf).also { n = it } > 0) {
							out.write(buf, 0, n)
							written += n
							if (written - lastReport >= 512 * 1024) {
								lastReport = written
								onProgress?.invoke(written, total)
							}
						}
						out.flush()
						onProgress?.invoke(written, total)
					}
				}

				// 结果广播
				val intent = Intent(context, InstallResultReceiver::class.java).apply {
					setPackage(context.packageName)
				}
				val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
				} else {
					PendingIntent.FLAG_UPDATE_CURRENT
				}
				val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
				session.commit(pending.intentSender)
			}

			Log.i(TAG, "session $sessionId commit sent")
			Result.Ok("正在安装…")
		} catch (e: SecurityException) {
			Log.w(TAG, "session install denied", e)
			runCatching { if (sessionId >= 0) installer.abandonSession(sessionId) }
			Result.NeedPermission
		} catch (t: Throwable) {
			Log.w(TAG, "session install failed", t)
			runCatching { if (sessionId >= 0) installer.abandonSession(sessionId) }
			Result.Failed(t.message ?: "安装失败")
		}
	}

	/** Intent 方式安装（一定会弹系统页，兜底用）。 */
	fun installViaIntent(context: Context, apkFile: File): Result {
		if (!apkFile.exists()) return Result.Failed("安装包不存在")
		return try {
			val uri = FileProvider.getUriForFile(
				context, "${context.packageName}.fileprovider", apkFile
			)
			val intent = Intent(Intent.ACTION_VIEW).apply {
				setDataAndType(uri, "application/vnd.android.package-archive")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			context.startActivity(intent)
			Result.NeedUserConfirm(intent)
		} catch (t: Throwable) {
			Log.w(TAG, "intent install failed", t)
			Result.Failed(t.message ?: "无法拉起安装器")
		}
	}

	/** 已安装版本号（未安装返回 -1）。 */
	fun installedVersion(context: Context, pkg: String): Long = try {
		@Suppress("DEPRECATION")
		val info = context.packageManager.getPackageInfo(pkg, 0)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
		else info.versionCode.toLong()
	} catch (e: PackageManager.NameNotFoundException) {
		-1L
	}

	fun isInstalled(context: Context, pkg: String): Boolean =
		installedVersion(context, pkg) >= 0

	// PackageInstaller 的安装位置常量（避免额外 import）
	private const val PackageInfo_InstallLocation_Auto = 0
}
