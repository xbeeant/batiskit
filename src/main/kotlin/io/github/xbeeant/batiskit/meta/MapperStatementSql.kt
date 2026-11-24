package io.github.xbeeant.batiskit.meta

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlFile
import io.github.xbeeant.batiskit.config.MapperAssistSettings

/**
 * Alt+Enter / 检查 quickfix 生成语句时用的「预填 CRUD SQL」构造器。
 *
 * intention 与 inspection 共用这一份，避免两处推断规则漂移。拿不到表名或列时返回 null，
 * 调用方会退回原来的空骨架，绝不因为辅助功能失败而挡住生成语句。
 */
object MapperStatementSql {

    fun build(project: Project, method: PsiMethod, kind: String, file: XmlFile?): String? {
        val state = MapperAssistSettings.getInstance().state
        if (!state.crudSqlEnabled) return null
        val namespace = file?.rootTag?.getAttributeValue("namespace").orEmpty()
        val hints = mutableListOf<String>()
        method.returnType.let { type ->
            (type as? PsiClassType)?.parameters?.forEach { parameter ->
                (parameter as? PsiClassType)?.resolve()?.qualifiedName?.let { hints += it }
            }
            (type as? PsiClassType)?.resolve()?.qualifiedName?.let { hints += it }
        }
        method.parameterList.parameters.forEach { parameter ->
            (parameter.type as? PsiClassType)?.resolve()?.qualifiedName?.let { hints += it }
        }
        val table = TableMappingResolver.tableNameFor(project, file, namespace, hints) ?: return null
        val columns = TableMetadata.columns(project, table, file)
        return MapperSqlRules.sqlFor(kind, table, columns).takeIf { it.isNotBlank() }
    }
}
