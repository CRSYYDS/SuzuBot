package dev.qqbot.wrapper.memory

/**
 * CJK bigram 记忆池的 Kotlin 移植。
 *
 * 原版：LismQQ-bot `src/memory-pool.js`（源自 LightphpAgent 的 SharedMemoryPool，Apache-2.0）
 *
 * 核心思路：
 *  - 写入时不做任何处理，只存原文
 *  - 检索时把 query 和每条记忆都切成「打分词元」：小写化 + 去标点 + CJK 字符 bigram
 *  - 用词元重叠数打分，取 topN
 *  - 中文子串能命中（因为 bigram），但同义改写召回不到
 *
 * 分区约定（沿用原版隔离设计）：
 *  - 用户提问写入 scope = "utterance"（对偏好召回不可见）
 *  - 偏好注入只检索 scope = "user"
 *  - 每个 QQ 会话一个 namespace，互不可见
 */
class MemoryPool(
	private val maxRecords: Int = 300,
	private val maxResults: Int = 5
) {

	data class Record(
		val id: String,
		val memory: String,
		val namespace: String,
		val source: String,
		val scope: String,
		val ts: Long,
		val metadata: Map<String, String> = emptyMap()
	) {
		var score: Int = 0
			internal set
	}

	private val records = mutableListOf<Record>()
	private var seq = 0L

	// ── 写入 ──────────────────────────────────────────────────────────────────

	fun store(
		data: String,
		namespace: String = "",
		source: String = "user",
		scope: String = "user",
		metadata: Map<String, String> = emptyMap()
	): Record {
		val rec = Record(
			id = "m-${System.currentTimeMillis().toString(36)}-${++seq}",
			memory = data,
			namespace = namespace,
			source = source,
			scope = scope,
			ts = System.currentTimeMillis(),
			metadata = metadata
		)
		synchronized(records) {
			records.add(rec)
			evictOverflow()
		}
		return rec
	}

	// ── 检索 ──────────────────────────────────────────────────────────────────

	fun retrieve(
		query: String,
		namespace: String = "",
		scope: String? = null,
		source: String? = null,
		limit: Int = maxResults
	): List<Record> {
		if (limit <= 0) return emptyList()
		val queryTokens = tokenize(query)
		if (queryTokens.isEmpty()) return emptyList()

		val candidates = synchronized(records) {
			records.filter { r ->
				r.namespace == namespace &&
					(scope == null || r.scope == scope) &&
					(source == null || r.source == source)
			}.toList()
		}

		return candidates
			.mapIndexed { index, rec ->
				val sc = score(queryTokens, rec.memory)
				rec.score = sc
				index to (rec to sc)
			}
			.filter { it.second.second > 0 }
			.sortedWith(compareByDescending<Pair<Int, Pair<Record, Int>>> { it.second.second }
				.thenByDescending { it.first })
			.take(limit)
			.map { it.second.first }
	}

	fun listRecords(
		namespace: String? = null,
		scope: String? = null,
		source: String? = null
	): List<Record> = synchronized(records) {
		records.filter { r ->
			(namespace == null || r.namespace == namespace) &&
				(scope == null || r.scope == scope) &&
				(source == null || r.source == source)
		}.toList()
	}

	fun count(): Int = synchronized(records) { records.size }

	fun clear(namespace: String? = null): Int = synchronized(records) {
		val before = records.size
		if (namespace == null) records.clear()
		else records.removeAll { it.namespace == namespace }
		before - records.size
	}

	// ── 序列化（用简单文本，避免引入 JSON 库依赖） ──────────────────────────

	/** 每条一行：id\tnamespace\tsource\tscope\tts\tmemory */
	fun serialize(): String = synchronized(records) {
		records.joinToString("\n") { r ->
			listOf(r.id, r.namespace, r.source, r.scope, r.ts.toString(), r.memory)
				.joinToString("\t")
		}
	}

	fun restore(data: String) {
		if (data.isBlank()) return
		synchronized(records) {
			records.clear()
			data.lineSequence().forEach { line ->
				val parts = line.split("\t")
				if (parts.size >= 6) {
					records.add(
						Record(
							id = parts[0],
							namespace = parts[1],
							source = parts[2],
							scope = parts[3],
							ts = parts[4].toLongOrNull() ?: 0L,
							memory = parts.drop(5).joinToString("\t")
						)
					)
				}
			}
			evictOverflow()
		}
	}

	// ── 内部 ──────────────────────────────────────────────────────────────────

	private fun evictOverflow() {
		val overflow = records.size - maxRecords
		if (overflow > 0) {
			records.subList(0, overflow).clear()
		}
	}

	private fun score(queryTokens: Set<String>, memory: String): Int {
		val recTokens = tokenize(memory)
		return queryTokens.count { it in recTokens }
	}

	companion object {
		private val ENGLISH_STOPWORDS = hashSetOf(
			"a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "had", "has",
			"have", "he", "her", "his", "i", "in", "is", "it", "its", "my", "not", "of", "on", "or",
			"our", "she", "so", "that", "the", "their", "them", "there", "they", "this", "to", "was",
			"we", "were", "will", "with", "you", "your"
		)

		private val CJK_STOPWORDS = hashSetOf(
			"我们", "你们", "他们", "她们", "它们", "这个", "那个", "这些", "那些", "什么", "怎么",
			"因为", "所以", "但是", "如果", "虽然", "可以", "应该", "需要", "已经", "还是", "或者",
			"以及", "对于", "关于", "通过", "进行", "一个", "一些", "一下", "一样", "时候", "现在",
			"不是", "就是", "知道", "觉得", "然后", "而且", "由于", "为了", "根据", "按照", "随着"
		)

		private fun isCjk(c: Char): Boolean {
			val code = c.code
			return (code in 0x3040..0x30FF) ||   // 日文假名
				(code in 0x3400..0x4DBF) ||      // CJK 扩展 A
				(code in 0x4E00..0x9FFF) ||      // CJK 基本
				(code in 0xAC00..0xD7AF)         // 韩文
		}

		/** 切出 CJK 连续段的字符 bigram（单字符保留自身）。 */
		fun cjkBigrams(text: String): List<String> {
			if (text.none(::isCjk)) return emptyList()
			val out = mutableListOf<String>()
			val run = StringBuilder()
			fun flush() {
				if (run.isEmpty()) return
				val chars = run.toString().toList()
				if (chars.size < 2) {
					out.add(chars[0].toString())
				} else {
					for (i in 0 until chars.size - 1) {
						out.add("${chars[i]}${chars[i + 1]}")
					}
				}
				run.clear()
			}
			for (c in text) {
				if (isCjk(c)) run.append(c) else flush()
			}
			flush()
			return out
		}

		/**
		 * 把文本切成打分词元：小写化 → 按空白切 → 剥首尾标点 → 停用词过滤；
		 * CJK 段额外产出字符 bigram。
		 */
		fun tokenize(text: String): Set<String> {
			val tokens = HashSet<String>()
			for (raw in text.lowercase().split(Regex("\\s+"))) {
				if (raw.isEmpty()) continue
				val clean = raw.trim { !it.isLetterOrDigit() }
				if (clean.isEmpty() || clean in ENGLISH_STOPWORDS) continue
				tokens.add(clean)
				for (bg in cjkBigrams(clean)) {
					if (bg !in CJK_STOPWORDS) tokens.add(bg)
				}
			}
			return tokens
		}

		/** 把检索结果渲染成注入上下文的偏好块。 */
		fun renderPreferenceBlock(results: List<Record>): String {
			if (results.isEmpty()) return ""
			val lines = results.map { it.memory }.filter { it.isNotEmpty() }
			if (lines.isEmpty()) return ""
			return "\n##用户偏好\n用户之前提到了:\n" + lines.joinToString("\n")
		}
	}
}
