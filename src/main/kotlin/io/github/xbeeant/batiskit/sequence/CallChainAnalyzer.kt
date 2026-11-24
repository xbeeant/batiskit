package io.github.xbeeant.batiskit.sequence

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiSwitchLabelStatement
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiUtilCore
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex

/**
 * 从一个 [PsiMethod] 出发做调用链分析，产出 [SequenceDiagram]（对标原版 SequencePlugin 的
 * `SequenceGenerator`，但直接走 Java PSI，不引 UAST）。
 *
 * 语义约定：
 * - 根方法是深度 0，每下钻一层 +1，到 [DiagramOptions.maxDepth] 为止；
 * - lambda 体视为**所在方法的延续**（里面的调用算当前参与者的消息）；匿名类 / 局部类的方法体
 *   不展开（那是另一条执行线），只画 `new` 创建消息；
 * - **递归守卫**：同一条调用链上再次到达已展开过的方法时，画一根标 `⟳` 的回边、不再下钻；
 * - **智能接口**：接口 / 抽象方法的唯一实现自动展开（虚线箭头指向实现类）；有多个实现时按
 *   互斥 `alt` 分支分别展开每个实现的方法体，各自带出 Mapper / SQL，不再只画一根接口箭头；
 * - **库代码不深入**：只展开本工程源码里的方法（JDK / 依赖 jar 只画一根箭头），可以在选项里关掉；
 * - **基础类跳过**：JDK / Kotlin 标准库 / `CollectionUtils` 这类工具类（String / List / HashMap …）
 *   的调用连箭头与生命线都不生成，不只是「不下钻」，可以在选项里关掉；
 * - 消息条数到上限就截断并补一条备注，防止大方法把图撑爆；
 * - **数据库层**：调用落到 MyBatis / MyBatis-Plus 的 Mapper 方法时，由 [DatabaseTrace]
 *   续上一条 SQL 箭头与数据库表参与者（可用选项 `traceDatabase` 关掉）。
 */
class CallChainAnalyzer(
    private val project: Project,
    private val options: DiagramOptions,
) {

    /** 一张图一个实例：复用 XML 索引 / Database 表查找的结果 */
    private val databaseTrace = DatabaseTrace(project, options)

    fun analyze(root: PsiMethod): SequenceDiagram {
        val diagram = SequenceDiagram()
        val rootClass = root.containingClass
        val rootParticipant = diagram.addParticipant(
            SeqParticipant(
                qualified = qualifiedOf(rootClass),
                display = displayOf(rootClass),
                isInterface = rootClass?.isInterface == true,
                psiClass = rootClass,
                fullDisplay = rootClass?.name ?: "anonymous",
            ),
        )
        val rootKey = methodKey(root)
        stack.addLast(rootKey)
        try {
            if (root.body == null) {
                // 起点本身就是 Mapper 接口方法时，SQL 在 XML / 注解里：照样画到数据库层
                val dbLast = databaseTrace.append(diagram, rootParticipant.qualified, 0, root)
                if (dbLast == null) {
                    diagram.addMessage(
                        SeqMessage(MessageKind.NOTE, rootParticipant.qualified, rootParticipant.qualified,
                            "方法没有方法体（接口 / 抽象方法），无调用链可展开", psiMethod = root),
                    )
                }
            } else {
                walk(root, rootParticipant.qualified, 0, diagram)
                if (diagram.messages.none { it.kind != MessageKind.NOTE }) {
                    diagram.addMessage(
                        SeqMessage(MessageKind.NOTE, rootParticipant.qualified, rootParticipant.qualified,
                            "方法体里没有发现可解析的方法调用", psiMethod = root),
                    )
                }
            }
        } finally {
            stack.removeLast()
        }
        diagram.seal()
        return diagram
    }

    // ---------- 遍历 ----------

    /** 递归守卫栈：当前调用链上已经展开过的方法 */
    private val stack = ArrayDeque<String>()

    /**
     * 遍历一个方法的函数体，把调用消息追加进 diagram，返回**最后一条**消息的下标
     * （调用方用它给根消息填 endIndex，布局层据此画激活条）。
     *
     * 分支信息不靠遍历期状态维护：平台的 `JavaRecursiveElementWalkingVisitor` 一旦重写
     * `visitIfStatement` 再手动 `accept` 子元素，子调用并不会在那个时机分派回
     * `visitMethodCallExpression`（之后会以空路径重新走一遍），维护的分支栈会整段丢掉。
     * 改为对每条调用读确定性的 PSI 祖先链（[branchInfoOf]），不受遍历机制影响；
     * 下钻到别的方法体时重新起一条链，分支也不跨方法继承。
     */
    private fun walk(
        method: PsiMethod,
        participant: String,
        depth: Int,
        diagram: SequenceDiagram,
        /** 非空时表示这段方法体属于某个「多实现」分支的展开，整段消息都归入那个 alt */
        implGuard: String? = null,
    ): Int {
        if (method.body == null) return -1
        var last = -1
        method.body!!.accept(object : JavaRecursiveElementWalkingVisitor() {

            override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                super.visitMethodCallExpression(call)
                val target = call.methodExpression.resolve() as? PsiMethod ?: return
                val (branchKind, guard) = branchInfoOf(call)
                // 多实现展开时，实现体里的所有消息统一打上 IMPL + 实现名：fragments() 才能把
                // 「实现箭头 + 实现体调用 + SQL + 返回」合并成一块完整的 alt，而不是被内部
                // if / for 的分支标记切碎，也不会漏掉任何一条 SQL。
                val effectiveKind = if (implGuard != null) BranchKind.IMPL else branchKind
                val effectiveGuard = if (implGuard != null) implGuard else guard
                last = recordCall(call, target, participant, depth, diagram, effectiveGuard, effectiveKind,
                    implGuard) ?: return
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val (branchKind, guard) = branchInfoOf(expression)
                val effectiveKind = if (implGuard != null) BranchKind.IMPL else branchKind
                val effectiveGuard = if (implGuard != null) implGuard else guard
                last = recordNew(expression, participant, depth, diagram, effectiveGuard, effectiveKind,
                    implGuard) ?: return
            }

            /** 匿名类 / 局部类的方法体是另一条执行线，不并入当前方法的消息流 */
            override fun visitClass(aClass: PsiClass) {
                // 不递归进去
            }

            override fun visitLambdaExpression(expression: PsiLambdaExpression) {
                // lambda 体当作所在方法的延续：继续递归即可
                super.visitLambdaExpression(expression)
            }

        })
        return last
    }

    /**
     * 从一条调用的 PSI 祖先链反推它所在的条件 / 分支，返回「最内层分支类型」与
     * 「由外到内用 ` / ` 拼起来的条件原文」。
     *
     * - 条件表达式里的调用（如 `if (flag && service.check())`）不算任何分支，但仍会照常画箭头；
     * - 只有落在 then / else / 循环体 / case / try 块里的调用才带分支标记，条件原文留在 guard；
     * - 嵌套分支按祖先顺序拼接；遇到 [PsiMethod] / [PsiClass] 就停，分支不跨方法、不跨匿名类。
     *
     * 这样分支信息完全由 PSI 树结构决定，与 visitor 的遍历时机无关。
     */
    private fun branchInfoOf(call: PsiElement): Pair<BranchKind?, String> {
        val parts = ArrayList<Pair<BranchKind, String>>()
        var node: PsiElement? = call
        var stop = false
        while (!stop) {
            val parent = node?.parent ?: break
            when (parent) {
                is PsiMethod, is PsiClass -> stop = true
                is PsiIfStatement -> {
                    if (!inside(call, parent.condition)) {
                        when {
                            inside(call, parent.thenBranch) ->
                                parts.add(0, BranchKind.IF to "if (${textOf(parent.condition)})")
                            parent.elseBranch?.let { inside(call, it) } == true ->
                                parts.add(0, BranchKind.ELSE to "else")
                        }
                    }
                }
                is PsiForStatement -> if (inside(call, parent.body)) {
                    parts.add(0, BranchKind.LOOP to "for (${textOf(parent.condition)})")
                }
                is PsiForeachStatement -> if (inside(call, parent.body)) {
                    parts.add(0, BranchKind.LOOP to
                        "for (${textOf(parent.iterationParameter)} : ${textOf(parent.iteratedValue)})")
                }
                is PsiWhileStatement -> if (inside(call, parent.body)) {
                    parts.add(0, BranchKind.LOOP to "while (${textOf(parent.condition)})")
                }
                is PsiDoWhileStatement -> if (inside(call, parent.body)) {
                    parts.add(0, BranchKind.LOOP to "do while (${textOf(parent.condition)})")
                }
                is PsiSwitchStatement -> {
                    val label = switchLabelOf(call, parent)
                    parts.add(0, (if (label != null) BranchKind.CASE else BranchKind.SWITCH) to
                        (label ?: "switch (${textOf(parent.expression)})"))
                }
                is PsiConditionalExpression -> if (!inside(call, parent.condition)) {
                    parts.add(0, BranchKind.TERNARY to textOf(parent.condition))
                }
                is PsiTryStatement -> {
                    val guard = when {
                        parent.tryBlock?.let { inside(call, it) } == true -> "try"
                        parent.catchBlocks.any { inside(call, it) } -> "catch"
                        parent.finallyBlock?.let { inside(call, it) } == true -> "finally"
                        else -> null
                    }
                    if (guard != null) {
                        val kind = when (guard) {
                            "catch" -> BranchKind.CATCH
                            "finally" -> BranchKind.FINALLY
                            else -> BranchKind.TRY
                        }
                        parts.add(0, kind to guard)
                    }
                }
            }
            node = parent
        }
        // add(0, …) 从内向外走，末尾是最内层分支、开头是最外层，guard 也按「外 → 内」拼接
        return (parts.lastOrNull()?.first) to parts.joinToString(" / ") { it.second }
    }

    /** [element] 是否等于或位于 [container] 之内 */
    private fun inside(element: PsiElement, container: PsiElement?): Boolean {
        if (container == null) return false
        var cur: PsiElement? = element
        while (cur != null) {
            if (cur === container) return true
            cur = cur.parent
        }
        return false
    }

    /** 找到调用所在的 `case` / `default` 标签：switch body 里位于它之前的最近一个 */
    private fun switchLabelOf(call: PsiElement, switch: PsiSwitchStatement): String? {
        val body = switch.body ?: return null
        var child: PsiElement? = call
        while (child != null && child.parent !== body) child = child.parent
        if (child == null) return null
        val children = body.children
        val idx = children.indexOf(child)
        if (idx < 0) return null
        for (i in idx - 1 downTo 0) {
            val c = children[i]
            if (c is PsiSwitchLabelStatement) return textOf(c).removeSuffix(":").trim()
        }
        return null
    }

    /**
     * 记录一次方法调用并按选项下钻。返回该消息的下标；被过滤 / 截断时返回 null（调用方跳过）。
     * 目标若有多个实现，交给 [recordMultiImpl] 按互斥分支分别展开，各自带出 SQL。
     */
    private fun recordCall(
        call: PsiCall,
        target: PsiMethod,
        caller: String,
        depth: Int,
        diagram: SequenceDiagram,
        guard: String = "",
        branchKind: BranchKind? = null,
        implGuard: String? = null,
    ): Int? {
        if (options.skipTrivial && isTrivialCall(call, target)) {
            return null
        }
        if (depth >= options.maxDepth) {
            return null
        }
        if (options.limitReached(diagram.messages.size)) {
            noteTruncated(diagram, caller)
            return null
        }
        val impls = implementations(target)
        if (impls.size > 1) {
            return recordMultiImpl(call, impls, caller, depth, diagram)
        }
        val impl = impls.singleOrNull()
        val body = impl ?: target
        val bodyClass = body.containingClass
        // 基础类 / 工具类（String / List / HashMap / CollectionUtils …）：连箭头和生命线都不生成。
        // 与 projectOnly 不同——后者只是不下钻、仍留一根箭头。判断展开后的 bodyClass，
        // JDK 接口若被「智能接口」展开成本工程的实现类，仍按业务代码保留。
        if (options.skipBasicClasses &&
            SequenceRules.isBasicClass(bodyClass?.qualifiedName, bodyClass?.name, options.basicClassRules)
        ) {
            return null
        }
        val callee = diagram.addParticipant(
            SeqParticipant(
                qualified = qualifiedOf(bodyClass),
                display = displayOf(bodyClass),
                isInterface = bodyClass?.isInterface == true || body.hasModifierProperty(PsiModifier.ABSTRACT),
                psiClass = bodyClass,
                fullDisplay = bodyClass?.name ?: "anonymous",
            ),
        )
        val key = methodKey(body)
        val isRecursive = stack.contains(key)
        val message = diagram.addMessage(
            SeqMessage(
                kind = MessageKind.CALL,
                from = caller,
                to = callee.qualified,
                label = callLabel(call),
                depth = depth,
                dashed = impl != null,
                recursive = isRecursive,
                psiMethod = body,
                fullLabel = callText(call),
                branchKind = branchKind,
                guard = guard,
            ),
        )
        diagram.messages[message].endIndex = message

        // 数据库层：Mapper 方法继续落到 SQL / 数据库表。插在本调用与它的下钻之间，
        // 保证时序是「调用 Mapper → 执行 SQL → 返回结果」，并且 projectOnly 不影响它
        // （BaseMapper / IService 的方法本来就来自依赖 jar）。
        val dbLast = databaseTrace.append(
            diagram, callee.qualified, depth + 1, body, receiverClassOf(call),
        )

        // 下钻条件：非递归回边、没到深度上限、本工程代码（或允许展开库代码）
        val inProject = bodyClass?.let { isProjectClass(it) } == true
        if (!isRecursive && depth < options.maxDepth && (!options.projectOnly || inProject)) {
            stack.addLast(key)
            try {
                val end = walk(body, callee.qualified, depth + 1, diagram, implGuard)
                if (end >= 0) {
                    diagram.messages[message].endIndex = maxOf(diagram.messages[message].endIndex, end)
                }
            } finally {
                stack.removeLast()
            }
        }
        // 返回箭头：非 void 且不是自调用才画
        if (options.showReturns && caller != callee.qualified && target.returnType?.canonicalText != "void") {
            diagram.addMessage(
                SeqMessage(MessageKind.RETURN, callee.qualified, caller, "return", depth, dashed = true,
                    psiMethod = body, branchKind = branchKind, guard = guard),
            ).also { diagram.messages[it].endIndex = it }
        }
        // SQL 箭头落在激活区间内；没有数据库层时返回原来的 message 下标，旧行为逐字不变
        if (dbLast != null) {
            if (diagram.messages[message].endIndex < dbLast) {
                diagram.messages[message].endIndex = dbLast
            }
            return maxOf(message, dbLast)
        }
        return message
    }

    /**
     * 接口 / 抽象方法有**多个**实现时，把每个实现当成一条互斥分支分别展开：每个实现一根
     * 虚线箭头（[BranchKind.IMPL]），随后下钻它自己的方法体，方法体里的 Mapper 调用由
     * [DatabaseTrace] 各自带出 SQL 箭头与数据库表参与者。
     *
     * 以前 `uniqueImpl()` 用 `singleOrNull()`，多实现直接返回 null → 只画一根到接口的箭头、
     * 一个实现都不展开，各实现体里的 SQL 于是一条都出不来（用户反馈「只扫描出来实现之一」）。
     */
    private fun recordMultiImpl(
        call: PsiCall,
        impls: List<PsiMethod>,
        caller: String,
        depth: Int,
        diagram: SequenceDiagram,
    ): Int? {
        var drew = false
        for (impl in impls) {
            if (options.limitReached(diagram.messages.size)) {
                noteTruncated(diagram, caller)
                break
            }
            val implClass = impl.containingClass
            if (options.skipBasicClasses &&
                SequenceRules.isBasicClass(implClass?.qualifiedName, implClass?.name, options.basicClassRules)
            ) {
                continue
            }
            val implName = implClass?.name ?: "impl"
            val callee = diagram.addParticipant(
                SeqParticipant(
                    qualified = qualifiedOf(implClass),
                    display = displayOf(implClass),
                    isInterface = false,
                    psiClass = implClass,
                    fullDisplay = implClass?.name ?: "anonymous",
                ),
            )
            val key = methodKey(impl)
            val isRecursive = stack.contains(key)
            val message = diagram.addMessage(
                SeqMessage(
                    kind = MessageKind.CALL,
                    from = caller,
                    to = callee.qualified,
                    label = callLabel(call),
                    depth = depth,
                    dashed = true,
                    recursive = isRecursive,
                    psiMethod = impl,
                    fullLabel = callText(call),
                    branchKind = BranchKind.IMPL,
                    guard = implName,
                ),
            )
            diagram.messages[message].endIndex = message
            drew = true

            // 每个实现各自把 Mapper 调用落到 SQL / 数据库表——这是「多个 SQL」的来源
            val dbLast = databaseTrace.append(
                diagram, callee.qualified, depth + 1, impl, receiverClassOf(call),
            )

            val inProject = implClass?.let { isProjectClass(it) } == true
            if (!isRecursive && depth < options.maxDepth && (!options.projectOnly || inProject)) {
                stack.addLast(key)
                try {
                    // implGuard 让实现体里的每条消息都归入本实现的 alt 片段，不被内部 if / for 切碎
                    val end = walk(impl, callee.qualified, depth + 1, diagram, implName)
                    if (end >= 0) {
                        diagram.messages[message].endIndex = maxOf(diagram.messages[message].endIndex, end)
                    }
                } finally {
                    stack.removeLast()
                }
            }
            if (options.showReturns && caller != callee.qualified &&
                impl.returnType?.canonicalText != "void"
            ) {
                diagram.addMessage(
                    SeqMessage(MessageKind.RETURN, callee.qualified, caller, "return", depth, dashed = true,
                        psiMethod = impl, branchKind = BranchKind.IMPL, guard = implName),
                ).also { diagram.messages[it].endIndex = it }
            }
            if (dbLast != null && diagram.messages[message].endIndex < dbLast) {
                diagram.messages[message].endIndex = dbLast
            }
        }
        return if (drew) diagram.messages.lastIndex else null
    }

    /** 调用接收者的声明类型：`UserMapper extends BaseMapper<User>` 的泛型实体只能从这里读到 */
    private fun receiverClassOf(call: PsiCall): PsiClass? {
        val expression = (call as? PsiMethodCallExpression)?.methodExpression?.qualifierExpression ?: return null
        val type = runCatching { expression.type as? PsiClassType }.getOrNull() ?: return null
        return runCatching { type.resolve() }.getOrNull()
    }

    /** 记录一次 `new X()` 构造调用：画创建消息，构造函数体照常下钻 */
    private fun recordNew(
        expression: PsiNewExpression,
        caller: String,
        depth: Int,
        diagram: SequenceDiagram,
        guard: String = "",
        branchKind: BranchKind? = null,
        implGuard: String? = null,
    ): Int? {
        if (depth >= options.maxDepth) {
            return null
        }
        if (options.limitReached(diagram.messages.size)) {
            noteTruncated(diagram, caller)
            return null
        }
        val constructor = expression.resolveMethod()
        val cls = constructor?.containingClass
            ?: (expression.classReference?.resolve() as? PsiClass)
        // `new HashMap<>()` / `new ArrayList<>()` 这类基础容器同样不进图。
        // 构造器解析不到 PsiClass（stub / 部分 mock JDK）时退回类型引用的简单名兜底，
        // 否则 `cls == null` 会让这些调用漏过过滤，留下 `new ArrayList<>()` 与 anonymous 生命线。
        if (options.skipBasicClasses) {
            val typeRef = expression.classReference?.text?.substringBefore('<')?.trim().orEmpty()
            if (SequenceRules.isBasicClass(cls?.qualifiedName, cls?.name, options.basicClassRules) ||
                SequenceRules.isBasicClass(typeRef, rules = options.basicClassRules)
            ) {
                return null
            }
        }
        val anonymous = expression.anonymousClass != null
        val qualified = if (anonymous) {
            "anonymous ${expression.classReference?.text ?: "object"}"
        } else {
            qualifiedOf(cls)
        }
        val display = if (anonymous) {
            "${expression.classReference?.text ?: "object"} (匿名)"
        } else {
            displayOf(cls)
        }
        val callee = diagram.addParticipant(
            SeqParticipant(
                qualified, display, psiClass = cls,
                fullDisplay = cls?.name ?: display,
            ),
        )
        val typeText = (expression.classReference?.text ?: cls?.name ?: display)
            .replace("\r", " ").replace("\n", " ").trim()
        val newText = "new $typeText()"
        val message = diagram.addMessage(
            SeqMessage(
                kind = MessageKind.CREATE,
                from = caller,
                to = callee.qualified,
                label = abbreviate(newText),
                depth = depth,
                psiMethod = constructor,
                fullLabel = newText,
                branchKind = branchKind,
                guard = guard,
            ),
        )
        diagram.messages[message].endIndex = message
        // 匿名类的构造函数没有可下钻的方法体；普通类且在本工程时继续走构造函数体
        if (!anonymous && constructor != null && depth < options.maxDepth &&
            (!options.projectOnly || isProjectClass(cls!!))
        ) {
            val key = methodKey(constructor)
            if (!stack.contains(key)) {
                stack.addLast(key)
                try {
                    val end = walk(constructor, callee.qualified, depth + 1, diagram, implGuard)
                    if (end >= 0) {
                        diagram.messages[message].endIndex = end
                    }
                } finally {
                    stack.removeLast()
                }
            }
        }
        return message
    }

    // ---------- 过滤与判定 ----------

    /** getter / setter / toString / 日志这类噪声调用 */
    private fun isTrivialCall(call: PsiCall, method: PsiMethod): Boolean {
        val name = method.name
        if (name == "toString" || name == "hashCode" || name == "equals" || name == "getClass") {
            return true
        }
        val argCount = (call as? PsiMethodCallExpression)?.argumentList?.expressions?.size ?: 0
        if ((name.startsWith("get") || name.startsWith("is")) && name.length > 3 && argCount == 0) {
            return true
        }
        if (name.startsWith("set") && name.length > 3 && argCount == 1) {
            return true
        }
        // 日志调用：方法名是日志词，且接收者像 logger（变量名 / 类型名）
        if (name in LOG_METHODS) {
            val receiver = (call as? PsiMethodCallExpression)?.methodExpression?.qualifierExpression
            val receiverText = receiver?.text?.lowercase().orEmpty()
            val receiverType = receiver?.type?.presentableText?.lowercase().orEmpty()
            if ("log" in receiverText || "logger" in receiverText || "logger" in receiverType) {
                return true
            }
        }
        return false
    }

    /**
     * 智能接口：找出接口 / 抽象方法在本工程里所有带方法体的实现。
     * - 0 个：保持旧行为，只画一根到接口的箭头；
     * - 1 个：虚线箭头指向实现类并展开（旧行为）；
     * - 多个：每个实现一条互斥 `alt` 分支，分别展开方法体与 SQL（[recordMultiImpl]）。
     */
    private fun implementations(target: PsiMethod): List<PsiMethod> {
        if (!options.smartInterface) {
            return emptyList()
        }
        val cls = target.containingClass ?: return emptyList()
        val isAbstractMethod = cls.isInterface || target.hasModifierProperty(PsiModifier.ABSTRACT)
        if (!isAbstractMethod || target.body != null) {
            return emptyList()
        }
        return runCatching {
            OverridingMethodsSearch.search(target, GlobalSearchScope.projectScope(project), true)
                .findAll()
                .filter { it.body != null && it.containingClass?.isInterface != true }
                .distinctBy { methodKey(it) }
        }.getOrDefault(emptyList())
    }

    private fun isProjectClass(cls: PsiClass): Boolean {
        val vFile = PsiUtilCore.getVirtualFile(cls) ?: return false
        if (!ProjectFileIndex.getInstance(project).isInContent(vFile)) {
            return false
        }
        // SDK home 里的类一律视为库代码（测试环境的 mock JDK 源码恰好会落在内容根之下，
        // 生产环境的 JDK / 依赖则本来就走 isInContent = false 的分支）
        val module = ModuleUtilCore.findModuleForPsiElement(cls) ?: return true
        val sdkHome = ModuleRootManager.getInstance(module).sdk?.homePath ?: return true
        return !vFile.path.startsWith(sdkHome)
    }

    private fun noteTruncated(diagram: SequenceDiagram, at: String) {
        if (diagram.truncated) {
            return
        }
        diagram.truncated = true
        diagram.addMessage(
            SeqMessage(MessageKind.NOTE, at, at, "已达到 ${options.maxMessages} 条消息上限，剩余调用被截断"),
        )
    }

    // ---------- 标签与身份 ----------

    /** 分支条件原文压成一行：换行 / 缩进会撑爆片段框标题，但方法调用与字面量都保留 */
    private fun textOf(element: PsiElement?): String =
        element?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    /** 完整调用标签：长字符串字面量原样保留，只把换行压成空格（文本导出用） */
    private fun callText(call: PsiCall): String {
        val callExpr = call as? PsiMethodCallExpression ?: return "call()"
        val name = callExpr.methodExpression.referenceName ?: "call"
        val args = callExpr.argumentList.expressions.joinToString(", ") {
            it.text.replace("\r", " ").replace("\n", " ").trim()
        }
        return if (args.isEmpty()) "$name()" else "$name($args)"
    }

    /** 图形页签用的短标签：只有画不下时才省略，模型里同时存着 [callText] */
    private fun callLabel(call: PsiCall): String = abbreviate(callText(call))

    private fun abbreviate(text: String): String =
        if (text.length > MAX_LABEL) text.take(MAX_LABEL - 1) + "…" else text

    private fun methodKey(method: PsiMethod): String =
        "${qualifiedOf(method.containingClass)}.${method.name}(${method.parameterList.parametersCount})"

    private fun qualifiedOf(cls: PsiClass?): String = cls?.qualifiedName
        ?: cls?.name
        ?: "anonymous"

    private fun displayOf(cls: PsiClass?): String {
        val name = cls?.name ?: return "anonymous"
        return if (name.length > MAX_DISPLAY) name.take(MAX_DISPLAY - 1) + "…" else name
    }

    companion object {
        private val LOG_METHODS = setOf("debug", "info", "warn", "error", "trace", "log")
        private const val MAX_LABEL = 60
        private const val MAX_DISPLAY = 28
    }
}
