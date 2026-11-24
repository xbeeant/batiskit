package io.github.xbeeant.batiskit.json

import io.github.xbeeant.batiskit.template.Template
import io.github.xbeeant.batiskit.template.TemplatePlaceholders

/** 一个待写入的 .java 文件 */
class GeneratedJavaFile(val fileName: String, val className: String, val text: String)

/**
 * 类图 → Java 源码。
 *
 * 产物不是写死在 Kotlin 里的字符串拼接，而是走插件自己的模板引擎渲染 `resources/json/pojo.tpl`：
 * 想加类级注解、换字段排版、改 JavaDoc，改模板即可，跟表结构生成是同一套规矩。
 * 平台层可以把用户模板（`id = jsonPojo`）的全文传进 [render] 覆盖内置模板。
 *
 * 注解按 FQN 归并：同一个库在字段上产生的多个注解会合成一个；
 * fastjson 1.x 与 2.x 同时选中时两者的 `@JSONField` 简名冲突，冲突方自动写成全限定名且不 import。
 */
class JsonRenderer(private val settings: JsonSettings) {

    private val libs: Set<JsonLib> = JsonLib.parse(settings.annotationLibs)
    private val lombok: List<LombokAnnotation> = LombokAnnotation.parse(settings.lombokAnnotations)
    private val record: Boolean = settings.classKind.equals("RECORD", true)

    fun render(
        result: InferenceResult,
        packageName: String,
        templateText: String? = null
    ): List<GeneratedJavaFile> {
        val root = result.root ?: return emptyList()
        val source = templateText?.takeIf { it.isNotBlank() } ?: defaultTemplate()
        val template = Template.compile(source, "json/pojo.tpl", TemplatePlaceholders.NAMES)
        val all = result.classes
        return if (settings.splitClasses) {
            // 每个类一个文件：各自算 import，公共类名靠同包解析，不需要互相 import
            all.map { bean -> buildFile(bean, listOf(bean), packageName, template, result.sampleCount, false) }
        } else {
            listOf(buildFile(root, all, packageName, template, result.sampleCount, true))
        }
    }

    /** 内置模板资源；测试环境（classpath 里同样有 resources）也能读到 */
    fun defaultTemplate(): String = javaClass.getResourceAsStream("/json/pojo.tpl")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: error("缺少内置模板 resources/json/pojo.tpl")

    // ------------------------------------------------------------------ 单文件

    private fun buildFile(
        root: ClassModel,
        all: List<ClassModel>,
        packageName: String,
        template: Template,
        sampleCount: Int,
        nested: Boolean
    ): GeneratedJavaFile {
        val registry = AnnotationRegistry()
        val specs = LinkedHashMap<String, ClassSpecs>()
        all.forEach { bean ->
            val spec = specsOf(bean, sampleCount)
            spec.register(registry)
            specs[bean.name] = spec
        }
        registry.resolve()

        val imports = LinkedHashSet<String>()
        all.forEach { bean ->
            bean.fields.filter { it.enabled }.forEach { imports += it.type.imports() }
        }
        imports += registry.imports()
        if (settings.serializable && !record) imports += "java.io.Serializable"

        val classText = renderClass(root, specs, registry, template, nested)
        val header = buildString {
            if (packageName.isNotBlank()) append("package ").append(packageName).append(";\n\n")
            if (imports.isNotEmpty()) {
                sortImports(imports).forEach { append("import ").append(it).append(";\n") }
                append("\n")
            }
        }
        return GeneratedJavaFile(root.name + ".java", root.name, header + classText)
    }

    private fun renderClass(
        bean: ClassModel,
        specs: Map<String, ClassSpecs>,
        registry: AnnotationRegistry,
        template: Template,
        nested: Boolean
    ): String {
        val spec = specs.getValue(bean.name)
        val fields = spec.fields.map { f ->
            mapOf<String, Any?>(
                "name" to f.field.name,
                "type" to f.typeText,
                "modifier" to f.modifier,
                "comment" to f.comment,
                "annotations" to f.annotations.map { (text, fqn) -> registry.display(text, fqn) }
            )
        }
        val inner = if (nested && bean.children.isNotEmpty()) {
            bean.children.joinToString("\n\n") { child ->
                renderClass(child, specs, registry, template, true)
            }.lines().joinToString("\n") { line -> if (line.isBlank()) "" else "    $line" }
        } else {
            ""
        }
        val model = mapOf<String, Any?>(
            "className" to bean.name,
            "kind" to if (record) "record" else "class",
            "modifier" to if (nested && bean.depth > 0) "static " else "",
            "implementsClause" to if (settings.serializable && !record && bean.depth == 0) {
                " implements Serializable"
            } else {
                ""
            },
            "javaDoc" to if (settings.generateComments) "${bean.name}（由 JSON 生成，共 ${spec.sampleCount} 份样本）" else "",
            "classAnnotations" to spec.classAnnotations.map { (text, fqn) -> registry.display(text, fqn) },
            "fields" to fields,
            "innerClasses" to inner,
            "serializable" to (settings.serializable && !record && bean.depth == 0),
            "serialVersionUid" to (settings.serializable && !record && bean.depth == 0)
        )
        return template.render(model).let { rendered ->
            if (rendered.hasWarnings) {
                // 模板写错变量名时不要静默产出半截代码
                error("JSON 类模板渲染告警：${rendered.warnings.joinToString("；")}")
            }
            rendered.text.trimEnd() + "\n"
        }
    }

    // ------------------------------------------------------------------ 注解与字段

    private class ClassSpecs(
        val classAnnotations: List<Spec>,
        val fields: List<FieldSpecs>,
        val sampleCount: Int
    ) {
        fun register(registry: AnnotationRegistry) {
            classAnnotations.forEach { registry.register(it.fqn) }
            fields.forEach { it.annotations.forEach { spec -> registry.register(spec.fqn) } }
        }
    }

    private class FieldSpecs(
        val field: FieldModel,
        val typeText: String,
        val modifier: String,
        val comment: String,
        val annotations: List<Spec>
    )

    private data class Spec(val text: String, val fqn: String)

    private fun specsOf(bean: ClassModel, sampleCount: Int): ClassSpecs {
        val classAnnotations = mutableListOf<Pair<String, String>>()
        if (!record && settings.useLombok) {
            lombok.forEach { classAnnotations += it.annotation to it.fqn }
        }
        if (JsonLib.JACKSON in libs) {
            if (settings.ignoreUnknown) {
                classAnnotations += "@JsonIgnoreProperties(ignoreUnknown = true)" to JsonAnnotations.JACKSON_IGNORE
            }
            if (settings.includeNonNull) {
                classAnnotations += "@JsonInclude(JsonInclude.Include.NON_NULL)" to JsonAnnotations.JACKSON_INCLUDE
            }
        }

        val modifier = when {
            record -> ""
            settings.fieldVisibility.equals("PUBLIC", true) -> "public "
            settings.fieldVisibility.equals("PACKAGE_PRIVATE", true) -> ""
            else -> "private "
        }
        val usePrimitives = !settings.useWrapperTypes
        val fields = bean.fields.filter { it.enabled }.map { field ->
            val changed = field.name != field.jsonKey
            val annotations = mutableListOf<Pair<String, String>>()
            annotations += JsonAnnotations.nameAnnotations(
                libs, field.jsonKey, settings.nameAnnotation, changed
            )
            if (!field.datePattern.isNullOrBlank() && isTemporal(field.type)) {
                annotations += JsonAnnotations.formatAnnotation(libs, field.datePattern)
            }
            FieldSpecs(
                field = field,
                typeText = field.type.java(usePrimitives),
                modifier = modifier,
                comment = commentOf(field),
                annotations = mergeAnnotations(annotations)
            )
        }
        return ClassSpecs(mergeAnnotations(classAnnotations), fields, sampleCount)
    }

    private fun isTemporal(type: JsonType): Boolean = when (type) {
        is JsonType.Simple -> type.name in setOf(
            "LocalDate", "LocalTime", "LocalDateTime", "OffsetDateTime", "Instant", "Date"
        )

        is JsonType.ListType -> isTemporal(type.element)
        is JsonType.MapType -> isTemporal(type.value)
        is JsonType.Bean -> false
    }

    private fun commentOf(field: FieldModel): String {
        if (!settings.generateComments) return ""
        val parts = mutableListOf<String>()
        if (field.jsonKey != field.name) parts += "JSON 字段：${field.jsonKey}"
        if (field.sample.isNotBlank()) parts += "示例：${field.sample}"
        parts += field.notes
        return parts.joinToString("；")
            .replace("*/", "* /")
            .replace("\n", " ")
            .let { if (it.length > 180) it.take(177) + "…" else it }
    }

    /** 同一个注解（同一 FQN）在字段上出现多次时合并参数，避免生成两个 @JSONField 导致编译失败 */
    private fun mergeAnnotations(specs: List<Pair<String, String>>): List<Spec> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        specs.forEach { (text, fqn) ->
            val short = fqn.substringAfterLast('.')
            val rest = text.removePrefix("@$short")
            val params = if (rest.startsWith("(")) rest.removeSurrounding("(", ")").trim() else ""
            val list = grouped.getOrPut(fqn) { mutableListOf() }
            if (params.isNotBlank()) list += params
        }
        return grouped.map { (fqn, params) ->
            val short = fqn.substringAfterLast('.')
            val text = if (params.isEmpty()) "@$short" else "@$short(${params.joinToString(", ")})"
            Spec(text, fqn)
        }
    }

    /** java.* / javax.* 在前，第三方在后，组内按字母序 */
    private fun sortImports(imports: Set<String>): List<String> = imports.sortedWith(
        compareBy<String> { fqn ->
            when {
                fqn.startsWith("java.") -> 0
                fqn.startsWith("javax.") -> 1
                else -> 2
            }
        }.thenBy { it }
    )

    /**
     * 简名冲突处理：fastjson 与 fastjson2 的 @JSONField 简名相同，
     * 同时选中时两个都不能 import，冲突方直接用全限定名写在注解上。
     */
    private class AnnotationRegistry {
        private val fqns = LinkedHashSet<String>()
        private var conflicts = setOf<String>()

        fun register(fqn: String) {
            fqns += fqn
        }

        fun resolve() {
            conflicts = fqns.groupBy { it.substringAfterLast('.') }
                .values.filter { it.size > 1 }.flatten().toSet()
        }

        fun imports(): Set<String> = fqns.filter { it !in conflicts }.toSet()

        fun display(text: String, fqn: String): String {
            if (fqn !in conflicts) return text
            val short = fqn.substringAfterLast('.')
            return "@$fqn" + text.removePrefix("@$short")
        }
    }
}
