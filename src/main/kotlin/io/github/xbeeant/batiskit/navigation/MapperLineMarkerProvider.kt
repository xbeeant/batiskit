package io.github.xbeeant.batiskit.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.config.MapperAssistSettings

/**
 * Mapper 接口 / 方法 ↔ Mapper XML 语句片段的侧栏导航图标（参考 MybatisX）：
 *
 *  - Mapper 接口声明行：跳转到对应 XML 文件的根标签（namespace 匹配）；
 *  - Mapper 方法声明行：跳转到同 id 的语句片段（多个时弹窗选择）；
 *  - XML 语句片段行：反向跳转到 Mapper 接口方法；
 *  - `<sql id>` 定义行：点击查看它被哪些 `<include refid>` 引用；
 *  - `<include refid>` 行：点击查看该片段的定义与**全部**使用处（含其它 Mapper XML）。
 */
class MapperLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        // 总开关：同时装了 MybatisX 等插件时关掉，避免侧栏出现两套图标
        if (!MapperAssistSettings.getInstance().state.navigationIconsEnabled) return
        // 一次 pass 先建好整份 XML 索引，后面每个方法 / 标签都复用它，不再逐个扫 FileTypeIndex
        elements.firstOrNull()?.let(MapperXmlResolver::prepareIndex)
        // 单个 `<sql>` 往往被多处 include；一次 pass 内缓存解析结果，别为每个 include 重扫一遍 XML 索引
        val definitionCache = HashMap<String, List<XmlTag>>()
        val usageCache = HashMap<XmlTag, List<XmlTag>>()

        fun definitionsOf(include: XmlTag, ref: String): List<XmlTag> {
            val key = "${include.containingFile?.virtualFile?.path.orEmpty()}#$ref"
            return definitionCache.getOrPut(key) {
                runCatching { MapperXmlResolver.sqlDefinitions(include, ref) }
                    .getOrDefault(emptyList<XmlTag>())
            }
        }

        fun usagesOf(definition: XmlTag): List<XmlTag> = usageCache.getOrPut(definition) {
            runCatching { MapperXmlResolver.sqlUsages(definition) }
                .getOrDefault(emptyList<XmlTag>())
        }

        for (element in elements) {
            when (element) {
                is PsiClass -> {
                    if (!element.isInterface) {
                        continue
                    }
                    val root = MapperXmlResolver.xmlRoot(element) ?: continue
                    val fileName = root.containingFile?.name ?: "Mapper XML"
                    result += NavigationGutterIconBuilder.create(AllIcons.FileTypes.Xml)
                        .setTargets(root)
                        .setTooltipText("跳转到 Mapper XML（$fileName）")
                        .createLineMarkerInfo(element.nameIdentifier ?: element)
                }
                is PsiMethod -> {
                    val statements = MapperXmlResolver.statements(element)
                    if (statements.isEmpty()) {
                        continue
                    }
                    result += NavigationGutterIconBuilder.create(AllIcons.FileTypes.Xml)
                        .setTargets(statements)
                        .setTooltipText("跳转到 XML 语句片段（id = ${element.name}）")
                        .createLineMarkerInfo(element.nameIdentifier ?: element)
                }
                is XmlTag -> {
                    if (MapperXmlResolver.isStatementTag(element)) {
                        val method = MapperXmlResolver.method(element) ?: continue
                        val owner = method.containingClass?.name ?: continue
                        // 侧栏图标只能挂在叶子元素上：XmlTag 是复合元素，拿它的第一个 token（`<`）当锚点，
                        // 否则平台会报 "LineMarker is supposed to be registered for leaf elements only"
                        val anchor = element.firstChild ?: continue
                        result += NavigationGutterIconBuilder.create(AllIcons.FileTypes.Java)
                            .setTargets(method)
                            .setTooltipText("跳转到 Mapper 方法（$owner#${method.name}）")
                            .createLineMarkerInfo(anchor)
                        continue
                    }
                    // <resultMap> 定义行：查看它被哪些语句 / extends / 嵌套 resultMap 引用
                    if (element.name == "resultMap" && !element.getAttributeValue("id").isNullOrBlank()) {
                        val usages = MapperXmlResolver.resultMapUsages(element)
                        if (usages.isNotEmpty()) {
                            val anchor = element.firstChild ?: continue
                            val id = element.getAttributeValue("id")
                            result += NavigationGutterIconBuilder.create(AllIcons.Actions.Find)
                                .setTargets(usages)
                                .setTooltipText("resultMap「$id」被引用 ${usages.size} 处，点击查看")
                                .createLineMarkerInfo(anchor)
                        }
                    }
                    // <sql id="…"> 定义：一次点开就能看到「哪些地方用到了这个片段」
                    // 没有引用时也留一个图标，明确告诉用户 0 处，而不是让人以为功能没生效
                    if (element.name == "sql" && !element.getAttributeValue("id").isNullOrBlank()) {
                        val id = element.getAttributeValue("id")?.trim().orEmpty()
                        val usages = usagesOf(element)
                        val anchor = element.firstChild ?: continue
                        val targets: List<XmlTag> = if (usages.isEmpty()) listOf(element) else usages
                        val tooltip = if (usages.isEmpty()) {
                            "sql「$id」暂无 <include> 引用"
                        } else {
                            "sql「$id」被 include ${usages.size} 处，点击查看全部引用"
                        }
                        result += NavigationGutterIconBuilder.create(AllIcons.Actions.Find)
                            .setTargets(targets)
                            .setTooltipText(tooltip)
                            .createLineMarkerInfo(anchor)
                        continue
                    }
                    // <include refid>：把定义与这个片段的所有使用处一起列出来，
                    // 从任一引用点都能看到「还有谁在用同一段 SQL」。Ctrl+B 仍走 SqlIncludeReference 只跳定义
                    if (element.name == "include") {
                        val ref = element.getAttributeValue("refid")?.trim().orEmpty()
                        if (ref.isBlank()) continue
                        val definition = definitionsOf(element, ref).firstOrNull()
                        val usages = definition?.let { usagesOf(it) }.orEmpty()
                        val anchor = element.firstChild ?: continue
                        val targets = ArrayList<XmlTag>()
                        if (definition != null) targets += definition
                        for (usage in usages) {
                            if (usage !== definition) targets += usage
                        }
                        if (targets.isEmpty()) targets += element
                        val tooltip = if (definition == null) {
                            "refid「$ref」没有找到对应的 <sql id> 定义"
                        } else {
                            "refid「$ref」：定义 1 处 · 共被 include ${usages.size} 处，点击查看定义与全部引用"
                        }
                        result += NavigationGutterIconBuilder.create(AllIcons.Actions.Find)
                            .setTargets(targets)
                            .setTooltipText(tooltip)
                            .createLineMarkerInfo(anchor)
                        continue
                    }
                }
            }
        }
    }
}
