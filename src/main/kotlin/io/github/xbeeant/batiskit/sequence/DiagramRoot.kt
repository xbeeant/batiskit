package io.github.xbeeant.batiskit.sequence

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UMethod

/**
 * 时序图的起点：把「Java 方法」与「Kotlin / 其它 UAST 语言的方法」统一成一个入口。
 *
 * 对话框、导出、双击导航都只认这份抽象；语言差异收敛在 [analyze] 里。
 * Java 分支继续走原来的 [CallChainAnalyzer]，行为逐字保持；Kotlin 分支走 [UastCallChainAnalyzer]。
 */
sealed class DiagramRoot {

    /** 标题与默认文件名用，形如 `UserService.save` */
    abstract val displayName: String

    /** 双击跳声明用（模型里存的是 PsiElement） */
    abstract val psiElement: PsiElement

    abstract fun analyze(project: Project, options: DiagramOptions): SequenceDiagram

    class Java(private val method: PsiMethod) : DiagramRoot() {
        override val displayName: String
            get() = "${method.containingClass?.name ?: "?"}.${method.name}"
        override val psiElement: PsiElement get() = method
        override fun analyze(project: Project, options: DiagramOptions): SequenceDiagram =
            CallChainAnalyzer(project, options).analyze(method)
    }

    class Uast(private val method: UMethod) : DiagramRoot() {
        override val displayName: String
            get() = runCatching {
                val javaPsi = method.javaPsi
                "${javaPsi.containingClass?.name ?: "?"}.${javaPsi.name}"
            }.getOrDefault("sequence")
        override val psiElement: PsiElement
            get() = method.sourcePsi ?: method.javaPsi
        override fun analyze(project: Project, options: DiagramOptions): SequenceDiagram =
            UastCallChainAnalyzer(project, options).analyze(method)
    }
}
