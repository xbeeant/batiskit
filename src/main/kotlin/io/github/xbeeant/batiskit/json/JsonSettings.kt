package io.github.xbeeant.batiskit.json

/**
 * 「JSON → Java 对象」的全部开关。
 *
 * 纯数据类（不引用任何平台 API）：解析 / 推断 / 渲染三层都吃同一个对象，
 * 所以整条链路可以在普通 JUnit 里断言，UI 只是把值填进来。
 *
 * 平台序列化（PersistentStateComponent）要求 public var + 无参构造，枚举一律用 String 存放，
 * 避免 XmlSerializer 处理枚举的兼容问题——和 [io.github.xbeeant.batiskit.config.GenerationConfig] 同一套约定。
 */
class JsonSettings {

    /** 注解库，逗号分隔，可同时选多个：[JsonLib] 的名字。留空表示不生成序列化注解 */
    var annotationLibs: String = "JACKSON"

    /** 字段名与 JSON key 不一致时是否生成 name 映射注解：DIFFERENT / ALWAYS / NEVER */
    var nameAnnotation: String = "DIFFERENT"

    /** Lombok 类级注解开关 */
    var useLombok: Boolean = true

    /** Lombok 注解集，逗号分隔：[LombokAnnotation] 的名字 */
    var lombokAnnotations: String = "DATA,NO_ARGS,ALL_ARGS,BUILDER"

    /** 产物形态：CLASS / RECORD（record 下自动忽略 lombok 与字段可见性） */
    var classKind: String = "CLASS"

    /** true 时每个类单独一个 .java；false 时嵌套对象作为静态内部类放在顶层类里 */
    var splitClasses: Boolean = false

    /** 字段可见性：PRIVATE / PUBLIC / PACKAGE_PRIVATE */
    var fieldVisibility: String = "PRIVATE"

    /** 数字 / 布尔用包装类型（Integer / Long / Boolean）；关闭则生成 int / long / boolean，泛型实参仍用包装类型 */
    var useWrapperTypes: Boolean = true

    /** 数字策略：AUTO（Integer/Long/BigInteger + BigDecimal）/ BIG_DECIMAL / DOUBLE / STRING */
    var numberKind: String = "AUTO"

    /** 日期策略：AUTO（按格式识别 LocalDate / LocalTime / LocalDateTime / OffsetDateTime）/ STRING / LOCAL_DATE_TIME / INSTANT / JAVA_UTIL_DATE */
    var dateKind: String = "AUTO"

    /** 样本里该字段全是 null 时按什么类型生成：OBJECT / STRING */
    var nullKind: String = "OBJECT"

    /** 识别 ISO 日期 / 时间字符串（受 dateKind 约束） */
    var detectDates: Boolean = true

    /** 把「数字 / 日期 / UUID 作为 key」的对象折叠成 Map<String, T>，而不是生成几十个字段 */
    var detectDynamicMap: Boolean = true

    /** 全大写下划线字符串多个取值时，在字段注释里给出枚举候选 */
    var detectEnum: Boolean = true

    /** UUID 格式字符串映射成 java.util.UUID（默认关闭：多数场景里它只是个 id 字符串） */
    var detectUuid: Boolean = false

    /** 结构相同的嵌套对象复用同一个类（GsonFormatPlus 里的 TODO：reuse bean） */
    var reuseBeans: Boolean = true

    /** 生成 JavaDoc：原始 key、示例值、可选字段、类型冲突提示 */
    var generateComments: Boolean = true

    /** Jackson：类级 @JsonIgnoreProperties(ignoreUnknown = true) */
    var ignoreUnknown: Boolean = true

    /** Jackson：类级 @JsonInclude(NON_NULL) */
    var includeNonNull: Boolean = false

    /** 实现 Serializable 并生成 serialVersionUID */
    var serializable: Boolean = false

    /** JavaDoc 的 @author */
    var author: String = System.getProperty("user.name", "")

    /** 上次使用的类名 / 包名（弹窗每次会按目标目录重新推断包名） */
    var className: String = "Response"
    var packageName: String = ""

    /**
     * 自定义类模板全文（空 = 用内置 `resources/json/pojo.tpl`）。
     * 单独存在这里而不是 TemplateSettings：那边是「表结构生成的方案仓库」，
     * 放一条没有 output 的模板会被当成坏模板混进代码生成的错误列表。
     */
    var classTemplate: String = ""

    fun copyOf(): JsonSettings = JsonSettings().also { c ->
        c.annotationLibs = annotationLibs
        c.nameAnnotation = nameAnnotation
        c.useLombok = useLombok
        c.lombokAnnotations = lombokAnnotations
        c.classKind = classKind
        c.splitClasses = splitClasses
        c.fieldVisibility = fieldVisibility
        c.useWrapperTypes = useWrapperTypes
        c.numberKind = numberKind
        c.dateKind = dateKind
        c.nullKind = nullKind
        c.detectDates = detectDates
        c.detectDynamicMap = detectDynamicMap
        c.detectEnum = detectEnum
        c.detectUuid = detectUuid
        c.reuseBeans = reuseBeans
        c.generateComments = generateComments
        c.ignoreUnknown = ignoreUnknown
        c.includeNonNull = includeNonNull
        c.serializable = serializable
        c.author = author
        c.className = className
        c.packageName = packageName
        c.classTemplate = classTemplate
    }
}

/**
 * 支持的序列化库。
 *
 * fastjson 与 fastjson2 的注解**类名相同但包名不同**——这是最容易踩的坑：
 * 生成 `@JSONField` 时若 import 错包，代码能编译过但运行期注解根本不生效。
 */
enum class JsonLib(val label: String, val fieldAnnotation: String?, val nameParameter: String) {
    FASTJSON("Fastjson 1.x", "com.alibaba.fastjson.annotation.JSONField", "name"),
    FASTJSON2("Fastjson 2.x", "com.alibaba.fastjson2.annotation.JSONField", "name"),
    GSON("Gson", "com.google.gson.annotations.SerializedName", "value"),
    JACKSON("Jackson", "com.fasterxml.jackson.annotation.JsonProperty", "value");

    companion object {
        fun parse(text: String): Set<JsonLib> = text.split(',', ' ', ';')
            .map { it.trim().uppercase() }
            .mapNotNull { name -> entries.firstOrNull { it.name == name } }
            .toCollection(linkedSetOf())
    }
}

/** Lombok 注解集：字符串存配置，这里负责翻译成注解文本 + import */
enum class LombokAnnotation(val annotation: String, val fqn: String) {
    DATA("@Data", "lombok.Data"),
    GETTER("@Getter", "lombok.Getter"),
    SETTER("@Setter", "lombok.Setter"),
    TO_STRING("@ToString", "lombok.ToString"),
    EQUALS_HASHCODE("@EqualsAndHashCode", "lombok.EqualsAndHashCode"),
    NO_ARGS("@NoArgsConstructor", "lombok.NoArgsConstructor"),
    ALL_ARGS("@AllArgsConstructor", "lombok.AllArgsConstructor"),
    BUILDER("@Builder", "lombok.Builder"),
    ACCESSORS_CHAIN("@Accessors(chain = true)", "lombok.experimental.Accessors");

    companion object {
        fun parse(text: String): List<LombokAnnotation> = text.split(',', ' ', ';')
            .map { it.trim().uppercase() }
            .mapNotNull { name -> entries.firstOrNull { it.name == name } }
    }
}

/** 注解库对应的「类级」附加注解（多库可叠加） */
object JsonAnnotations {

    const val JACKSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnoreProperties"
    const val JACKSON_INCLUDE = "com.fasterxml.jackson.annotation.JsonInclude"
    const val JACKSON_FORMAT = "com.fasterxml.jackson.annotation.JsonFormat"
    const val GSON_EXPOSE = "com.google.gson.annotations.Expose"

    /** 字段上的日期格式注解；fastjson / fastjson2 用 format，Jackson 用 @JsonFormat(pattern) */
    fun formatAnnotation(libs: Set<JsonLib>, pattern: String): List<Pair<String, String>> =
        buildList {
            libs.forEach { lib ->
                when (lib) {
                    JsonLib.FASTJSON, JsonLib.FASTJSON2 -> lib.fieldAnnotation?.let {
                        add("@JSONField(format = \"$pattern\")" to it)
                    }

                    JsonLib.JACKSON -> add("@JsonFormat(pattern = \"$pattern\")" to JACKSON_FORMAT)
                    JsonLib.GSON -> Unit // Gson 没有字段级日期格式注解
                }
            }
        }

    /** name 映射注解：不同库的写法不同（Gson/Jackson 用 value，fastjson 用 name） */
    fun nameAnnotations(libs: Set<JsonLib>, key: String, mode: String, changed: Boolean): List<Pair<String, String>> {
        val needed = when (mode.uppercase()) {
            "ALWAYS" -> true
            "NEVER" -> false
            else -> changed
        }
        if (!needed) return emptyList()
        return libs.mapNotNull { lib ->
            lib.fieldAnnotation?.let { fqn ->
                val short = fqn.substringAfterLast('.')
                val arg = if (lib == JsonLib.FASTJSON || lib == JsonLib.FASTJSON2) {
                    "@$short(name = \"$key\")"
                } else {
                    "@$short(\"$key\")"
                }
                arg to fqn
            }
        }
    }
}
