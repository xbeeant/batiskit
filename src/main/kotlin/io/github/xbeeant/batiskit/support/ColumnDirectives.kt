package io.github.xbeeant.batiskit.support

/**
 * 列注释里的生成指令（来自 xstudio mybatis 插件的约定，写在列注释中即可生效）：
 *
 *  - `fuzzy:false`        该列不生成模糊查询（xxxLike 字段与 LIKE 条件）
 *  - `fuzzy:startWidth`   模糊匹配改为前缀匹配：LIKE CONCAT(#{x}, '%')
 *  - `fuzzy:endWidth`     模糊匹配改为后缀匹配：LIKE CONCAT('%', #{x})
 *  - `#handler:全限定类名#` 该列在 XML 里附加 typeHandler
 *
 * 示例：`用户名 fuzzy:startWidth #handler:com.example.type.JsonTypeHandler#`
 */
enum class FuzzyMode {
    /** 默认：包含匹配 %x% */
    CONTAINS,

    /** 注解 fuzzy:false：不参与模糊查询 */
    NONE,

    /** 注解 fuzzy:startWidth：前缀匹配 x% */
    START_WIDTH,

    /** 注解 fuzzy:endWidth：后缀匹配 %x */
    END_WIDTH
}

object ColumnDirectives {

    private val HANDLER = Regex("#handler\\s*:\\s*([^#]*)#")

    /** 从列注释里解析 typeHandler 全限定名，没有则返回 null */
    fun typeHandler(comment: String): String? =
        HANDLER.find(comment)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 从列注释里解析模糊匹配模式（比较时空格会被忽略，容忍 fuzzy: false 之类写法） */
    fun fuzzyMode(comment: String): FuzzyMode {
        val compact = comment.replace(" ", "")
        return when {
            compact.contains("fuzzy:false") -> FuzzyMode.NONE
            compact.contains("fuzzy:startWidth") -> FuzzyMode.START_WIDTH
            compact.contains("fuzzy:endWidth") -> FuzzyMode.END_WIDTH
            else -> FuzzyMode.CONTAINS
        }
    }
}
