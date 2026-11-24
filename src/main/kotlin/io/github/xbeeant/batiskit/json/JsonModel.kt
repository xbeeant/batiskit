package io.github.xbeeant.batiskit.json

/**
 * 推断出来的 Java 类型树。渲染时才知道要不要用基本类型（泛型实参一律用包装类型），
 * 所以 [java] 接受一个开关，而不是在推断阶段就定死。
 */
sealed interface JsonType {
    fun java(usePrimitives: Boolean = false): String
    fun imports(): Set<String>

    data class Simple(val name: String) : JsonType {
        override fun java(usePrimitives: Boolean): String =
            if (usePrimitives) PRIMITIVES[name] ?: name else name

        override fun imports(): Set<String> = FQNS[name]?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
    }

    data class ListType(val element: JsonType) : JsonType {
        override fun java(usePrimitives: Boolean): String = "List<${element.java(false)}>"
        override fun imports(): Set<String> = setOf("java.util.List") + element.imports()
    }

    data class MapType(val value: JsonType, val key: String = "String") : JsonType {
        override fun java(usePrimitives: Boolean): String = "Map<$key, ${value.java(false)}>"
        override fun imports(): Set<String> = setOf("java.util.Map") + value.imports()
    }

    /** 指向本次生成的另一个类（嵌套静态类或拆分出来的顶层类） */
    data class Bean(val className: String) : JsonType {
        override fun java(usePrimitives: Boolean): String = className
        override fun imports(): Set<String> = emptySet()
    }

    companion object {
        private val FQNS = mapOf(
            "BigDecimal" to "java.math.BigDecimal",
            "BigInteger" to "java.math.BigInteger",
            "LocalDate" to "java.time.LocalDate",
            "LocalTime" to "java.time.LocalTime",
            "LocalDateTime" to "java.time.LocalDateTime",
            "OffsetDateTime" to "java.time.OffsetDateTime",
            "Instant" to "java.time.Instant",
            "Date" to "java.util.Date",
            "UUID" to "java.util.UUID",
            "Object" to "",
            "String" to "",
            "Integer" to "",
            "Long" to "",
            "Boolean" to "",
            "Double" to "",
            "byte[]" to ""
        )

        private val PRIMITIVES = mapOf(
            "Integer" to "int", "Long" to "long", "Boolean" to "boolean",
            "Double" to "double", "Float" to "float", "Short" to "short", "Byte" to "byte"
        )

        /** UI 里手改类型后回填：支持 `List<X>` / `Map<String, X>` / `X[]` / 全限定名 */
        fun parse(text: String): JsonType {
            val t = text.trim()
            if (t.endsWith("[]")) return ListType(parse(t.dropLast(2)))
            val genericStart = t.indexOf('<')
            if (genericStart > 0 && t.endsWith(">")) {
                val raw = t.substring(0, genericStart).substringAfterLast('.')
                val args = t.substring(genericStart + 1, t.length - 1)
                return when (raw) {
                    "List", "ArrayList", "Set" -> ListType(parse(args.substringAfterLast(',')))
                    "Map", "HashMap", "LinkedHashMap" -> MapType(parse(args.substringAfter(',')))
                    else -> Simple(t.substringAfterLast('.'))
                }
            }
            return Simple(t.substringAfterLast('.').ifBlank { "Object" })
        }
    }
}

/** 一个生成出来的字段。UI 的字段表直接改 [name] / [type] / [enabled]，改完重新渲染即可 */
class FieldModel(
    val jsonKey: String,
    var name: String,
    var type: JsonType,
    var enabled: Boolean = true,
    val occurrences: Int = 1,
    val sampleCount: Int = 1,
    val sample: String = "",
    val datePattern: String? = null,
    val notes: List<String> = emptyList()
) {
    val optional: Boolean get() = occurrences < sampleCount
    fun typeText(usePrimitives: Boolean = false): String = type.java(usePrimitives)
}

/** 一个生成出来的类。[children] 是渲染时按引用关系挂上去的，推断阶段只管字段 */
class ClassModel(val name: String, val originKey: String?, val depth: Int) {
    val fields: MutableList<FieldModel> = mutableListOf()
    val children: MutableList<ClassModel> = mutableListOf()
}

class InferenceResult(
    val root: ClassModel?,
    val classes: List<ClassModel>,
    val warnings: List<String>,
    val sampleCount: Int
)

/** JSON key → Java 标识符。比简单驼峰多处理了缩写、保留字、数字开头和复数类名 */
object JsonNames {

    private val KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "_", "var", "record", "yield"
    )

    /** `userID` → `userId`，`URLValue` → `urlValue`；保留字加 Value，数字开头加 f */
    fun fieldName(raw: String): String {
        val words = words(raw)
        if (words.isEmpty()) return "field"
        var name = buildString {
            words.forEachIndexed { index, word ->
                val lower = word.lowercase()
                append(if (index == 0) lower else lower.replaceFirstChar { it.uppercaseChar() })
            }
        }
        if (name.first().isDigit()) name = "f$name"
        if (name in KEYWORDS) name += "Value"
        return name
    }

    /** `order_items` → `OrderItem`（singular=true）；与 List/Map/String 等撞名时补 Item */
    fun className(raw: String, singular: Boolean = false): String {
        val parts = words(raw).toMutableList()
        if (singular && parts.isNotEmpty()) parts[parts.lastIndex] = singularOf(parts.last())
        var name = parts.joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
        if (name.isEmpty()) name = "Generated"
        if (name.first().isDigit()) name = "C$name"
        if (name in RESERVED_TYPE_NAMES) name += "Item"
        return name
    }

    /** 合法 Java 类名？弹窗校验用 */
    fun isValidClassName(text: String): Boolean =
        Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(text) && text !in KEYWORDS

    private val RESERVED_TYPE_NAMES = setOf(
        "List", "Map", "Set", "Collection", "String", "Object", "Integer", "Long", "Boolean",
        "Double", "BigDecimal", "LocalDate", "LocalDateTime", "Date", "UUID", "Class", "Override"
    )

    private fun words(raw: String): List<String> = raw
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .flatMap { splitCamel(it) }
        .filter { it.isNotBlank() }

    /** 按大小写边界切词，同时把 `URLValue` 这种连续大写拆成 URL + Value */
    private fun splitCamel(token: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (i in token.indices) {
            val ch = token[i]
            val prev = token.getOrNull(i - 1)
            val next = token.getOrNull(i + 1)
            if (i > 0 && ch.isUpperCase() &&
                (prev?.isLowerCase() == true || (prev?.isUpperCase() == true && next?.isLowerCase() == true))
            ) {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.setLength(0)
                }
            }
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun singularOf(word: String): String {
        val lower = word.lowercase()
        return when {
            lower.length > 4 && lower.endsWith("sses") -> word.dropLast(2)
            lower.length > 4 && lower.endsWith("ies") -> word.dropLast(3) + "y"
            lower.length > 3 && lower.endsWith("s") && !lower.endsWith("ss") &&
                !lower.endsWith("us") && !lower.endsWith("is") -> word.dropLast(1)

            else -> word
        }
    }
}

/**
 * JSON 样本 → 类图。
 *
 * 「更好」的核心都在这里，而不在 UI：
 *  - **多份样本合并**：字段取并集，记录每字段在几份样本里出现（可选字段），类型冲突时统一到安全类型并留提示；
 *  - **数组元素合并**：`[{"a":1},{"b":2}]` 生成一个含 a/b 的类，而不是两个；
 *  - **结构复用**：形状相同的对象共用同一个类（GsonFormatPlus 的 `reuse bean` 还是 TODO）；
 *  - **动态 key**：数字 / 日期 / UUID 作为 key 的对象折叠成 `Map<String, T>`；
 *  - **日期与精度**：ISO 日期串识别成 java.time 类型并带上格式，整数按范围选 Integer/Long/BigInteger，小数默认 BigDecimal。
 */
class JsonInferrer(private val settings: JsonSettings) {

    private val warnings = mutableListOf<String>()
    private val classes = mutableListOf<ClassModel>()
    private val usedClassNames = mutableSetOf<String>()
    private val beanCache = mutableMapOf<String, ClassModel>()
    /** 正在 fillBean 的类：签名相同的嵌套对象不能复用祖先，否则会生成自引用类并丢掉内层字段 */
    private val building = mutableSetOf<String>()
    private var sampleCount = 0

    fun infer(samples: List<JsonValue>, rootName: String): InferenceResult {
        sampleCount = samples.size
        val roots = mutableListOf<JsonValue.Obj>()
        samples.forEach { sample ->
            when (sample) {
                is JsonValue.Obj -> roots += sample
                is JsonValue.Arr -> {
                    val objects = sample.items.filterIsInstance<JsonValue.Obj>()
                    if (objects.isNotEmpty()) roots += objects
                    else roots += wrapScalar("data", sample)
                }

                else -> roots += wrapScalar("data", sample)
            }
        }
        if (roots.isEmpty()) {
            return InferenceResult(null, emptyList(), listOf("样本里没有对象，无法生成 Java 类"), sampleCount)
        }

        val root = newBean(JsonNames.className(rootName.ifBlank { "Response" }), rootName, 0, null)
        building += root.name
        fillBean(root, roots)
        building -= root.name
        linkChildren(root)
        return InferenceResult(root, classes, warnings, sampleCount)
    }

    // ------------------------------------------------------------------ 类构建

    private fun wrapScalar(key: String, value: JsonValue): JsonValue.Obj =
        JsonValue.Obj(linkedMapOf(key to value))

    private fun newBean(name: String, hint: String, depth: Int, signature: String?): ClassModel {
        val unique = uniqueClassName(name)
        val bean = ClassModel(unique, hint, depth)
        classes += bean
        if (signature != null) beanCache[signature] = bean
        return bean
    }

    private fun buildBean(hint: String, objects: List<JsonValue.Obj>, depth: Int): JsonType {
        if (depth > MAX_DEPTH) {
            warnings += "字段 $hint 的嵌套层级超过 $MAX_DEPTH 层，已按 Object 处理"
            return JsonType.Simple("Object")
        }
        val signature = if (settings.reuseBeans) signatureOf(objects) else null
        if (signature != null) {
            val hit = beanCache[signature]
            // 兄弟字段里形状相同的可以复用；祖先还在构建中时不能复用，
            // 否则 {"a":{"b":…}} 会塌缩成自引用类，内层的字段全丢
            if (hit != null && hit.name !in building) return JsonType.Bean(hit.name)
        }
        val bean = newBean(JsonNames.className(hint, singular = true), hint, depth, signature)
        building += bean.name
        fillBean(bean, objects)
        building -= bean.name
        return JsonType.Bean(bean.name)
    }

    private fun fillBean(bean: ClassModel, objects: List<JsonValue.Obj>) {
        // 顶层对象本身就是「数字/日期 key -> 值」的 Map（Java 类没法直接表示 Map），
        // 折成一个 data 字段；嵌套对象走 objectType 里的同一套判定
        if (settings.detectDynamicMap && looksLikeDynamicMap(objects)) {
            val values = objects.flatMap { it.fields.values }
            val guess = if (values.isEmpty()) {
                Guess(JsonType.Simple("Object"))
            } else {
                inferType(values, "data", bean.depth + 1)
            }
            bean.fields += FieldModel(
                jsonKey = "data",
                name = "data",
                type = JsonType.MapType(guess.type),
                occurrences = objects.size,
                sampleCount = sampleCount,
                sample = "${values.size} 项",
                notes = listOf("原始对象的 key 是数据（数字 / 日期 / 编号），已折叠成 Map<String, …>")
            )
            return
        }
        val keys = LinkedHashSet<String>()
        objects.forEach { keys.addAll(it.fields.keys) }
        val usedNames = mutableSetOf<String>()
        keys.forEach { key ->
            val present = objects.mapNotNull { it.fields[key] }
            val guess = inferType(present, key, bean.depth + 1)
            var fieldName = JsonNames.fieldName(key)
            // Boolean 字段叫 isXxx 时 lombok 会生成 isIsXxx()，Jackson 也容易绑不上——去掉前缀，
            // 原始 key 由 name 映射注解保留
            if (guess.type is JsonType.Simple && guess.type.name == "Boolean" &&
                fieldName.startsWith("is") && fieldName.length > 2 && fieldName[2].isUpperCase()
            ) {
                fieldName = fieldName.substring(2).replaceFirstChar { it.lowercaseChar() }
            }
            fieldName = uniqueFieldName(fieldName, usedNames)

            val notes = guess.notes.toMutableList()
            if (present.size < sampleCount) {
                notes += "可选字段：仅 ${present.size}/$sampleCount 份样本出现"
            }
            if (present.size > 1 && present.any { it is JsonValue.Null }) {
                notes += "样本里可能为 null"
            }
            bean.fields += FieldModel(
                jsonKey = key,
                name = fieldName,
                type = guess.type,
                occurrences = present.size,
                sampleCount = sampleCount,
                sample = present.firstOrNull { it !is JsonValue.Null }?.let { preview(it) } ?: "",
                datePattern = guess.pattern,
                notes = notes
            )
        }
    }

    /** 渲染单文件时每个类只能挂在一个父节点下；按创建顺序第一次引用它的类作为父节点 */
    private fun linkChildren(root: ClassModel) {
        val assigned = mutableSetOf(root.name)
        val byName = classes.associateBy { it.name }
        classes.forEach { owner ->
            owner.fields.flatMap { beanRefs(it.type) }.distinct().forEach { ref ->
                val child = byName[ref] ?: return@forEach
                if (assigned.add(ref)) owner.children += child
            }
        }
    }

    private fun beanRefs(type: JsonType): List<String> = when (type) {
        is JsonType.Bean -> listOf(type.className)
        is JsonType.ListType -> beanRefs(type.element)
        is JsonType.MapType -> beanRefs(type.value)
        is JsonType.Simple -> emptyList()
    }

    // ------------------------------------------------------------------ 类型推断

    private class Guess(
        val type: JsonType,
        val pattern: String? = null,
        val notes: List<String> = emptyList()
    )

    private fun inferType(values: List<JsonValue>, hint: String, depth: Int): Guess {
        if (depth > MAX_DEPTH) {
            return Guess(JsonType.Simple("Object"), null, listOf("嵌套层级过深，按 Object 处理"))
        }
        val nonNull = values.filter { it !is JsonValue.Null }
        if (nonNull.isEmpty()) {
            val name = if (settings.nullKind.equals("STRING", true)) "String" else "Object"
            return Guess(
                JsonType.Simple(name), null,
                listOf("所有样本里都是 null，类型按 $name 生成，请在字段表里改成实际类型")
            )
        }
        val categories = nonNull.mapTo(linkedSetOf()) { categoryOf(it) }
        if (categories.size > 1) {
            // 字符串 + 数字是接口里最常见的「同一字段有时是 0 有时是 \"0\"」：
            // 统一成 String 能兜住两种值；其余冲突只能退到 Object 并明确告知
            val type = if (categories == setOf("str", "num")) "String" else "Object"
            val note = "同一字段出现多种类型（${categories.joinToString(" / ")}），已统一为 $type，请确认"
            warnings += "$hint：$note"
            return Guess(JsonType.Simple(type), null, listOf(note))
        }
        return when (categories.first()) {
            "bool" -> Guess(JsonType.Simple("Boolean"))
            "num" -> Guess(numberType(nonNull.filterIsInstance<JsonValue.Num>()))
            "str" -> stringType(nonNull.filterIsInstance<JsonValue.Str>().map { it.value }, hint)
            "arr" -> arrayType(nonNull.filterIsInstance<JsonValue.Arr>(), hint, depth)
            else -> objectType(nonNull.filterIsInstance<JsonValue.Obj>(), hint, depth)
        }
    }

    private fun categoryOf(value: JsonValue): String = when (value) {
        is JsonValue.Null -> "null"
        is JsonValue.Bool -> "bool"
        is JsonValue.Num -> "num"
        is JsonValue.Str -> "str"
        is JsonValue.Arr -> "arr"
        is JsonValue.Obj -> "obj"
    }

    private fun numberType(nums: List<JsonValue.Num>): JsonType {
        val raws = nums.map { it.raw }
        val integral = raws.all { !it.contains('.') && !it.contains('e') && !it.contains('E') }
        return when (settings.numberKind.uppercase()) {
            "STRING" -> JsonType.Simple("String")
            "BIG_DECIMAL" -> JsonType.Simple("BigDecimal")
            "DOUBLE" -> if (integral) integerType(raws) else JsonType.Simple("Double")
            else -> if (integral) integerType(raws) else JsonType.Simple("BigDecimal")
        }
    }

    private fun integerType(raws: List<String>): JsonType {
        val longs = raws.mapNotNull { it.toLongOrNull() }
        return when {
            longs.size == raws.size && longs.all { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() } ->
                JsonType.Simple("Integer")

            longs.size == raws.size -> JsonType.Simple("Long")
            raws.all { it.toBigIntegerOrNull() != null } -> JsonType.Simple("BigInteger")
            else -> JsonType.Simple("BigDecimal")
        }
    }

    private fun stringType(values: List<String>, hint: String): Guess {
        if (settings.detectDates && !settings.dateKind.equals("STRING", true)) {
            val shape = values.firstOrNull()?.let { dateShapeOf(it) }
            if (shape != null && values.all { dateShapeOf(it) == shape }) {
                return dateGuess(shape)
            }
            if (shape != null) {
                warnings += "$hint：日期字符串格式不一致，仍按 String 处理"
            }
        }
        if (settings.detectUuid && values.all { UUID.matches(it) }) {
            return Guess(JsonType.Simple("UUID"))
        }
        val notes = mutableListOf<String>()
        if (settings.detectEnum) {
            val distinct = values.distinct()
            if (distinct.size >= 2 && distinct.all { ENUM_CANDIDATE.matches(it) }) {
                notes += "疑似枚举：${distinct.take(6).joinToString(" / ")}（可在字段表里改成枚举类型）"
            }
        }
        return Guess(JsonType.Simple("String"), null, notes)
    }

    private fun dateGuess(shape: DateShape): Guess = when (settings.dateKind.uppercase()) {
        "LOCAL_DATE_TIME" -> Guess(JsonType.Simple("LocalDateTime"), shape.pattern)
        "INSTANT" -> Guess(JsonType.Simple("Instant"), null)
        "JAVA_UTIL_DATE" -> Guess(JsonType.Simple("Date"), shape.pattern)
        else -> Guess(JsonType.Simple(shape.type), shape.pattern)
    }

    private fun dateShapeOf(text: String): DateShape? = when {
        TIME.matches(text) -> DateShape("LocalTime", "HH:mm:ss")
        OFFSET_DATE_TIME.matches(text) -> {
            val pattern = if (text.contains('T')) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd HH:mm:ssXXX"
            DateShape("OffsetDateTime", pattern)
        }

        DATE_TIME.matches(text) || SLASH_DATE_TIME.matches(text) -> {
            val day = if (text.contains('/')) "yyyy/MM/dd" else "yyyy-MM-dd"
            val time = if (text.contains('T')) "'T'HH:mm:ss" else " HH:mm:ss"
            DateShape("LocalDateTime", if (text.contains(':')) day + time else day)
        }

        DATE.matches(text) -> DateShape("LocalDate", "yyyy-MM-dd")
        SLASH_DATE.matches(text) -> DateShape("LocalDate", "yyyy/MM/dd")
        else -> null
    }

    private data class DateShape(val type: String, val pattern: String)

    private fun arrayType(arrays: List<JsonValue.Arr>, hint: String, depth: Int): Guess {
        val elements = arrays.flatMap { it.items }
        if (elements.isEmpty()) {
            return Guess(
                JsonType.ListType(JsonType.Simple("Object")), null,
                listOf("样本是空数组，元素类型按 Object 生成，请在字段表里改成实际类型")
            )
        }
        val inner = inferType(elements, hint, depth + 1)
        return Guess(JsonType.ListType(inner.type), inner.pattern, inner.notes)
    }

    private fun objectType(objects: List<JsonValue.Obj>, hint: String, depth: Int): Guess {
        if (settings.detectDynamicMap && looksLikeDynamicMap(objects)) {
            val values = objects.flatMap { it.fields.values }
            val inner = if (values.isEmpty()) {
                Guess(JsonType.Simple("Object"))
            } else {
                inferType(values, hint, depth + 1)
            }
            return Guess(
                JsonType.MapType(inner.type), inner.pattern,
                inner.notes + "对象的 key 是数据（数字 / 日期 / 编号），已折叠成 Map，避免生成几十个字段"
            )
        }
        return Guess(buildBean(hint, objects, depth), null, emptyList())
    }

    /** 数字 / 日期 / UUID / `item1,item2…` 这类 key，说明对象其实是 Map 而不是固定结构 */
    private fun looksLikeDynamicMap(objects: List<JsonValue.Obj>): Boolean {
        val keys = objects.flatMap { it.fields.keys }
        if (keys.size < 2) return false
        if (!keys.all { dynamicKey(it) }) return false
        return objects.any { it.fields.size >= 2 } || objects.map { it.fields.keys }.distinct().size > 1
    }

    private fun dynamicKey(key: String): Boolean =
        key.all { it.isDigit() } || DATE.matches(key) || UUID.matches(key) ||
            Regex("^[A-Za-z_]+\\d+$").matches(key) || Regex("^\\d{4}-\\d{2}-\\d{2}.*$").matches(key)

    /** 结构签名：字段名 + 每层的类型形状（递归到 [SIGNATURE_DEPTH] 层），相同签名才复用同一个类 */
    private fun signatureOf(objects: List<JsonValue.Obj>): String {
        val keys = objects.flatMap { it.fields.keys }.toSortedSet()
        return keys.joinToString("|") { key ->
            val shapes = objects.mapNotNull { it.fields[key] }
                .mapTo(sortedSetOf()) { shapeOf(it, 0) }
            "$key:${shapes.joinToString(",")}"
        }
    }

    /**
     * 类型形状：对象要带上下一层的字段名与形状，数组带上元素形状。
     * 只看一层会让 `{"a":{"x":1}}` 与 `{"a":{"x":1,"y":2}}` 签名相同而误复用（丢字段）。
     */
    private fun shapeOf(value: JsonValue, depth: Int): String = when (value) {
        is JsonValue.Null -> "null"
        is JsonValue.Bool -> "bool"
        is JsonValue.Num -> "num"
        is JsonValue.Str -> "str"
        is JsonValue.Arr -> if (depth >= SIGNATURE_DEPTH) "arr" else
            "arr(" + value.items.mapTo(sortedSetOf()) { shapeOf(it, depth + 1) }.joinToString(",") + ")"

        is JsonValue.Obj -> if (depth >= SIGNATURE_DEPTH) "obj" else
            "obj(" + value.fields.entries.joinToString(",") { (key, child) ->
                "$key:${shapeOf(child, depth + 1)}"
            } + ")"
    }

    // ------------------------------------------------------------------ 辅助

    private fun uniqueClassName(hint: String): String {
        var name = hint
        var index = 2
        while (!usedClassNames.add(name)) name = hint + index++
        return name
    }

    private fun uniqueFieldName(hint: String, used: MutableSet<String>): String {
        var name = hint
        var index = 2
        while (!used.add(name)) name = hint + index++
        return name
    }

    private fun preview(value: JsonValue): String {
        val text = when (value) {
            is JsonValue.Null -> "null"
            is JsonValue.Bool -> value.value.toString()
            is JsonValue.Num -> value.raw
            is JsonValue.Str -> value.value
            is JsonValue.Arr -> "[${value.items.size} 项]"
            is JsonValue.Obj -> "{${value.fields.size} 个字段}"
        }
        val flat = text.replace("\n", " ").replace("\r", " ").replace("\t", " ")
        return if (flat.length > 48) flat.take(45) + "…" else flat
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val SIGNATURE_DEPTH = 6

        private val DATE = Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$")
        private val SLASH_DATE = Regex("^\\d{4}/\\d{1,2}/\\d{1,2}$")
        private val TIME = Regex("^\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?$")
        private val DATE_TIME = Regex(
            "^\\d{4}-\\d{1,2}-\\d{1,2}[T ]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})?$"
        )
        private val SLASH_DATE_TIME = Regex("^\\d{4}/\\d{1,2}/\\d{1,2}[ T]\\d{1,2}:\\d{2}(:\\d{2})?$")
        private val OFFSET_DATE_TIME = Regex(
            "^\\d{4}-\\d{1,2}-\\d{1,2}[T ]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$"
        )
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val ENUM_CANDIDATE = Regex("^[A-Z][A-Z0-9_]{1,30}$")
    }
}
