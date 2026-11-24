package io.github.xbeeant.batiskit.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.config.MapperAssistSettings

/**
 * Ctrl+B / Ctrl+Click 增强（gotoDeclarationHandler）：
 *
 *  - 在 Mapper 接口方法名上：跳转到 XML 中同 id 的语句片段（多个时列表）；
 *  - 在 Mapper 接口名上：跳转到对应 XML 文件的根标签；
 *  - 在 XML 语句的 `resultMap="…"` / `extends="…"` 属性值上：跳转到对应的 `<resultMap>` 定义；
 *  - 在 `<resultMap id="…">` 的 id 值上：弹出所有引用处（语句 / extends / 嵌套 resultMap）；
 *  - 在 `<sql id="…">` 的 id 值上：弹出所有 `<include refid>` 使用处；
 *  - 在 `<include refid="…">` 上：先给片段定义、再列这个片段的全部使用处。
 * 未匹配到 Mapper XML 时返回 null，交给平台默认行为。
 */
class MapperGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) {
            return null
        }
        // 总开关关闭后完全回落平台默认行为（Ctrl+B 仍可用，只是不再跳 Mapper XML）
        if (!MapperAssistSettings.getInstance().state.navigationIconsEnabled) {
            return null
        }

        // XML 属性值：resultMap / extends / sql 片段的双向跳转与使用情况
        xmlAttributeTargets(sourceElement)?.let { return it }

        val identifier = if (sourceElement is PsiIdentifier) {
            sourceElement
        } else {
            sourceElement.parent as? PsiIdentifier ?: return null
        }

        // 方法名 → XML 语句片段
        val method = PsiTreeUtil.getParentOfType(identifier, PsiMethod::class.java)
        if (method != null && method.nameIdentifier === identifier) {
            val statements = MapperXmlResolver.statements(method)
            if (statements.isNotEmpty()) {
                return statements.toTypedArray()
            }
            return null
        }

        // 接口名 → XML 根标签
        val clazz = PsiTreeUtil.getParentOfType(identifier, PsiClass::class.java)
        if (clazz != null && clazz.isInterface && clazz.nameIdentifier === identifier) {
            val roots = MapperXmlResolver.xmlFiles(clazz).mapNotNull { it.rootTag }
            if (roots.isNotEmpty()) {
                return roots.toTypedArray()
            }
        }
        return null
    }

    /** Mapper XML 属性值上的跳转：引用 → 定义；定义侧 id / refid → 引用处列表 */
    private fun xmlAttributeTargets(sourceElement: PsiElement): Array<PsiElement>? {
        // 光标通常落在属性值内部的叶子 token 上；直接传 XmlAttributeValue 也要能用（getParentOfType 不含自身）
        val value = sourceElement as? XmlAttributeValue
            ?: PsiTreeUtil.getParentOfType(sourceElement, XmlAttributeValue::class.java)
            ?: return null
        val attribute = value.parent as? XmlAttribute ?: return null
        val tag = attribute.parent as? XmlTag ?: return null
        val root = (tag.containingFile as? XmlFile)?.rootTag ?: return null
        if (root.name != "mapper") {
            return null
        }
        return when (attribute.name) {
            "resultMap", "extends" -> {
                val definitions = MapperXmlResolver.resultMapDefinitions(tag, value.value)
                if (definitions.isNotEmpty()) {
                    definitions.toTypedArray()
                } else {
                    null
                }
            }
            "id" -> {
                val usages = when (tag.name) {
                    "resultMap" -> MapperXmlResolver.resultMapUsages(tag)
                    "sql" -> MapperXmlResolver.sqlUsages(tag)
                    else -> return null
                }
                if (usages.isNotEmpty()) usages.toTypedArray() else null
            }

            // 从任一 <include> 都能看到「这个片段还在哪些地方被用过」
            "refid" -> {
                if (tag.name != "include") return null
                val definition = MapperXmlResolver.sqlDefinitions(tag, value.value.orEmpty()).firstOrNull()
                    ?: return null
                val targets = ArrayList<PsiElement>()
                targets += definition
                MapperXmlResolver.sqlUsages(definition).filterTo(targets) { it !== definition }
                targets.toTypedArray()
            }

            else -> null
        }
    }
}
