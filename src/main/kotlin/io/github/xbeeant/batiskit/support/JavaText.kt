package io.github.xbeeant.batiskit.support

/** 源码文本处理的小工具 */
object JavaText {

    /** "com.x.Base<A, B>" -> "Base<A, B>"；"com.x.Base" -> "Base" */
    fun stripPackage(text: String): String {
        val trimmed = text.trim()
        val generic = trimmed.indexOf('<')
        return if (generic < 0) {
            trimmed.substringAfterLast('.')
        } else {
            trimmed.substring(0, generic).substringAfterLast('.') + trimmed.substring(generic)
        }
    }

    /** 抽取模板里出现的全限定类型（用于生成 import） */
    fun qualifiedTypesIn(template: String): List<String> =
        Regex("[A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)+").findAll(template)
            .map { it.value }
            .filter { it.substringAfterLast('.').firstOrNull()?.isUpperCase() == true }
            .toList()

    /**
     * 把模板里的全限定类型替换为简名显示，并返回需要 import 的类型清单。
     * "com.x.IAbstractDao<A, com.y.PageBounds>" -> ("IAbstractDao<A, PageBounds>", ["com.x.IAbstractDao", "com.y.PageBounds"])
     */
    fun simplifyQualified(template: String): Pair<String, List<String>> {
        val fqns = qualifiedTypesIn(template).sortedByDescending { it.length }
        var display = template
        fqns.forEach { display = display.replace(it, it.substringAfterLast('.')) }
        return display to fqns
    }

    /** 转义 Java 字符串字面量 */
    fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", " ")

    /** 驼峰转下划线：userName -> user_name（用于判断 MyBatis-Plus 是否需要 @TableField） */
    fun camelToSnake(text: String): String = buildString {
        text.forEachIndexed { index, ch ->
            if (ch.isUpperCase()) {
                if (index > 0 && !text[index - 1].isUpperCase()) {
                    append('_')
                }
                append(ch.lowercaseChar())
            } else {
                append(ch)
            }
        }
    }
}
