package io.github.xbeeant.batiskit.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement

/**
 * Mapper XML 参数提示入口，同时覆盖**注入的 SQL 片段**。
 *
 * 真实 IDE 里 `<select>` 正文常被注入成 SQL，光标所在的 PSI 属于注入文件，
 * 只看 `XmlText` 父链的旧实现在这种情况下完全不触发；这里先把位置映射回宿主 XML，
 * 再交给 [MapperParamCompletionSupport] 统一判定上下文和生成候选。
 *
 * 弹出时机：`#` / `$` 敲出来就弹，继续输入 `{`、`.` 也保持弹出；
 * 候选内容（@Param、paramN/argN、foreach item 的类型与属性、bind、SQL 上下文提示）
 * 全部由 [MapperParamCompletionSupport] 提供，与参数检查共用同一套解析规则。
 *
 * 注册两次（XML 与 SQL）由 plugin.xml 完成：注入 SQL 时平台只调用 SQL 语言的
 * contributor，宿主 XML 文本则走 XML 的注册。
 */
class MapperParamCompletionContributor : CompletionContributor() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean {
        if (typeChar !in setOf('#', '$', '{', '.')) {
            return false
        }
        val (host, offset) = MapperCompletionHost.positionOf(position, position.textOffset)
        if (typeChar == '{') {
            val text = host.containingFile.text
            if (offset < 1 || text[offset - 1] !in charArrayOf('#', '$')) {
                return false
            }
        }
        return MapperParamCompletionSupport.isContext(host, offset)
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val project = parameters.originalFile.project
        if (DumbService.isDumb(project)) {
            return
        }
        val position = parameters.originalPosition ?: parameters.position
        val (host, offset) = MapperCompletionHost.positionOf(position, parameters.offset)
        MapperParamCompletionSupport.complete(host, offset, result)
    }
}
