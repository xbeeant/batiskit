package io.github.xbeeant.batiskit.sequence

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiUtilCore
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UastFacade
import org.jetbrains.uast.toUElement

/**
 * 基于 UAST 的调用链分析器：给 Kotlin 方法（以及其它 UAST 语言）生成 [SequenceDiagram]。
 *
 * 为什么单独一份而不是改造 [CallChainAnalyzer]：后者全靠 Java PSI
 * （`JavaRecursiveElementWalkingVisitor` / `PsiMethodCallExpression`），改它等于拿 11 条 Java 回归测试冒险。
 * UAST 是平台自带的语言无关抽象，编译期**不需要** Kotlin 插件依赖；运行时 Kotlin 插件注册自己的
 * UAST 转换器，Kotlin 与 Java 的调用表达式统一变成 [UCallExpression]，因此 Kotlin → Java 的
 * 混合调用链也能一路下钻。
 *
 * 语义与 Java 版对齐：根方法深度 0、递归回边不再展开、噪声调用过滤、基础类 / 工具类调用跳过、
 * 消息数上限截断、只展开本工程代码、智能接口唯一实现自动展开（多个实现按互斥 alt 分支分别展开）、
 * 返回箭头可选。模型层与导航层本来就语言无关。
 * 数据库层追踪与 Java 版共用 [DatabaseTrace]，Kotlin 调 Java Mapper 的链一样能落到 SQL。
 */
class UastCallChainAnalyzer(
    private val project: Project,
    private val options: DiagramOptions,
) {

    /** 与 Java 版共用同一套数据库层解析规则，一张图一个实例 */
    private val databaseTrace = DatabaseTrace(project, options)

    fun analyze(root: UMethod): SequenceDiagram {
        val diagram = SequenceDiagram()
        val info = uMethodInfo(root) ?: return diagram.also { it.seal() }
        val participant = diagram.addParticipant(participantOf(info))
        stack.addLast(SequenceRules.methodKey(participant.qualified, info.name, info.paramCount))
        try {
            val calls = callsIn(info.body)
            walk(calls, participant.qualified, 0, diagram)
            if (diagram.messages.none { it.kind != MessageKind.NOTE }) {
                // 起点是 Mapper 接口方法（无方法体）时，SQL 在 XML / 注解里而不是方法体里
                databaseTrace.append(diagram, participant.qualified, 0, info.javaMethod)
            }
            if (diagram.messages.none { it.kind != MessageKind.NOTE }) {
                note(diagram, participant.qualified, "方法体里没有发现可解析的方法调用", info.nav)
            }
        } finally {
            stack.removeLast()
        }
        diagram.seal()
        return diagram
    }

    /** 递归守卫栈：当前调用链上已经展开过的方法 */
    private val stack = ArrayDeque<String>()

    // ---------- 目标信息：身份、归属、导航 ----------

    /** 一个可下钻方法所需的全部信息，Java / Kotlin 目标共用 */
    private data class MethodInfo(
        val body: PsiElement?,
        val cls: PsiClass?,
        val name: String,
        val paramCount: Int,
        val returnText: String?,
        val nav: PsiElement,
        val isAbstract: Boolean,
        val javaMethod: PsiMethod,
    )

    private data class CallInfo(
        val method: MethodInfo,
        val callName: String,
        val args: List<String>,
        val receiverText: String,
        val isConstructor: Boolean,
    )

    /** 一条调用 + 它所在的分支路径（条件原文），布局层据此画 alt / loop / opt 片段框 */
    private data class CallEntry(
        val call: UCallExpression,
        val guard: String = "",
        val kind: BranchKind? = null,
    )

    private fun uMethodInfo(method: UMethod): MethodInfo? {
        val javaPsi = runCatching { method.javaPsi }.getOrElse { return null }
        return methodInfo(javaPsi, method.sourcePsi ?: javaPsi.body, method.sourcePsi ?: javaPsi)
    }

    private fun methodInfo(
        method: PsiMethod,
        body: PsiElement? = method.body,
        nav: PsiElement = method,
    ): MethodInfo {
        val cls = method.containingClass
        return MethodInfo(
            body = body,
            cls = cls,
            name = method.name,
            paramCount = method.parameterList.parametersCount,
            returnText = method.returnType?.canonicalText,
            nav = nav,
            isAbstract = cls?.isInterface == true || method.hasModifierProperty(PsiModifier.ABSTRACT),
            javaMethod = method,
        )
    }

    /** 调用解析到的目标：Kotlin 函数通常是 KtNamedFunction（转 UMethod），Java 方法是 PsiMethod */
    private fun resolvedMethodInfo(resolved: PsiElement): MethodInfo? = when (resolved) {
        is PsiMethod -> methodInfo(resolved)
        else -> runCatching { resolved.toUElement() as? UMethod }.getOrNull()?.let { uMethodInfo(it) }
    }

    private fun callInfoOf(call: UCallExpression): CallInfo? {
        val resolved = runCatching { call.resolve() }.getOrNull() ?: return null
        val method = resolvedMethodInfo(resolved) ?: return null
        val kind = call.kind.name
        return CallInfo(
            method = method,
            callName = call.methodName ?: method.name,
            args = call.valueArguments.map { it.sourcePsi?.text?.trim().orEmpty() },
            receiverText = receiverTextOf(call),
            isConstructor = kind == "CONSTRUCTOR_CALL" || kind == "NESTED_CONSTRUCTOR_CALL",
        )
    }

    private fun participantOf(info: MethodInfo): SeqParticipant = SeqParticipant(
        qualified = SequenceRules.qualifiedOf(info.cls?.qualifiedName, info.cls?.name),
        display = SequenceRules.displayOf(info.cls?.name),
        isInterface = info.isAbstract,
        psiClass = info.cls,
        fullDisplay = info.cls?.name ?: "anonymous",
    )

    // ---------- 遍历 ----------

    /**
     * 收集方法体里的调用表达式，同时把 if / else / 循环 / switch / try 的条件原文挂到每条调用上。
     * 逐级转换 PSI → UAST 而不是用 UastVisitor，是为了同时吃 Java / Kotlin 两种 PSI，并且能
     * 跳过匿名对象 / 局部类的方法体（那是另一条执行线，对齐 Java 版 `visitClass` 不递归的语义）。
     * lambda 体仍算当前方法的延续，自然被收集进来。
     *
     * 关键坑：Java 的带接收者调用（`service.doWork()`）经 `toUElement()` 得到的是
     * `UQualifiedReferenceExpression`（Java 里叫 JavaUCompositeQualifiedExpression），**不是** UCallExpression，
     * 真正的调用藏在 selector 里；只有无接收者的 `helper()` 才会直接转成 UCallExpression。
     * 只看后者会漏掉跨对象调用——也就是调用链的主体。这里三种取法都试，再按 sourcePsi 去重
     * （表达式语句与它内层的调用会指向同一个 PSI 元素）。
     */
    private fun callsIn(body: PsiElement?): List<CallEntry> {
        if (body == null) return emptyList()
        val result = ArrayList<CallEntry>()
        val seen = HashSet<Any>()
        val path = ArrayList<Pair<BranchKind, String>>()
        collect(body, result, seen, path)
        return result
    }

    private fun collect(
        element: PsiElement,
        out: MutableList<CallEntry>,
        seen: MutableSet<Any>,
        path: MutableList<Pair<BranchKind, String>>,
    ) {
        if (isAnonymousObject(element)) return
        val u = runCatching { element.toUElement() }.getOrNull()

        // if / else 单独处理：条件里的调用照常收集（不带分支标记），条件原文进 guard；
        // then 与 else 分别标注，互斥关系不丢。else-if 会再走到这里的 UIfExpression 分支。
        if (u is UIfExpression) {
            val cond = textOf(u.condition.sourcePsi)
            u.condition.sourcePsi?.let { collect(it, out, seen, path) }
            inPath(path, BranchKind.IF, "if ($cond)") {
                u.thenExpression?.sourcePsi?.let { collect(it, out, seen, path) }
            }
            u.elseExpression?.sourcePsi?.let { elsePsi ->
                inPath(path, BranchKind.ELSE, "else") { collect(elsePsi, out, seen, path) }
            }
            return
        }

        // 循环 / switch / 三元 / try：用 PSI 类名识别，避免绑定各语言 UAST 子接口的差异；
        // guard 取语句头部（压成一行），条件里的调用仍随 children 一起收集。
        val mark = branchOf(element)
        if (mark != null) {
            inPath(path, mark.first, mark.second) {
                element.children.forEach { collect(it, out, seen, path) }
            }
            return
        }

        callOf(element)?.let { call ->
            val anchor: Any = call.sourcePsi ?: call
            if (seen.add(anchor)) out += CallEntry(call, guardOf(path), kindOf(path))
        }
        element.children.forEach { collect(it, out, seen, path) }
    }

    /** 用 PSI 类名识别分支节点：Java 与 Kotlin 类名不同但都含这些关键字，零编译期耦合 */
    private fun branchOf(element: PsiElement): Pair<BranchKind, String>? {
        val cls = element.javaClass.name
        val head = headOf(element)
        return when {
            cls.contains("PsiForStatement") || cls.contains("PsiForeachStatement") ||
                cls.contains("KtForExpression") -> BranchKind.LOOP to head.ifEmpty { "for" }
            cls.contains("PsiWhileStatement") || cls.contains("PsiDoWhileStatement") ||
                cls.contains("KtWhileExpression") || cls.contains("KtDoWhileExpression") ->
                BranchKind.LOOP to head.ifEmpty { "while" }
            cls.contains("PsiSwitchStatement") || cls.contains("KtWhenExpression") ->
                BranchKind.SWITCH to head.ifEmpty { "switch" }
            cls.contains("PsiConditionalExpression") -> BranchKind.TERNARY to head
            cls.contains("PsiTryStatement") || cls.contains("KtTryExpression") -> BranchKind.TRY to "try"
            else -> null
        }
    }

    private fun inPath(
        path: MutableList<Pair<BranchKind, String>>,
        kind: BranchKind,
        guard: String,
        visit: () -> Unit,
    ) {
        path.add(kind to guard)
        try {
            visit()
        } finally {
            path.removeAt(path.lastIndex)
        }
    }

    private fun guardOf(path: List<Pair<BranchKind, String>>): String =
        path.joinToString(" / ") { it.second }
    private fun kindOf(path: List<Pair<BranchKind, String>>): BranchKind? = path.lastOrNull()?.first

    /** 语句头部（第一个 `{` 之前）压成一行，长表达式截断，避免片段框标题被撑爆 */
    private fun headOf(element: PsiElement): String {
        val head = element.text.replace(Regex("\\s+"), " ").trim().substringBefore('{').trim()
        return if (head.length > 90) head.take(89) + "…" else head
    }

    private fun textOf(element: PsiElement?): String =
        element?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun callOf(element: PsiElement): UCallExpression? {
        val converted = runCatching { element.toUElement() }.getOrNull()
        if (converted is UCallExpression) return converted
        val selector = (converted as? UQualifiedReferenceExpression)?.selector as? UCallExpression
        if (selector != null) return selector
        // 兜底：显式按 UCallExpression 请求一次转换，覆盖其它 UAST 语言的复合表达式包装
        return runCatching {
            UastFacade.convertElement(element, null, UCallExpression::class.java) as? UCallExpression
        }.getOrNull()
    }

    /** 带接收者的 Java 调用被 UAST 拆成 qualified expression，receiver 可能挂在父节点上 */
    private fun receiverTextOf(call: UCallExpression): String {
        val direct = runCatching { call.receiver?.sourcePsi?.text }.getOrNull()
        if (!direct.isNullOrEmpty()) return direct
        var parent: UElement? = runCatching { call.uastParent }.getOrNull()
        var guard = 0
        while (parent != null && guard++ < 4) {
            if (parent is UQualifiedReferenceExpression) {
                return runCatching { parent.receiver.sourcePsi?.text }.getOrNull().orEmpty()
            }
            parent = runCatching { parent.uastParent }.getOrNull()
        }
        return ""
    }

    /** Java 匿名类 / 局部类，以及 Kotlin object literal：不并入当前方法的消息流 */
    private fun isAnonymousObject(element: PsiElement): Boolean =
        element is PsiClass || element.javaClass.name.contains("KtObjectLiteral")

    /** 调用接收者的声明类型，用来读 `UserMapper extends BaseMapper<User>` 里的实体泛型 */
    private fun receiverClassOf(call: UCallExpression): PsiClass? {
        val receiver = runCatching { call.receiver }.getOrNull()
            ?: runCatching { (call.uastParent as? UQualifiedReferenceExpression)?.receiver }.getOrNull()
        val type = runCatching { receiver?.getExpressionType() as? PsiClassType }.getOrNull() ?: return null
        return runCatching { type.resolve() }.getOrNull()
    }

    private fun walk(
        calls: List<CallEntry>,
        caller: String,
        depth: Int,
        diagram: SequenceDiagram,
        /** 非空时表示这段方法体属于某个「多实现」分支的展开，整段消息都归入那个 alt */
        implGuard: String? = null,
    ): Int {
        var last = -1
        for (entry in calls) {
            if (depth >= options.maxDepth) continue
            if (options.limitReached(diagram.messages.size)) {
                noteTruncated(diagram, caller)
                break
            }
            val call = entry.call
            val info = callInfoOf(call) ?: continue
            val impls = implementations(info.method)
            if (impls.size > 1) {
                last = recordMultiImpl(call, info, impls, caller, depth, diagram) ?: last
                continue
            }
            val impl = impls.singleOrNull()
            val target = impl ?: info.method
            if (options.skipTrivial && SequenceRules.isTrivial(target.name, info.args.size, info.receiverText)) {
                continue
            }
            // 基础类 / 工具类（String / List / HashMap / CollectionUtils …）：连箭头和生命线都不生成
            if (options.skipBasicClasses &&
                SequenceRules.isBasicClass(target.cls?.qualifiedName, target.cls?.name, options.basicClassRules)
            ) {
                continue
            }
            val callee = diagram.addParticipant(participantOf(target))
            val key = SequenceRules.methodKey(callee.qualified, target.name, target.paramCount)
            val recursive = stack.contains(key)
            // 多实现展开时统一归入本实现的 alt 片段（与 Java 版一致），不被内部 if / for 切碎
            val effectiveKind = if (implGuard != null) BranchKind.IMPL else entry.kind
            val effectiveGuard = if (implGuard != null) implGuard else entry.guard
            // 图上用省略后的短标签；文本导出走 fullLabel，长字符串参数不再被 `…` 截断
            val fullLabel = if (info.isConstructor) {
                SequenceRules.fullLabel("new ${target.name}", info.args)
            } else {
                SequenceRules.fullLabel(info.callName, info.args)
            }
            val label = SequenceRules.abbreviate(fullLabel)
            val index = diagram.addMessage(
                SeqMessage(
                    kind = if (info.isConstructor) MessageKind.CREATE else MessageKind.CALL,
                    from = caller,
                    to = callee.qualified,
                    label = label,
                    depth = depth,
                    dashed = impl != null,
                    recursive = recursive,
                    psiMethod = target.nav,
                    fullLabel = fullLabel,
                    branchKind = effectiveKind,
                    guard = effectiveGuard,
                ),
            )
            diagram.messages[index].endIndex = index
            last = index

            // 数据库层：Mapper 方法 → SQL / 表，插在本调用与下钻之间（不影响 projectOnly）
            val dbLast = databaseTrace.append(
                diagram, callee.qualified, depth + 1, target.javaMethod, receiverClassOf(call),
            )
            if (dbLast != null) {
                last = dbLast
                if (diagram.messages[index].endIndex < dbLast) {
                    diagram.messages[index].endIndex = dbLast
                }
            }

            val inProject = target.cls?.let(::isProjectClass) == true
            if (!recursive && depth < options.maxDepth && (!options.projectOnly || inProject)) {
                stack.addLast(key)
                try {
                    val end = walk(callsIn(target.body), callee.qualified, depth + 1, diagram, implGuard)
                    if (end >= 0) diagram.messages[index].endIndex = maxOf(diagram.messages[index].endIndex, end)
                } finally {
                    stack.removeLast()
                }
            }
            // Kotlin 的 Unit 等价于 Java 的 void，都不画返回箭头
            if (options.showReturns && caller != callee.qualified &&
                target.returnText != "void" && target.returnText != "kotlin.Unit" && target.returnText != "Unit"
            ) {
                val returned = diagram.addMessage(
                    SeqMessage(MessageKind.RETURN, callee.qualified, caller, "return", depth,
                        dashed = true, psiMethod = target.nav,
                        branchKind = effectiveKind, guard = effectiveGuard),
                )
                diagram.messages[returned].endIndex = returned
                last = returned
            }
        }
        return last
    }

    /**
     * 接口 / 抽象方法有**多个**实现时，把每个实现当成一条互斥分支分别展开，各自把 Mapper
     * 调用落到 SQL / 数据库表。以前 `singleOrNull()` 在多实现时返回 null，所有实现的 SQL 都丢了。
     */
    private fun recordMultiImpl(
        call: UCallExpression,
        info: CallInfo,
        impls: List<MethodInfo>,
        caller: String,
        depth: Int,
        diagram: SequenceDiagram,
    ): Int? {
        var drew = false
        for (impl in impls) {
            if (depth >= options.maxDepth) break
            if (options.limitReached(diagram.messages.size)) {
                noteTruncated(diagram, caller)
                break
            }
            if (options.skipBasicClasses &&
                SequenceRules.isBasicClass(impl.cls?.qualifiedName, impl.cls?.name, options.basicClassRules)
            ) {
                continue
            }
            val implName = impl.cls?.name ?: "impl"
            val callee = diagram.addParticipant(participantOf(impl))
            val key = SequenceRules.methodKey(callee.qualified, impl.name, impl.paramCount)
            val recursive = stack.contains(key)
            val fullLabel = SequenceRules.fullLabel(info.callName, info.args)
            val index = diagram.addMessage(
                SeqMessage(
                    kind = if (info.isConstructor) MessageKind.CREATE else MessageKind.CALL,
                    from = caller,
                    to = callee.qualified,
                    label = SequenceRules.abbreviate(fullLabel),
                    depth = depth,
                    dashed = true,
                    recursive = recursive,
                    psiMethod = impl.nav,
                    fullLabel = fullLabel,
                    branchKind = BranchKind.IMPL,
                    guard = implName,
                ),
            )
            diagram.messages[index].endIndex = index
            drew = true

            // 每个实现各自把 Mapper 调用落到 SQL / 数据库表——「多个 SQL」的来源
            val dbLast = databaseTrace.append(
                diagram, callee.qualified, depth + 1, impl.javaMethod, receiverClassOf(call),
            )
            if (dbLast != null && diagram.messages[index].endIndex < dbLast) {
                diagram.messages[index].endIndex = dbLast
            }

            val inProject = impl.cls?.let(::isProjectClass) == true
            if (!recursive && depth < options.maxDepth && (!options.projectOnly || inProject)) {
                stack.addLast(key)
                try {
                    val end = walk(callsIn(impl.body), callee.qualified, depth + 1, diagram, implName)
                    if (end >= 0) diagram.messages[index].endIndex = maxOf(diagram.messages[index].endIndex, end)
                } finally {
                    stack.removeLast()
                }
            }
            if (options.showReturns && caller != callee.qualified &&
                impl.returnText != "void" && impl.returnText != "kotlin.Unit" && impl.returnText != "Unit"
            ) {
                val returned = diagram.addMessage(
                    SeqMessage(MessageKind.RETURN, callee.qualified, caller, "return", depth,
                        dashed = true, psiMethod = impl.nav,
                        branchKind = BranchKind.IMPL, guard = implName),
                )
                diagram.messages[returned].endIndex = returned
            }
        }
        return if (drew) diagram.messages.lastIndex else null
    }

    // ---------- 过滤与判定 ----------

    /**
     * 智能接口：找出接口 / 抽象方法在本工程里所有带方法体的实现（0 / 1 / 多个）。
     * 多个实现由 [recordMultiImpl] 按互斥 alt 分支分别展开，各自带出 SQL。
     */
    private fun implementations(info: MethodInfo): List<MethodInfo> {
        if (!options.smartInterface || !info.isAbstract || info.body != null) return emptyList()
        return runCatching {
            OverridingMethodsSearch.search(info.javaMethod, GlobalSearchScope.projectScope(project), true)
                .findAll()
                .filter { it.body != null && it.containingClass?.isInterface != true }
                .distinctBy {
                    "${it.containingClass?.qualifiedName ?: it.containingClass?.name}.${it.name}" +
                        "(${it.parameterList.parametersCount})"
                }
                .map { methodInfo(it) }
        }.getOrDefault(emptyList())
    }

    private fun isProjectClass(cls: PsiClass): Boolean {
        val vFile = PsiUtilCore.getVirtualFile(cls) ?: return false
        if (!ProjectFileIndex.getInstance(project).isInContent(vFile)) return false
        val module = ModuleUtilCore.findModuleForPsiElement(cls) ?: return true
        val sdkHome = ModuleRootManager.getInstance(module).sdk?.homePath ?: return true
        return !vFile.path.startsWith(sdkHome)
    }

    private fun noteTruncated(diagram: SequenceDiagram, at: String) {
        if (diagram.truncated) return
        diagram.truncated = true
        note(diagram, at, "已达到 ${options.maxMessages} 条消息上限，剩余调用被截断", null)
    }

    private fun note(diagram: SequenceDiagram, at: String, text: String, nav: PsiElement?) {
        diagram.addMessage(SeqMessage(MessageKind.NOTE, at, at, text, psiMethod = nav))
    }

    companion object {
        /** 从光标所在元素向上找最近的 UMethod（Kotlin 的 KtNamedFunction 会转成 UMethod） */
        fun rootAt(element: PsiElement): UMethod? {
            var current: PsiElement? = element
            while (current != null) {
                val node = current
                val converted = runCatching { node.toUElement() }.getOrNull()
                if (converted is UMethod) return converted
                current = node.parent
            }
            return null
        }
    }
}
