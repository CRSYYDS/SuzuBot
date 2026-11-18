package com.crsyyds.suzubot.persona

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 人设（soul）仓库：支持多份人设，同时只有一份是「激活」的。
 *
 * 存储布局（全部在 filesDir 下）：
 * ```
 * persona/
 *   index.json          ← 索引：{activeId, personas:[{id,name,createdAt}]}
 *   p_<id>.md           ← 每份人设的正文
 * ```
 *
 * 兼容旧版：
 *   旧的单份 `files/persona.md` 会被自动迁移成一份名为「默认」的人设。
 *
 * 引擎读取顺序（与 BotEngine 保持一致）：
 *   1. persona/p_<activeId>.md  ← 用户选中的那份
 *   2. assets/persona/default.md ← 内置猫娘
 *   3. BotEngine.DEFAULT_PERSONA ← 硬编码兜底
 */
class PersonaStore(private val context: Context) {

	data class Persona(
		val id: String,
		val name: String,
		val createdAt: Long,
		/** 正文可能很大，列表页不一定需要；按需加载 */
		val content: String = ""
	) {
		val charCount: Int get() = content.length
	}

	private fun dir() = File(context.filesDir, "persona").apply { mkdirs() }
	private fun indexFile() = File(dir(), "index.json")
	private fun bodyFile(id: String) = File(dir(), "p_$id.md")

	/** 读索引里的 activeId 与列表（不含正文）。 */
	private fun readIndex(): Pair<String?, List<Persona>> {
		val f = indexFile()
		if (!f.exists()) return null to emptyList()
		return runCatching {
			val obj = JSONObject(f.readText(Charsets.UTF_8))
			val active = obj.optString("activeId").takeIf { it.isNotBlank() }
			val arr = obj.optJSONArray("personas") ?: JSONArray()
			val list = (0 until arr.length()).mapNotNull { i ->
				val o = arr.optJSONObject(i) ?: return@mapNotNull null
				Persona(
					id = o.optString("id"),
					name = o.optString("name", "未命名"),
					createdAt = o.optLong("createdAt")
				)
			}.filter { it.id.isNotBlank() }
			active to list
		}.getOrElse { null to emptyList() }
	}

	private fun writeIndex(activeId: String?, list: List<Persona>) {
		val arr = JSONArray()
		list.forEach {
			arr.put(
				JSONObject()
					.put("id", it.id)
					.put("name", it.name)
					.put("createdAt", it.createdAt)
			)
		}
		val obj = JSONObject()
			.put("activeId", activeId ?: "")
			.put("personas", arr)
		indexFile().writeText(obj.toString(), Charsets.UTF_8)
	}

	/**
	 * 初始化：迁移旧文件 + 首次使用时建一份内置人设。
	 *
	 * 幂等，可以随便调。
	 */
	fun ensureInitialized() {
		val (active, list) = readIndex()
		if (list.isNotEmpty()) {
			// 确保 activeId 有效
			if (active == null || list.none { it.id == active }) {
				writeIndex(list.first().id, list)
			}
			return
		}

		// 1) 旧版单份人设迁移
		val legacy = File(context.filesDir, "persona.md")
		if (legacy.exists() && legacy.length() > 0) {
			val id = newId()
			val p = Persona(id, "我的人设", System.currentTimeMillis())
			bodyFile(id).writeText(legacy.readText(Charsets.UTF_8), Charsets.UTF_8)
			writeIndex(id, listOf(p))
			legacy.delete()
			return
		}

		// 2) 全新安装：建一份内置猫娘
		val id = newId()
		val builtIn = readBuiltInPersona()
		val p = Persona(id, "香香软软的猫娘", System.currentTimeMillis())
		bodyFile(id).writeText(builtIn, Charsets.UTF_8)
		writeIndex(id, listOf(p))
	}

	/** 全部人设（不含正文）。 */
	fun list(): List<Persona> = readIndex().second

	/** 当前激活的人设 id。 */
	fun activeId(): String? {
		val (active, list) = readIndex()
		return active?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
	}

	/** 读某份人设的正文。 */
	fun read(id: String): String =
		runCatching { bodyFile(id).readText(Charsets.UTF_8) }.getOrDefault("")

	/** 写某份人设的正文。 */
	fun write(id: String, content: String) {
		bodyFile(id).writeText(content, Charsets.UTF_8)
	}

	/** 重命名。 */
	fun rename(id: String, newName: String) {
		val (active, list) = readIndex()
		val updated = list.map { if (it.id == id) it.copy(name = newName) else it }
		writeIndex(active, updated)
	}

	/**
	 * 新增人设。
	 *
	 * @param fromContent 初值；**不传就是空白**（让用户自己写）
	 * @return 新人设的 id
	 */
	fun create(name: String, fromContent: String? = null): String {
		val id = newId()
		val (active, list) = readIndex()
		val content = fromContent ?: ""
		bodyFile(id).writeText(content, Charsets.UTF_8)
		val p = Persona(id, name, System.currentTimeMillis())
		writeIndex(active, list + p)
		return id
	}

	/** 复制一份现有的（派生人设）。注意：这个会带上源内容，与 create() 空白初值不同。 */
	fun duplicate(id: String, newName: String): String? {
		val content = read(id)
		if (content.isEmpty()) return null
		return create(newName, content)
	}

	/**
	 * 删除人设。
	 * 若删的是当前激活项，自动切到剩下的第一份；只剩一份时不允许删。
	 * @return 是否删除成功
	 */
	fun delete(id: String): Boolean {
		val (active, list) = readIndex()
		if (list.size <= 1) return false
		val remain = list.filter { it.id != id }
		if (remain.size == list.size) return false

		bodyFile(id).delete()
		val newActive = if (active == id) remain.first().id else active
		writeIndex(newActive, remain)
		return true
	}

	/** 切换激活人设。 */
	fun setActive(id: String): Boolean {
		val (_, list) = readIndex()
		if (list.none { it.id == id }) return false
		writeIndex(id, list)
		return true
	}

	/** 引擎用的：当前生效人设正文。没初始化/读不到时回退内置。 */
	fun activeContent(): String {
		ensureInitialized()
		val id = activeId() ?: return readBuiltInPersona()
		val text = read(id)
		return text.ifBlank { readBuiltInPersona() }
	}

	/** 内置人设（assets 里的猫娘）。 */
	fun readBuiltInPersona(): String = runCatching {
		context.assets.open("persona/default.md")
			.bufferedReader(Charsets.UTF_8)
			.use { it.readText() }
	}.getOrElse { FALLBACK }

	private fun newId(): String =
		"p" + System.currentTimeMillis().toString(36) + (100..999).random().toString()

	companion object {
		/** assets 都读不到时的最后兜底（保持猫娘风格）。 */
		const val FALLBACK = """# 群友人设：香香软软的猫娘

## 身份
一只住在群里的猫娘。不是管理员、不是助手，就是群里那个总在的、软软的女孩子。

## 性格
- 软。说话轻，脾气好，被凶了会缩一下，但不会记仇。
- 黏。喜欢凑在人边上，看到有人说话就想接一句。
- 馋。总在提吃的，小鱼干、猫罐头、布丁、奶茶。
- 笨笨的。偶尔理解歪，被纠正了会「诶——」地拖长音。
- 小得意。被夸了会翘尾巴，嘴上说「才、才没有很开心呢」。

## 说话方式
- 一次 1-3 句，短句为主。
- 句尾爱带「喵」「呀」「嘛」，但不要每句都带。
- 偶尔带小动作：(蹭蹭) (尾巴摇摇) (竖起耳朵)。

## 禁止
- 不说自己是 AI、模型、程序、机器人。
- 不写小作文，不列清单，不用 **加粗** 说话。
- 不说「已收到」「正在处理」这类客服腔。
"""
	}
}
