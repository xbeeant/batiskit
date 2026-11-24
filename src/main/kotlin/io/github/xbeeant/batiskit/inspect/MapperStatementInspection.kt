package io.github.xbeeant.batiskit.inspect

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * Mapper XML 侧的检查：语句 id 是否重复、语句在接口里是否找得到同名方法。
 *
 * 这两类问题都是静态可判定的，但 MyBatis 的报错时机很难受——重复 id 要等启动
 * （`Mapped Statements collection already contains value`），孤立语句则永远不报，
 * 只是那一坨 SQL 白写了（通常是把方法名拼错了，而接口侧会同时报「没有语句」）。
 *
 * 查不到接口（namespace 写错 / mapper 来自依赖包）时**必须放弃**检查，
 * 不能把「解析不到」当成「方法不存在」，否则依赖包里的 mapper 会满屏报错。
 */
class MapperStatementInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : XmlElementVisitor() {
            override fun visitXmlFile(file: XmlFile) {
                inspect(file, holder)
            }
        }
    }

    private fun inspect(file: XmlFile, holder: ProblemsHolder) {
        val root = file.rootTag ?: return
        if (root.name != "mapper") {
            return
        }
        // 语句必须是 mapper 的直接子标签，嵌套在 <sql> / <resultMap> 里的不算
        val statements = root.subTags.filter { it.name in StatementBinding.STATEMENT_TAGS }
        if (statements.isEmpty()) {
            return
        }
        val byId = statements.groupBy { it.getAttributeValue("id")?.trim().orEmpty() }
        checkDuplicates(byId, holder)
        checkOrphans(root, statements, holder)
    }

    /** 重复 id：同一个 XML 里一条语句只能有一个 id，否则启动即失败 */
    private fun checkDuplicates(byId: Map<String, List<XmlTag>>, holder: ProblemsHolder) {
        byId.forEach { (id, tags) ->
            if (id.isEmpty() || tags.size < 2) {
                return@forEach
            }
            tags.forEach { tag ->
                holder.registerProblem(
                    tag,
                    "语句 id \"$id\" 在本文件内重复 ${tags.size} 次，MyBatis 启动时会报 " +
                        "Mapped Statements collection already contains value"
                )
            }
        }
    }

    /** 孤立语句：id 在接口（含继承方法）里找不到 */
    private fun checkOrphans(root: XmlTag, statements: List<XmlTag>, holder: ProblemsHolder) {
        val iface = MapperXmlResolver.interfaceOf(root) ?: return
        val methodNames = iface.allMethods.map { it.name }.toSet()
        val ids = statements.mapNotNull { it.getAttributeValue("id")?.trim()?.takeIf { id -> id.isNotEmpty() } }
        val orphanIds = StatementBinding.orphanStatements(ids, methodNames).toSet()
        if (orphanIds.isEmpty()) {
            return
        }
        statements.forEach { tag ->
            val id = tag.getAttributeValue("id")?.trim().orEmpty()
            if (id !in orphanIds) {
                return@forEach
            }
            holder.registerProblem(
                tag,
                "语句 id \"$id\" 在 ${iface.qualifiedName.orEmpty()} 里没有同名方法，这条语句不会被调用到"
            )
        }
    }
}
