package io.github.xbeeant.batiskit.refactor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReferenceProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * Mapper XML 的引用体系：让平台把「语句 id / namespace / resultMap 引用 / Java 类型属性」
 * 当成真正的引用来对待（对标 rockjava/intellij-mybatis-plugin 的 rename 与 find usage 能力）：
 *
 *  - 语句标签（select / insert / update / delete）的 `id` 属性 → 接口方法：
 *    Find Usages、Ctrl+B、重命名方法时同步改写 XML 语句 id；
 *  - 根标签 `namespace` 属性 → 接口类：重命名接口时 namespace 跟着更新；
 *  - `resultMap` / `extends` / `<association resultMap>` 引用 → `<resultMap>` 定义：
 *    定义重命名时（走 [MapperRenameHandler]）引用处同步更新；
 *  - `<include refid>` → `<sql id>` 片段定义：Ctrl+B / Find Usages，
 *    定义重命名时（走 [MapperRenameHandler]）短名与全限定引用处同步更新；
 *  - `resultType` / `parameterType` / `ofType` / `javaType` / `<resultMap type>` → Java 类：
 *    类名补全与跟随重命名由平台 JavaClassReference 承载（rockjava 的「resultType 校正」）。
 *
 * 引用只在 Mapper XML（根标签是 `mapper`）里生成，其它 XML 一律放行，避免拖慢无关文件。
 */
class MapperReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            MapperAttrReferenceProvider
        )
    }
}

/** 属性值引用的统一入口：按属性名与所在标签分发到具体引用实现 */
private object MapperAttrReferenceProvider : PsiReferenceProvider() {

    /** 挂在 Java 类型属性上的引用提供者（resolve / 补全 / 重命名全部由平台实现承载） */
    private val javaClassReferenceProvider = JavaClassReferenceProvider()

    /** 按 Java 类名解析的属性：resultType / parameterType / ofType / javaType / resultMap 的 type */
    private val JAVA_TYPE_ATTRS = setOf("resultType", "parameterType", "ofType", "javaType", "type")

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val tag = attribute.parent as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        val file = value.containingFile as? XmlFile ?: return PsiReference.EMPTY_ARRAY
        if (file.rootTag?.name != "mapper") {
            return PsiReference.EMPTY_ARRAY
        }
        val name = attribute.localName
        return when {
            // 语句 id → 接口方法
            name == "id" && MapperXmlResolver.isStatementTag(tag) ->
                arrayOf(StatementIdReference(value, tag))

            // 根标签 namespace → 接口类（引用只覆盖 FQN 的最后一段）
            name == "namespace" && tag.name == "mapper" ->
                arrayOf(NamespaceReference(value))

            // resultMap 引用（语句的 resultMap 属性 / extends / 嵌套 association 与 collection）
            name == "resultMap" && (MapperXmlResolver.isStatementTag(tag) ||
                tag.name == "association" || tag.name == "collection") ->
                arrayOf(ResultMapReference(value, tag))

            name == "extends" && tag.name == "resultMap" ->
                arrayOf(ResultMapReference(value, tag))

            // <include refid="…"> → <sql id="…">；全限定引用只覆盖最后一段，Ctrl+B / F2 都能定位
            name == "refid" && tag.name == "include" ->
                arrayOf(SqlIncludeReference(value, tag))

            // Java 类型属性 → JavaClassReference（补全 / resolve / rename 全由平台承载）
            name in JAVA_TYPE_ATTRS && (name != "type" || tag.name == "resultMap") ->
                javaClassReferenceProvider.getReferencesByElement(value, context)

            else -> PsiReference.EMPTY_ARRAY
        }
    }
}

/**
 * 改写属性值内容的公共能力：重命名目标后，按引用覆盖的范围改回字面量。
 * 覆盖范围用 [ElementManipulators] 定位（自动处理引号）。
 */
private fun rewriteReferenceText(reference: PsiReferenceBase<XmlAttributeValue>, newName: String): XmlAttributeValue =
    ElementManipulators.handleContentChange(reference.element, reference.rangeInElement, newName)

/** 引用覆盖的文本段：普通属性是引号内的整段；带 `.` 的值只覆盖最后一段（FQN 的简单名部分） */
internal fun valueRangeOf(value: XmlAttributeValue): TextRange {
    val inner = ElementManipulators.getValueTextRange(value)
    val text = value.value.orEmpty()
    val lastDot = text.lastIndexOf('.')
    return if (lastDot < 0) {
        inner
    } else {
        TextRange(inner.startOffset + lastDot + 1, inner.endOffset)
    }
}

/** 语句 `id` 属性值 → 接口方法：重命名方法时所有 XML 语句 id 由平台引用机制同步改写 */
private class StatementIdReference(
    value: XmlAttributeValue,
    private val statement: XmlTag,
) : PsiReferenceBase<XmlAttributeValue>(value, ElementManipulators.getValueTextRange(value)) {

    override fun resolve(): PsiElement? = MapperXmlResolver.method(statement)

    override fun getVariants(): Array<Any> = MapperXmlResolver.statementIdVariants(statement).toTypedArray()

    override fun handleElementRename(newName: String): PsiElement = rewriteReferenceText(this, newName)
}

/** `namespace` 属性值 → 接口类：只覆盖 FQN 最后一段，重命名接口简单名时同步更新 */
private class NamespaceReference(value: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(value, valueRangeOf(value)) {

    override fun resolve(): PsiElement? {
        val root = (element.containingFile as? XmlFile)?.rootTag ?: return null
        return MapperXmlResolver.interfaceOf(root)
    }

    override fun getVariants(): Array<Any> = MapperXmlResolver.namespaceVariants(element).toTypedArray()

    override fun handleElementRename(newName: String): PsiElement = rewriteReferenceText(this, newName)
}

/**
 * resultMap 引用属性值 → `<resultMap>` 定义标签。
 * 定义侧的重命名（id 字面量上 Shift+F6）走 [MapperRenameHandler]，由它回写这里的引用处。
 *
 * 用**软引用**：定义可能在同 namespace 的兄弟 XML、依赖 jar 的 mapper XML 或还没进索引的文件里，
 * 「静态解析不到」不等于「写错了」。硬引用会让平台对这些合法写法报
 * `Cannot resolve symbol 'ResultMapWithBLOBs'`，与本插件「宁漏不误」的口径冲突。
 */
private class ResultMapReference(
    value: XmlAttributeValue,
    private val anchor: XmlTag,
) : PsiReferenceBase<XmlAttributeValue>(value, valueRangeOf(value), true) {

    override fun resolve(): PsiElement? {
        val ref = element.value?.trim().orEmpty()
        if (ref.isBlank()) {
            return null
        }
        return MapperXmlResolver.resultMapDefinitions(anchor, ref).firstOrNull()
    }

    override fun getVariants(): Array<Any> = MapperXmlResolver.resultMapIdVariants(anchor).toTypedArray()

    override fun handleElementRename(newName: String): PsiElement = rewriteReferenceText(this, newName)
}

/** `<include refid>` → `<sql id>` 定义；定义侧重命名由 [MapperRenameHandler] 回写所有引用处 */
private class SqlIncludeReference(
    value: XmlAttributeValue,
    private val anchor: XmlTag,
) : PsiReferenceBase<XmlAttributeValue>(value, valueRangeOf(value), true) {

    override fun resolve(): PsiElement? {
        val ref = element.value?.trim().orEmpty()
        if (ref.isBlank()) return null
        return MapperXmlResolver.sqlDefinitions(anchor, ref).firstOrNull()
    }

    override fun handleElementRename(newName: String): PsiElement = rewriteReferenceText(this, newName)

    override fun getVariants(): Array<Any> = MapperXmlResolver.sqlIdVariants(anchor).toTypedArray()
}

/**
 * `refid` 属性上的片段补全。平台的 XmlCompletionContributor 不会把 XmlAttributeValue 的
 * 引用变体列全（输入边界处尤其容易丢），这里按「引号内已输入的前缀」显式补一份。
 * 候选来自 [MapperXmlResolver.sqlIdVariants]：本文件短名 + 其它 namespace 的全限定名。
 */
class MapperSqlRefidCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val value = parameters.originalPosition
            ?.let { PsiTreeUtil.getParentOfType(it, XmlAttributeValue::class.java, false) }
            ?: return
        val attribute = value.parent as? XmlAttribute ?: return
        val tag = attribute.parent as? XmlTag ?: return
        if (attribute.localName != "refid" || tag.name != "include") return
        val file = value.containingFile as? XmlFile ?: return
        if (file.rootTag?.name != "mapper") return

        val inner = ElementManipulators.getValueTextRange(value)
        val relativeEnd = (parameters.offset - value.textRange.startOffset)
            .coerceAtLeast(inner.startOffset)
            .coerceAtMost(inner.endOffset)
        val typed = value.text.substring(inner.startOffset, relativeEnd)
        result.withPrefixMatcher(typed).addAllElements(
            MapperXmlResolver.sqlIdVariants(tag).map { LookupElementBuilder.create(it) }
        )
    }
}

/**
 * `resultType` 等类型属性里的类名补全。变体全部来自平台 JavaClassReference 的 getVariants()
 * （前几段解析到 package 后列出包内类），只是补上平台 JavaClassReferenceCompletionContributor
 * 在输入边界处（光标紧贴已输入前缀末尾）取不到引用时的兜底。
 */
class MapperClassAttrCompletionContributor : CompletionContributor() {

    /** 与 MapperAttrReferenceProvider 一致的类型属性清单 */
    private val JAVA_TYPE_ATTRS = setOf("resultType", "parameterType", "ofType", "javaType", "type")

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val value = parameters.originalPosition
            ?.let { PsiTreeUtil.getParentOfType(it, XmlAttributeValue::class.java, false) }
            ?: return
        val attribute = value.parent as? XmlAttribute ?: return
        val tag = attribute.parent as? XmlTag ?: return
        val file = value.containingFile as? XmlFile ?: return
        if (file.rootTag?.name != "mapper") {
            return
        }
        val name = attribute.localName
        if (name !in JAVA_TYPE_ATTRS || (name == "type" && tag.name != "resultMap")) {
            return
        }
        val reference = file.findReferenceAt(parameters.offset) as? JavaClassReference
            ?: file.findReferenceAt(parameters.offset - 1) as? JavaClassReference
            ?: return

        // 已输入前缀的最后一段（前面几段由引用负责），用它过滤变体。
        // 注意：补全时平台会在光标处插入哑元标识符，且 XmlAttributeValue.textRange 含两侧引号，
        // 因此只按「引号内的起止范围 + 光标位置」从 value.text 里截，避免把引号/哑元算进前缀。
        val inner = ElementManipulators.getValueTextRange(value)
        val relativeEnd = (parameters.offset - value.textRange.startOffset).coerceAtMost(inner.endOffset)
        val typed = if (relativeEnd > inner.startOffset) {
            value.text.substring(inner.startOffset, relativeEnd)
        } else {
            ""
        }
        val prefix = typed.substringAfterLast('.')
        val matcher = result.withPrefixMatcher(prefix)
        matcher.addAllElements(reference.variants.mapNotNull(::toLookupElement))
    }

    /** JavaClassReference 的变体是 String / PsiClass / LookupElement 的混合，统一转成 LookupElement */
    private fun toLookupElement(variant: Any?): LookupElement? = when (variant) {
        null -> null
        is LookupElement -> variant
        is PsiClass -> variant.name?.let { LookupElementBuilder.create(it) }
        else -> LookupElementBuilder.create(variant.toString())
    }
}

/**
 * `resultMap` / `extends` / 语句 `id` / `namespace` 的补全变体。
 *
 * 三个引用类虽然实现了 `getVariants()`，但平台在输入边界处常常取不到引用，
 * 补全会静默为空（和 [MapperSqlRefidCompletionContributor] 踩到的是同一个坑）。
 * 这里按属性名显式列一份，前缀按 FQN 最后一段匹配。
 */
class MapperRefVariantsCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val value = parameters.originalPosition
            ?.let { PsiTreeUtil.getParentOfType(it, XmlAttributeValue::class.java, false) }
            ?: return
        val attribute = value.parent as? XmlAttribute ?: return
        val tag = attribute.parent as? XmlTag ?: return
        val file = value.containingFile as? XmlFile ?: return
        if (file.rootTag?.name != "mapper") return

        val variants = when (attribute.localName) {
            "resultMap", "extends" -> MapperXmlResolver.resultMapIdVariants(tag)
            "id" -> if (MapperXmlResolver.isStatementTag(tag)) {
                MapperXmlResolver.statementIdVariants(tag)
            } else {
                return
            }

            "namespace" -> MapperXmlResolver.namespaceVariants(value)
            else -> return
        }
        if (variants.isEmpty()) return

        val inner = ElementManipulators.getValueTextRange(value)
        val relativeEnd = (parameters.offset - value.textRange.startOffset)
            .coerceAtLeast(inner.startOffset)
            .coerceAtMost(inner.endOffset)
        val typed = if (relativeEnd > inner.startOffset) {
            value.text.substring(inner.startOffset, relativeEnd)
        } else {
            ""
        }
        result.withPrefixMatcher(typed.substringAfterLast('.')).addAllElements(
            variants.map { LookupElementBuilder.create(it) }
        )
    }
}
