package io.github.xbeeant.batiskit.refactor

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * Mapper XML 内的重命名重构入口（对标 rockjava/intellij-mybatis-plugin 的 rename 能力）：
 *
 *  - 语句 `id` 字面量上 Shift+F6 → 直接弹出**接口方法**的重命名（方法名是唯一真相，
 *    XML 语句 id 由引用机制随方法重命名同步改写，包括同 id 的多条语句与其它 XML 文件）；
 *  - `resultMap` 定义 / 引用字面量上 Shift+F6 → 更新定义 id 与全部引用处
 *    （未限定引用、全限定引用都能改写）；
 *  - `<sql id>` 定义 / `<include refid>` 字面量上 Shift+F6 → 更新片段定义与全部 include 引用。
 *
 * 平台的 PsiElementRenameHandler 对 XmlAttributeValue 判定「不可重命名」
 * （XmlAttributeValue 不是 PsiNamedElement），所以这里注册的 [RenameHandler] 是
 * 唯一命中的处理器，不会出现处理器选择弹窗。
 */
class MapperRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean = targetFrom(dataContext) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        execute(project, editor, targetFrom(dataContext) ?: return)
    }

    override fun invoke(project: Project, elements: Array<PsiElement>, dataContext: DataContext) {
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        execute(project, editor, MapperRenameSupport.renameTargetOf(elements.firstOrNull() ?: return) ?: return)
    }

    private fun execute(project: Project, editor: Editor?, target: RenameTarget) {
        when (target) {
            is MethodRenameTarget ->
                // 方法名是唯一真相：交给平台对方法做完整重命名，
                // XML 语句 id 由 MapperReferenceContributor 的引用机制同步
                PsiElementRenameHandler.invoke(target.method, project, target.method, editor)

            is ResultMapRenameTarget -> {
                val oldId = target.definition.getAttributeValue("id") ?: return
                val newName = Messages.showInputDialog(
                    project,
                    "更新 <resultMap> 定义 id 与全部引用处：",
                    "重命名 resultMap",
                    null,
                    oldId,
                    null
                )?.trim().orEmpty()
                if (newName.isEmpty() || newName == oldId) {
                    return
                }
                MapperRenameSupport.renameResultMap(project, target.definition, newName)
            }

            is SqlRenameTarget -> {
                val oldId = target.definition.getAttributeValue("id") ?: return
                val newName = Messages.showInputDialog(
                    project,
                    "更新 <sql> 定义 id 与全部 <include refid>：",
                    "重命名 SQL 片段",
                    null,
                    oldId,
                    null
                )?.trim().orEmpty()
                if (newName.isEmpty() || newName == oldId) {
                    return
                }
                MapperRenameSupport.renameSql(project, target.definition, newName)
            }
        }
    }

    private fun targetFrom(dataContext: DataContext): RenameTarget? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        val file = CommonDataKeys.PSI_FILE.getData(dataContext)
        val element = file?.let { f ->
            editor?.let { e -> f.findElementAt(e.caretModel.offset) }
        } ?: PsiElementRenameHandler.getElement(dataContext)
        return MapperRenameSupport.renameTargetOf(element)
    }
}

/** 重命名目标：语句 id → 方法；resultMap / sql 的定义与引用 → 定义标签 */
internal sealed class RenameTarget

internal data class MethodRenameTarget(val method: PsiMethod) : RenameTarget()

internal data class ResultMapRenameTarget(val definition: XmlTag) : RenameTarget()

internal data class SqlRenameTarget(val definition: XmlTag) : RenameTarget()

/**
 * 重命名目标解析（内部逻辑，测试可直接断言）与 resultMap 的同步重命名。
 */
internal object MapperRenameSupport {

    /** 字面量所在位置能发起的重命名目标；无关位置返回 null（菜单不出现） */
    fun renameTargetOf(element: PsiElement?): RenameTarget? {
        val value = element?.toAttributeValue() ?: return null
        val attribute = value.parent as? XmlAttribute ?: return null
        val tag = attribute.parent as? XmlTag ?: return null
        val file = value.containingFile as? XmlFile ?: return null
        if (file.rootTag?.name != "mapper") {
            return null
        }
        return when (attribute.localName) {
            "id" -> when {
                MapperXmlResolver.isStatementTag(tag) -> {
                    val method = MapperXmlResolver.method(tag) ?: return null
                    MethodRenameTarget(method)
                }

                tag.name == "resultMap" -> ResultMapRenameTarget(tag)

                tag.name == "sql" -> SqlRenameTarget(tag)

                else -> null
            }

            // 引用处发起重命名：目标同样是定义（与在定义上发起等价）
            "resultMap", "extends" -> resultMapDefinitionOf(value, attribute, tag)
                ?.let { ResultMapRenameTarget(it) }

            "refid" -> {
                val ref = value.value?.trim().orEmpty()
                MapperXmlResolver.sqlDefinitions(tag, ref).firstOrNull()?.let { SqlRenameTarget(it) }
            }

            else -> null
        }
    }

    /** resultMap 引用属性值 → 定义标签；解析不到（引用写错 / 跨工程）返回 null */
    fun resultMapDefinitionOf(value: XmlAttributeValue, attribute: XmlAttribute, anchor: XmlTag): XmlTag? {
        val ref = value.value?.trim().orEmpty()
        if (ref.isBlank()) {
            return null
        }
        return MapperXmlResolver.resultMapDefinitions(anchor, ref).firstOrNull()
    }

    /**
     * 更新 `<resultMap>` 定义 id 与全部引用处（未限定 / 全限定都能改写）。
     * 一个写命令提交，Ctrl+Z 一次撤销。
     */
    fun renameResultMap(project: Project, definition: XmlTag, newName: String) {
        val oldId = definition.getAttributeValue("id") ?: return
        if (oldId == newName) {
            return
        }
        // 先在旧 id 下收集引用处，再一起改写
        val usages = MapperXmlResolver.resultMapUsages(definition)
        WriteCommandAction.runWriteCommandAction(project) {
            definition.setAttribute("id", newName)
            usages.forEach { usage ->
                updateReferenceAttributes(usage, oldId, newName)
            }
        }
    }

    /**
     * 更新 `<sql>` 定义 id 与全部 `<include refid>` 引用（未限定 / 全限定都能改写）。
     * 一个写命令提交，Ctrl+Z 一次撤销。
     */
    fun renameSql(project: Project, definition: XmlTag, newName: String) {
        val oldId = definition.getAttributeValue("id") ?: return
        if (oldId == newName) return
        val usages = MapperXmlResolver.sqlUsages(definition)
        WriteCommandAction.runWriteCommandAction(project) {
            definition.setAttribute("id", newName)
            usages.forEach { usage ->
                val raw = usage.getAttributeValue("refid")?.trim() ?: return@forEach
                val updated = when {
                    raw == oldId -> newName
                    raw.endsWith(".$oldId") -> raw.removeSuffix(".$oldId") + ".$newName"
                    else -> return@forEach
                }
                usage.setAttribute("refid", updated)
            }
        }
    }

    /** 引用标签上持有 resultMap 引用的属性：语句与嵌套标签用 resultMap，定义用 extends */
    private fun updateReferenceAttributes(tag: XmlTag, oldId: String, newId: String) {
        val attributeNames = when {
            MapperXmlResolver.isStatementTag(tag) -> listOf("resultMap")
            tag.name == "resultMap" -> listOf("extends")
            tag.name == "association" || tag.name == "collection" -> listOf("resultMap")
            else -> return
        }
        for (name in attributeNames) {
            val raw = tag.getAttributeValue(name)?.trim() ?: continue
            val updated = when {
                raw == oldId -> newId
                raw.endsWith(".$oldId") -> raw.removeSuffix(".$oldId") + ".$newId"
                else -> continue
            }
            tag.setAttribute(name, updated)
        }
    }
}

/** 从任意光标元素向上定位到 XmlAttributeValue（属性值整体，含引号） */
private fun PsiElement.toAttributeValue(): XmlAttributeValue? =
    PsiTreeUtil.getParentOfType(this, XmlAttributeValue::class.java, false)

/**
 * Mapper 接口重命名 → 绑定的 XML 文件跟着重命名（rockjava 的「rename the mapper xml file
 * when rename the mapper interface」）。namespace 属性由 [MapperReferenceContributor]
 * 的引用机制在重命名过程中先更新，这里只需按新 FQN 找到绑定文件并把
 * `<旧类名>.xml` 改成 `<新类名>.xml`；文件名与类名不一致的 XML 不动。
 */
class MapperFileRenameListenerProvider : RefactoringElementListenerProvider {

    override fun getListener(element: PsiElement): RefactoringElementListener? {
        val clazz = element as? PsiClass ?: return null
        if (!clazz.isInterface) {
            return null
        }
        val oldName = clazz.name ?: return null
        return object : RefactoringElementListener {
            override fun elementMoved(newElement: PsiElement) {
                // 移动不涉及 XML 文件名与 namespace
            }

            override fun elementRenamed(newElement: PsiElement) {
                val newClass = newElement as? PsiClass ?: return
                val newName = newClass.name ?: return
                if (newName == oldName) {
                    return
                }
                val fqn = newClass.qualifiedName ?: return
                val files = MapperXmlResolver.xmlFilesByNamespace(newClass, fqn)
                for (file in files) {
                    val vf = file.virtualFile ?: continue
                    if (vf.name == "$oldName.xml") {
                        ApplicationManager.getApplication().runWriteAction {
                            vf.rename(this, "$newName.xml")
                        }
                    }
                }
            }
        }
    }
}
