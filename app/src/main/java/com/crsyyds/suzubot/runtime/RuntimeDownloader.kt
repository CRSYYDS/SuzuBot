package com.crsyyds.suzubot.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 运行时组件的下载 + 校验。
 *
 * 设计原则（对应项目定位「只做框架」）：
 *  - 不在 APK 内分发任何第三方二进制（NapCat/PicoOnebot/手表QQ 均由用户设备自行下载）
 *  - 每个文件都必须过 SHA256 校验，清单里 sha256 为 null 的条目不受信任但允许「用户自备」
 *  - 多源回落 + 断点续传 + 失败可降级到「用户手动选择本地文件」
 */
class RuntimeDownloader(
	private val context: Context,
	private val manifest: JSONObject
) {

	/** 单个组件的下载任务描述。 */
	data class Component(
		val id: String,          // "host" / "protocol"
		val version: String,
		val fileName: String,
		val sha256: String?,     // null = 未验证版本，只允许本地文件
		val sizeBytes: Long?,
		val sources: List<String>,
		val packageName: String?,
		/** 随 App 打包在 assets 里的路径；非 null 时优先用它，免联网 */
		val bundledAsset: String? = null
	)

	sealed class Result {
		data class Ok(val file: File) : Result()
		/** 未在清单中或清单里无 sha256 —— 允许用户手动提供，但要警告。 */
		data class NeedsUserFile(val reason: String) : Result()
		data class Failed(val message: String, val cause: Throwable? = null) : Result()
	}

	/** 从清单里取某个组件的描述。 */
	fun component(kind: String, version: String? = null): Component? {
		val node = manifest.optJSONObject(kind) ?: return null
		val variants = node.optJSONArray("variants") ?: return null
		var picked: JSONObject? = null
		for (i in 0 until variants.length()) {
			val v = variants.getJSONObject(i)
			if (version == null || v.optString("version") == version) { picked = v; break }
		}
		picked ?: return null

		val sources = mutableListOf<String>()
		picked.optJSONArray("sources")?.let { arr ->
			for (i in 0 until arr.length()) {
				val s = arr.getJSONObject(i).optString("url")
				if (s.isNotEmpty()) sources += s
			}
		}

		val ver = picked.optString("version")
		val pkg = node.optString("packageName").ifEmpty { null }
		val ext = "apk"

		// 内置副本：清单里写了 bundled.assetPath 就走 assets
		val bundled = picked.optJSONObject("bundled")?.optString("assetPath")
			?.takeIf { it.isNotBlank() }

		return Component(
			id = kind,
			version = ver,
			fileName = "${kind}-${ver}.$ext",
			sha256 = picked.optString("sha256").ifEmpty { null },
			sizeBytes = if (picked.has("sizeBytes") && !picked.isNull("sizeBytes")) picked.optLong("sizeBytes") else null,
			sources = sources,
			packageName = pkg,
			bundledAsset = bundled
		)
	}

	/**
	 * 确保本地已有该组件。
	 * @param acceptUnverified 允许下载清单里没写 sha256 的条目（默认 false，让调用方显式决定）
	 */
	fun ensure(
		c: Component,
		acceptUnverified: Boolean = false,
		/** 下载进度回调（已下载字节, 总字节或 null）。切线程由调用方负责。 */
		onProgress: ((downloaded: Long, total: Long?) -> Unit)? = null
	): Result {
		val dir = componentDir(c.id)
		val target = File(dir, c.fileName)

		if (target.exists() && target.length() > 0) {
			when (val v = verify(target, c.sha256)) {
				is Verify.Ok -> return Result.Ok(target)
				is Verify.Mismatch -> target.delete()  // 损坏就重下，不将就
			}
		}

		// ── 优先使用随 App 打包的内置副本（免联网，国内环境友好）──
		if (c.bundledAsset != null) {
			dir.mkdirs()
			val copied = copyFromAssets(c.bundledAsset, target, c.sizeBytes, onProgress)
			if (copied) {
				val v = verify(target, c.sha256)
				if (v is Verify.Ok) return Result.Ok(target)
				target.delete()
			}
			// 内置副本损坏/缺失时继续走下载路径
		}

		if (c.sha256 == null && !acceptUnverified) {
			return Result.NeedsUserFile("该版本未提供 SHA256 校验值，需要用户自行选择安装包")
		}
		if (c.sources.isEmpty()) {
			return Result.NeedsUserFile("清单中该版本没有可用的下载源，需要用户自行提供")
		}

		dir.mkdirs()
		val tmp = File(dir, "${c.fileName}.part")
		val mirrors = manifest.optJSONArray("mirrors")?.let { arr ->
			buildList {
				for (i in 0 until arr.length()) add(arr.getString(i))
			}
		} ?: emptyList()

		var lastError: Throwable? = null
		for (src in c.sources) {
			val attempts = listOf(src) + mirrors.map { it.replace("{url}", src) }
			for (url in attempts) {
				try {
					download(url, tmp, c.sizeBytes, onProgress)
					when (val v = verify(tmp, c.sha256)) {
						is Verify.Ok -> {
							if (target.exists()) target.delete()
							if (!tmp.renameTo(target)) {
								tmp.copyTo(target, overwrite = true)
								tmp.delete()
							}
							return Result.Ok(target)
						}
						is Verify.Mismatch -> {
							tmp.delete()
							lastError = IOException("SHA256 不匹配：期望 ${c.sha256}，得到 ${v.actual}")
						}
					}
				} catch (e: Throwable) {
					lastError = e
					// 单个源失败继续试下一个，不中断
				}
			}
		}
		return Result.Failed("所有下载源均失败", lastError)
	}

	/**
	 * 把 assets 里的 APK 复制到目标路径。
	 *
	 * 为什么用流复制而不是直接读进内存：PicoOnebot 有 50 MB，
	 * 低端机（3 GB 内存）一次性 byte[] 容易 OOM。
	 *
	 * @return true = 复制完成（还需调用方做 SHA256 校验）
	 */
	private fun copyFromAssets(
		assetPath: String,
		target: File,
		expectSize: Long?,
		onProgress: ((Long, Long?) -> Unit)? = null
	): Boolean {
		return try {
			val fdLen: Long = runCatching { context.assets.openFd(assetPath).use { it.length } }
				.getOrDefault(0L)
			val total: Long? = if (fdLen > 0) fdLen else expectSize

			context.assets.open(assetPath).use { input ->
				target.outputStream().use { output ->
					val buf = ByteArray(64 * 1024)
					var written = 0L
					var lastReport = 0L
					while (true) {
						val n = input.read(buf)
						if (n < 0) break
						output.write(buf, 0, n)
						written += n
						if (written - lastReport >= 1_000_000L) {
							lastReport = written
							onProgress?.invoke(written, total)
						}
					}
					output.flush()
					onProgress?.invoke(written, total)
				}
			}
			true
		} catch (e: Throwable) {
			target.delete()
			false
		}
	}

	/** 断点续传下载（服务端支持 Accept-Ranges 时生效）。 */
	private fun download(
		url: String,
		out: File,
		expectSize: Long?,
		onProgress: ((Long, Long?) -> Unit)? = null
	) {
		val existing = if (out.exists()) out.length() else 0L
		val conn = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 20_000
			readTimeout = 60_000
			instanceFollowRedirects = true
			if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
		}
		try {
			val code = conn.responseCode
			if (code !in 200..299) throw IOException("HTTP $code for $url")

			val append = code == 206 && existing > 0
			if (!append && out.exists()) out.delete()

			// 206 时服务端给的是剩余长度，总长 = 已下载 + 剩余
			// contentLengthLong 是 API 24+，改用 contentLength（协议端 APK < 2GB 够用）
			val serverLen = conn.contentLength.toLong().takeIf { it > 0 }
			val total = when {
				serverLen == null -> expectSize
				append -> existing + serverLen
				else -> serverLen
			}

			conn.inputStream.use { input ->
				out.appendBytes(input, append, existing, total, onProgress)
			}
			if (expectSize != null && out.length() != expectSize) {
				throw IOException("大小不符：期望 $expectSize，实际 ${out.length()}")
			}
		} finally {
			conn.disconnect()
		}
	}

	private fun File.appendBytes(
		input: InputStream,
		append: Boolean,
		startAt: Long = 0L,
		total: Long? = null,
		onProgress: ((Long, Long?) -> Unit)? = null
	) {
		java.io.FileOutputStream(this, append).use { fos ->
			val buf = ByteArray(64 * 1024)
			var n: Int
			var done = startAt
			var lastReport = 0L
			while (input.read(buf).also { n = it } > 0) {
				fos.write(buf, 0, n)
				done += n
				// 每 256KB 报一次，避免主线程被进度回调刷爆
				if (onProgress != null && done - lastReport >= 256 * 1024) {
					lastReport = done
					onProgress(done, total)
				}
			}
			fos.flush()
			onProgress?.invoke(done, total)
		}
	}

	private sealed class Verify {
		object Ok : Verify()
		data class Mismatch(val actual: String) : Verify()
	}

	private fun verify(f: File, expected: String?): Verify {
		if (expected == null) return Verify.Ok
		val actual = sha256(f)
		return if (actual.equals(expected, ignoreCase = true)) Verify.Ok else Verify.Mismatch(actual)
	}

	private fun sha256(f: File): String {
		val md = MessageDigest.getInstance("SHA-256")
		f.inputStream().use { input ->
			val buf = ByteArray(256 * 1024)
			var n: Int
			while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
		}
		return md.digest().joinToString("") { "%02x".format(it) }
	}

	/** 用户手动选择的 APK：校验包名（SHA256 可空）。 */
	fun inspectUserApk(file: File): Pair<String?, Int>? {
		val pm = context.packageManager
		val info = pm.getPackageArchiveInfo(
			file.absolutePath,
			PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
		) ?: return null
		// ApplicationInfo.minSdkVersion 是 API 24+，低版本用反射兜底，拿不到就返回 0（不拦）
		val minSdk = if (android.os.Build.VERSION.SDK_INT >= 24) {
			info.applicationInfo?.minSdkVersion ?: 0
		} else {
			runCatching {
				android.content.pm.ApplicationInfo::class.java
					.getField("minSdkVersion")
					.getInt(info.applicationInfo)
			}.getOrDefault(0)
		}
		return info.packageName to minSdk
	}

	fun componentDir(id: String): File = File(context.filesDir, "runtime/$id").also { it.mkdirs() }

	companion object {
		/** 设备是否保留 32 位运行能力 —— 这是本项目的硬门槛。 */
		fun deviceSupports32Bit(): Boolean =
			Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" || it == "armeabi" }

		fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()
	}
}
