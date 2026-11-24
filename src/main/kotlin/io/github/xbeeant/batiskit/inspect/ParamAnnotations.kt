package io.github.xbeeant.batiskit.inspect

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiParameter
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * `@Param` 注解的读写：Alt+Enter 的 intention 和检查里的 quickfix 共用一份实现。
 */
object ParamAnnotations {

    const val FQN = "org.apache.ibatis.annotations.Param"

    /** 参数上 `@Param` 的值（`value` 优先，兼容写成 `name` 的；没写属性时回退参数名） */
    fun of(parameter: PsiParameter): String? {
        val list = parameter.modifierList ?: return null
        // 工程没引入 mybatis 时按简名兜底
        val annotation = list.findAnnotation(FQN) ?: list.findAnnotation("Param") ?: return null
        val raw = annotation.findAttributeValue("value")?.text
            ?: annotation.findAttributeValue("name")?.text
        return raw?.trim()?.trim('"')?.ifBlank { null } ?: parameter.name
    }

    fun has(parameter: PsiParameter): Boolean =
        parameter.modifierList?.let { it.findAnnotation(FQN) != null || it.findAnnotation("Param") != null } ?: false

    /** 给参数加 `@Param(name)`，并把全限定名缩成简名（自动补 import）。必须在写动作里调用 */
    fun add(project: Project, parameter: PsiParameter, name: String) {
        val list = parameter.modifierList ?: return
        val annotation = JavaPsiFacade.getElementFactory(project)
            .createAnnotationFromText("@$FQN(\"$name\")", parameter)
        val anchor = list.firstChild
        if (anchor != null) {
            list.addBefore(annotation, anchor)
        } else {
            list.add(annotation)
        }
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(parameter)
    }
}
