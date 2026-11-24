package io.github.xbeeant.batiskit.generate

import io.github.xbeeant.batiskit.template.TemplateSpec

/**
 * 给新建的 Java 产物加来源标记。
 *
 * 以前生成完的文件和手写文件长得一模一样，合并时只能靠「方法签名 + 文本是否相同」猜测哪段是
 * 生成器写的；有了标记，后续合并、漂移比对和人工排查都能一眼分辨产出来历。
 *
 * 只作用于**新建**文件：已存在的文件不去改动，否则每次二次生成都会因为「多了一行注解」
 * 被判成有变化，预览里全是噪音，还可能把用户手工删掉的标记又加回去。
 *
 * 用 JDK 自带的 `javax.annotation.processing.Generated`（Java 9+，无需第三方依赖）。
 */
object GeneratedMarker {

    const val IMPORT = "import javax.annotation.processing.Generated;"

    private const val VALUE = "batiskit"

    /** 匹配主类型声明（含 public / abstract / final / sealed 等修饰） */
    private val TYPE_DECL = Regex(
        """(?m)^([ \t]*)(?:public\s+)?(?:abstract\s+|final\s+|sealed\s+|non-sealed\s+)?(?:class|interface|enum|record)\s+[A-Za-z_]"""
    )

    fun isMarked(text: String): Boolean = text.contains("@Generated(value = \"$VALUE\"")

    /** 已经是 Java 源码、且还没被打过标记时，插入 import 与类级注解；否则原样返回 */
    fun annotate(text: String, spec: TemplateSpec, setId: String, fileName: String): String {
        if (!fileName.endsWith(".java", ignoreCase = true) || isMarked(text)) return text
        val match = TYPE_DECL.find(text) ?: return text

        val annotation = "@Generated(value = \"$VALUE\", comments = \"set=$setId;template=${spec.id}\")"
        val indent = match.groupValues[1]
        val at = match.range.first + indent.length
        val withAnno = text.substring(0, at) + annotation + "\n" + indent + text.substring(at)

        val lines = withAnno.lines().toMutableList()
        if (lines.none { it.trim() == IMPORT }) {
            val packageLine = lines.indexOfFirst { it.trimStart().startsWith("package ") }
            val firstImport = lines.indexOfFirst { it.trimStart().startsWith("import ") }
            val insertAt = when {
                firstImport >= 0 -> firstImport
                packageLine >= 0 -> packageLine + 1
                else -> 0
            }
            lines.add(insertAt, IMPORT)
            // 原来没有 import 时，在 import 与 package / 类声明之间留一个空行
            if (firstImport < 0 && packageLine >= 0) lines.add(insertAt + 1, "")
        }
        return lines.joinToString("\n")
    }
}
