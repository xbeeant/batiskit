package io.github.xbeeant.batiskit.setter

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiStatement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * 把 [SetterPlan] 落笔到源码里：一条语句一个 setter 调用。
 *
 * 默认值有「短名 + import」与「全限定名」两套写法：先尝试补 import，
 * 补不上（import 列表不可用 / 已有同名类冲突）就整条退回全限定写法，保证生成的代码一定能编译。
 */
object AllSetterGenerator {

    const val COMMAND_NAME = "生成所有 Setter（默认值）"
    private const val NOTIFICATION_GROUP = "Batis Kit Generator"

    /** @return 生成的 setter 条数 */
    fun generate(project: Project, plan: SetterPlan): Int {
        val factory = JavaPsiFacade.getElementFactory(project)
        val javaFile = plan.container.containingFile as? PsiJavaFile
        var count = 0
        WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, Runnable {
            val importsReady = prepareImports(project, javaFile, plan.calls)

            var anchor: PsiElement = plan.anchor
            val added = mutableListOf<PsiElement>()

            // 裸 `new X();`：先换成 `X x = new X();`，后续的 setter 才有接收者
            plan.replacement?.let { declaration ->
                val original = anchor as? PsiStatement ?: return@let
                val replacement = factory.createStatementFromText(declaration, original)
                anchor = original.replace(replacement)
            }

            plan.calls.forEach { call ->
                // 这条语句用到的类型只要有一个补不上 import，就整条用全限定名
                val value = if (call.imports.all { importsReady[it] == true }) {
                    call.value
                } else {
                    call.qualifiedValue
                }
                val statement = factory.createStatementFromText(
                    "${plan.receiver}.${call.name}($value);", anchor
                )
                val inserted = plan.container.addAfter(statement, anchor)
                added += inserted
                anchor = inserted
                count++
            }

            // 只排版新增的语句，不动文件里其它代码
            val style = CodeStyleManager.getInstance(project)
            val javaStyle = JavaCodeStyleManager.getInstance(project)
            added.forEach { element ->
                runCatching { if (element.isValid) javaStyle.shortenClassReferences(element) }
            }
            added.forEach { element ->
                runCatching { if (element.isValid) style.reformat(element) }
            }
        })
        if (count > 0) {
            notify(project, count, plan.typeName)
        }
        return count
    }

    /** @return 每个 FQN 能否安全地以短名使用（同包无需 import 也算可以） */
    private fun prepareImports(
        project: Project,
        file: PsiJavaFile?,
        calls: List<SetterCall>
    ): Map<String, Boolean> {
        if (file == null) {
            return emptyMap()
        }
        val existing = file.importList?.importStatements.orEmpty()
        val packageName = file.packageName
        val result = LinkedHashMap<String, Boolean>()
        calls.flatMap { it.imports }.distinct().sorted().forEach { fqn ->
            val simple = fqn.substringAfterLast('.')
            val conflict = existing.any {
                it.qualifiedName != fqn && it.qualifiedName?.substringAfterLast('.') == simple
            }
            val samePackage = fqn.substringBeforeLast('.', "") == packageName
            result[fqn] = !conflict && (samePackage || addImport(project, file, fqn))
        }
        return result
    }

    /** 用临时 Java 文件解析出 import 语句再挂过去，不依赖目标类能否被索引到 */
    private fun addImport(project: Project, file: PsiJavaFile, fqn: String): Boolean {
        val list = file.importList ?: return false
        if (list.importStatements.any { it.qualifiedName == fqn }) {
            return true
        }
        return runCatching {
            val dummy = PsiFileFactory.getInstance(project).createFileFromText(
                "Dummy.java", JavaFileType.INSTANCE, "import $fqn;\nclass Dummy {}"
            ) as? PsiJavaFile ?: return false
            val statement = dummy.importList?.importStatements?.firstOrNull()?.copy() as? PsiImportStatement
                ?: return false
            list.add(statement)
            true
        }.getOrDefault(false)
    }

    private fun notify(project: Project, count: Int, typeName: String) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "Generate All Setter",
                    "已为 $typeName 生成 $count 个 setter 调用（默认值）",
                    NotificationType.INFORMATION
                )
                .notify(project)
        }
    }
}
