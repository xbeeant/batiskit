@file:Suppress("DEPRECATION") // JDOM 原生类被平台标弃用，但仍是零依赖读写 XML 最直接的方式

package io.github.xbeeant.batiskit.template

import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import java.io.StringReader

/**
 * 一整套模板方案的导入 / 导出（纯逻辑，可单测）。
 *
 * 方案在插件里有两种载体：内置 / 项目模板是「一个文件夹一套」，全局用户模板是设置里的扁平列表。
 * 导出把一套里的每个模板**全文**（含 front-matter）打包成一个 XML，导入时写回全局用户模板，
 * 并往头部注入 / 改写 `set: <方案名>`，让它们成为一套自包含的新方案（不覆盖内置同 id 模板）。
 *
 * 模板正文里常有大量 `<` / `&` / `${...}`，一律交给 JDOM 的 `setText` 转义，不手写字符串拼接。
 */
object TemplateSetBundle {

    const val ROOT = "batiskit-template-set"
    const val VERSION = "1"
    const val MAX_TEMPLATES = 100
    const val MAX_TEXT_LENGTH = 512 * 1024

    data class Item(val id: String, val name: String = "", val text: String = "")

    data class Bundle(val set: String, val items: List<Item>)

    private val SET_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    private val ITEM_ID = Regex("[A-Za-z0-9_.-]{1,128}")
    private val TRAILING_COMMENT = Regex("""\s+#.*$""")

    fun encode(set: String, items: List<Item>): String {
        val root = Element(ROOT)
            .setAttribute("version", VERSION)
            .setAttribute("name", set)
        items.forEach { item ->
            root.addContent(
                Element("template")
                    .setAttribute("id", item.id)
                    .setAttribute("name", item.name)
                    .setText(item.text)
            )
        }
        // raw format：模板正文里的缩进与末尾换行都是内容，pretty format 会把它当排版空白吃掉
        return XMLOutputter(Format.getRawFormat()).outputString(root)
    }

    /** 解析失败 / 根标签不对时抛 [IllegalArgumentException]，消息可直接展示给用户 */
    fun decode(text: String): Bundle {
        if (text.isBlank()) throw IllegalArgumentException("文件为空")
        val root = runCatching { parse(text) }
            .getOrElse { throw IllegalArgumentException("XML 解析失败：${it.message ?: "未知错误"}") }
        if (root.name != ROOT) {
            throw IllegalArgumentException("这不是模板方案文件（根标签应为 <$ROOT>）")
        }
        val set = root.getAttributeValue("name").orEmpty().trim()
        val items = root.getChildren("template").map { child ->
            Item(
                id = child.getAttributeValue("id").orEmpty().trim(),
                name = child.getAttributeValue("name").orEmpty(),
                text = child.text.orEmpty(),
            )
        }
        return Bundle(set, items)
    }

    /** 返回全部问题；空列表表示可以安全导入 */
    fun validate(bundle: Bundle): List<String> {
        val problems = mutableListOf<String>()
        if (!SET_NAME.matches(bundle.set)) {
            problems += "方案名不合法：只能用字母、数字、下划线与连字符（1–64 个字符）"
        }
        if (bundle.items.isEmpty()) problems += "方案里没有模板"
        if (bundle.items.size > MAX_TEMPLATES) problems += "模板数超过上限 $MAX_TEMPLATES"
        val seen = mutableSetOf<String>()
        bundle.items.forEach { item ->
            if (!ITEM_ID.matches(item.id)) problems += "模板 id 不合法：${item.id.ifBlank { "(空)" }}"
            if (!seen.add(item.id)) problems += "模板 id 重复：${item.id}"
            if (item.text.isBlank()) problems += "模板 ${item.id} 内容为空"
            if (item.text.length > MAX_TEXT_LENGTH) problems += "模板 ${item.id} 超过 512 KB"
            if (item.text.isNotBlank()) {
                TemplateSpec.parse(item.text, "${item.id}.tpl").error?.let { problems += "模板 ${item.id}：$it" }
            }
        }
        return problems
    }

    /**
     * 在 front-matter 里注入或改写 `set:`；头部不合法时原样返回，由 [validate] 拦截。
     * 除这一行外头部其它内容逐字保留（注释、空行、字段顺序、缩进）。
     */
    fun withSet(text: String, setId: String): String {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n").toMutableList()
        if (lines.firstOrNull()?.trim() != "---") return normalized
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return normalized
        val existing = (1 until end).firstOrNull { lines[it].trimStart().startsWith("set:") }
        if (existing != null) {
            lines[existing] = replaceSetLine(lines[existing], setId)
        } else {
            // 头部用了缩进就跟齐缩进，别插出一行风格突兀的声明
            val indent = (1 until end)
                .map { lines[it] }
                .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                ?.takeWhile { it == ' ' || it == '\t' }
                .orEmpty()
            lines.add(end, "${indent}set: $setId")
        }
        return lines.joinToString("\n")
    }

    /** 只替换 set 的值，保留原行的缩进与行尾注释（如 `set: old  # 归属方案`） */
    fun replaceSetLine(line: String, setId: String): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val comment = TRAILING_COMMENT.find(line.substringAfter(':'))?.value.orEmpty()
        return "${indent}set: $setId$comment"
    }

    /** 用户模板列表里的 id 只是列表键；带方案前缀避免与「覆盖内置模板」的条目撞车 */
    fun keyOf(setId: String, itemId: String): String = "$setId/$itemId"

    private fun parse(text: String): Element {
        val builder = SAXBuilder()
        runCatching { builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { builder.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        builder.setExpandEntities(false)
        return builder.build(StringReader(text)).rootElement
    }
}
