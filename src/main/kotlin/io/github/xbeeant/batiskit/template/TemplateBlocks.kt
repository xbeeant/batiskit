package io.github.xbeeant.batiskit.template

import java.util.concurrent.ConcurrentHashMap

/**
 * 排版块：把「按列/按开关展开」的长段落做成可复用的片段。
 *
 * 模板作者只想要填空题时，不必为了 N 个字段写 `#foreach`——写 `{fields}` 就把整段字段声明搬过来。
 * 块的内容本身也是模板（`resources/blocks/xxx.tpl`），因此：
 *  - 排版只有一处来源，内置模板也复用这些块，对拍测试顺带验证了块；
 *  - 块里可以继续用 `$` / `#if` / `#foreach` 甚至大括号占位符，和普通模板没有区别。
 *
 * 块刻意**不放在 `templates/` 下**：那里「一个文件夹 = 一套模板方案」，
 * 混进一个 `blocks/` 就得在扫描里给它开例外（还得占住这个方案名）。
 * 块不属于任何方案、两套方案共用，放在 `<资源根>/blocks/` 才是它的位置。
 *
 * 渲染时机是「建模型时」——一次生成里模型只建一次，块文本跟着模型走，
 * 16 个模板共享同一份结果，不需要每个模板各算一遍。
 */
object TemplateBlocks {

    /** 块在资源根下的目录名 */
    private const val DIR = "blocks"

    /** 可用的块名，与 `resources/blocks/<name>.tpl` 一一对应 */
    val NAMES = listOf("fields", "accessors", "mapperMethods")

    private val sources = ConcurrentHashMap<String, String>()

    /** 把每个块渲染好塞进模型（键名与 `{...}` 里的名字一致） */
    fun apply(model: MutableMap<String, Any?>) {
        // 用快照渲染：块之间互不引用，避免「后渲染的块看到前一个块的结果」这种隐式顺序依赖
        val snapshot = HashMap(model)
        val names = TemplatePlaceholders.namesOf(snapshot)
        NAMES.forEach { name ->
            model[name] = render(name, snapshot, names)
        }
    }

    /** 单独渲染一个块（测试与调试用） */
    fun render(name: String, model: Map<String, Any?>): String =
        render(name, model, TemplatePlaceholders.namesOf(model))

    private fun render(name: String, model: Map<String, Any?>, names: Set<String>): String {
        val file = "$DIR/$name.tpl"
        // 只缓存原文：编译结果由 Template.compile 按「源码 + 词表」缓存，
        // 这里若连 Template 一起缓存，会把某一次的词表固化下来，块里的大括号占位符就只认那一次的名字了
        val text = sources.getOrPut(file) {
            TemplateBlocks::class.java.getResourceAsStream("/$file")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw TemplateException(file, 1, 1, "内置排版块缺失")
        }
        // 只去掉尾部的空行：块自己以空行开头（保持与上一行内容的间隔），边界排版交给引用处
        return Template.compile(text, file, names).render(model).text.trimEnd('\n', '\r')
    }
}
