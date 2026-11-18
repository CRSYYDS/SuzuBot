package dev.qqbot.wrapper.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 图片处理：下载 QQ 图片 → 压缩 → 转 base64，供多模态模型使用。
 *
 * 背景：OneBot 的 image 段给的是 URL（或 file 标识），
 * 而 OpenAI 兼容的视觉接口需要 `image_url` 里塞 data URI 或公网 URL。
 * 这里统一转成 base64 data URI，避免模型侧拉不到内网/临时链接。
 *
 * 体积控制（重要）：
 *  - 原图可能是几 MB，直接 base64 会让请求爆掉
 *  - 统一缩到最长边 1024px、JPEG 质量 80，单张通常 50–150 KB
 *  - 超过 maxImages 或超大图直接跳过，并在提示词里说明
 */
object MediaLoader {

	private const val TAG = "MediaLoader"
	private const val MAX_LONG_SIDE = 1024
	private const val JPEG_QUALITY = 80
	private const val MAX_BYTES = 4L * 1024 * 1024      // 单张原始大小上限
	private const val DOWNLOAD_TIMEOUT = 15_000

	/** 一张处理好的图片。 */
	data class LoadedImage(
		/** data:image/jpeg;base64,... */
		val dataUri: String,
		/** 粗略估算的 KB */
		val approxKb: Int
	)

	/**
	 * 下载并编码多张图片。
	 *
	 * @param urls OneBot image 段里的 url/file
	 * @param maxImages 最多处理几张（默认 4）
	 * @return 成功的图片列表；失败的静默跳过（只记日志）
	 */
	suspend fun loadAll(urls: List<String>, maxImages: Int = 4): List<LoadedImage> =
		withContext(Dispatchers.IO) {
			if (urls.isEmpty()) return@withContext emptyList()
			urls.take(maxImages).mapNotNull { url ->
				runCatching { loadOne(url) }
					.onFailure { Log.w(TAG, "load image failed: ${it.message}") }
					.getOrNull()
			}
		}

	/** 下载单张并转 data URI。 */
	private fun loadOne(rawUrl: String): LoadedImage? {
		val url = normalize(rawUrl) ?: return null

		val conn = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = DOWNLOAD_TIMEOUT
			readTimeout = DOWNLOAD_TIMEOUT
			instanceFollowRedirects = true
			setRequestProperty("User-Agent", "Mozilla/5.0")
		}
		val bytes = try {
			val len = conn.contentLengthLong
			if (len > MAX_BYTES) {
				Log.w(TAG, "image too large: $len bytes")
				return null
			}
			conn.inputStream.use { it.readBytes() }
		} finally {
			conn.disconnect()
		}
		if (bytes.isEmpty()) return null

		// 解码 → 缩放 → 重新编码
		val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
		if (opts.outWidth <= 0 || opts.outHeight <= 0) {
			// 不是图片（可能是 webp 动图或损坏），原样转 base64 让模型判断
			val mime = guessMime(bytes)
			val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
			return LoadedImage("data:$mime;base64,$b64", bytes.size / 1024)
		}

		val sample = calcInSampleSize(opts.outWidth, opts.outHeight)
		val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
		val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
			?: return null

		val scaled = scaleIfNeeded(bitmap)

		val out = ByteArrayOutputStream()
		scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
		if (scaled !== bitmap) scaled.recycle()
		bitmap.recycle()

		val jpeg = out.toByteArray()
		val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
		return LoadedImage("data:image/jpeg;base64,$b64", jpeg.size / 1024)
	}

	/** 处理裸文件名 / 相对路径 / http(s) 的情况。 */
	private fun normalize(raw: String): String? {
		val s = raw.trim()
		if (s.isEmpty()) return null
		return when {
			s.startsWith("http://") || s.startsWith("https://") -> s
			// file:///xxx → 无法直接下载，跳过
			s.startsWith("file://") -> null
			// 可能是 base64 图片（部分实现直接给 base64）
			s.startsWith("base64://") -> null  // 交给上层处理
			else -> null
		}
	}

	private fun calcInSampleSize(w: Int, h: Int): Int {
		var sample = 1
		var longest = maxOf(w, h)
		while (longest / 2 >= MAX_LONG_SIDE) {
			longest /= 2
			sample *= 2
		}
		return sample
	}

	private fun scaleIfNeeded(src: Bitmap): Bitmap {
		val longest = maxOf(src.width, src.height)
		if (longest <= MAX_LONG_SIDE) return src
		val ratio = MAX_LONG_SIDE.toFloat() / longest
		return Bitmap.createScaledBitmap(
			src,
			(src.width * ratio).toInt().coerceAtLeast(1),
			(src.height * ratio).toInt().coerceAtLeast(1),
			true
		)
	}

	private fun guessMime(b: ByteArray): String = when {
		b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
		b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
		b.size > 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
		else -> "image/jpeg"
	}
}
