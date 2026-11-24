package io.github.xbeeant.batiskit.api

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType

/**
 * 从接口方法签名读取 cURL 参数信息（纯 PSI 读取，不做任何缓存）。
 *
 * 只在「复制为 cURL」时按需调用：[com.intellij.openapi.util.SmartPointerManager] 持有的
 * PsiMethod 指针在这一刻解引用，参数信息随 PSI 本来就在内存里，解析只是一次
 * 方法签名的轻量遍历（毫秒级）——额外的常驻内存开销为零。
 *
 * 规则：
 *  - `@RequestParam` / 未注解的简单类型参数 -> query（Spring 的默认绑定行为）
 *  - `@PathVariable` -> 替换路径里的 `{name}` 占位符
 *  - `@RequestHeader` -> `-H`
 *  - `@RequestBody` -> 按 DTO 字段生成一层 JSON 骨架（复杂字段给 null / []）
 */
object CurlPsiReader {

    private const val REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
    private const val PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable"
    private const val REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader"
    private const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"

    private val SIMPLE_TYPES = setOf(
        "String", "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean",
        "Character", "BigDecimal", "BigInteger", "Date", "LocalDate", "LocalDateTime"
    )

    fun readParams(method: PsiMethod): CurlBuilder.Params {
        val pathVars = LinkedHashMap<String, String>()
        val query = LinkedHashMap<String, String>()
        val headers = LinkedHashMap<String, String>()
        var body: String? = null
        for (parameter in method.parameterList.parameters) {
            when {
                parameter.hasAnnotation(REQUEST_PARAM) -> {
                    query[paramName(parameter, REQUEST_PARAM)] = sample(parameter.type)
                }
                parameter.hasAnnotation(REQUEST_HEADER) -> {
                    headers[paramName(parameter, REQUEST_HEADER)] = sample(parameter.type)
                }
                parameter.hasAnnotation(PATH_VARIABLE) -> {
                    pathVars[paramName(parameter, PATH_VARIABLE)] = sample(parameter.type)
                }
                parameter.hasAnnotation(REQUEST_BODY) -> {
                    body = bodyJson(parameter.type)
                }
                isSimple(parameter.type) -> {
                    // 没写注解的简单类型参数，Spring 默认按 query 绑定
                    query[parameter.name] = sample(parameter.type)
                }
            }
        }
        return CurlBuilder.Params(pathVars, query.toList(), headers.toList(), body)
    }

    /** 注解的 name / value 属性（先 name 后 value，空白视为未写） */
    private fun paramName(parameter: com.intellij.psi.PsiParameter, annotationFqn: String): String {
        val annotation = parameter.getAnnotation(annotationFqn) ?: return parameter.name
        listOf("name", "value").forEach { attr ->
            val text = annotation.findAttributeValue(attr)?.text?.trim('"')?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        return parameter.name
    }

    /** 按参数类型给样例值，提示参数形态即可，真实值由调用方填 */
    private fun sample(type: PsiType): String {
        val text = type.canonicalText.substringBefore('<').substringAfterLast('.')
        return when (text) {
            "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte",
            "BigDecimal", "BigInteger" -> "1"
            "float", "Float", "double", "Double" -> "1.5"
            "boolean", "Boolean" -> "true"
            "char", "Character" -> "a"
            else -> "text"
        }
    }

    private fun isSimple(type: PsiType): Boolean {
        if (type is PsiPrimitiveType) {
            return true
        }
        val text = type.canonicalText
        if (text.contains('<') || text.endsWith("[]")) {
            return false
        }
        return text.substringAfterLast('.') in SIMPLE_TYPES
    }

    /** @RequestBody 的 JSON 骨架：DTO 的非静态字段取一层，简单类型给样例值，集合给 []，其他对象给 null */
    private fun bodyJson(type: PsiType): String {
        val psiClass = (type as? PsiClassType)?.resolve()
        if (psiClass == null || isSimple(type) || psiClass.isEnum) {
            return "\"${sample(type)}\""
        }
        val fields = psiClass.fields
            .filter { !it.hasModifierProperty("static") && !it.hasModifierProperty("transient") }
            .take(16)
        if (fields.isEmpty()) {
            return "{}"
        }
        return fields.joinToString(",", "{", "}") { field ->
            "\"${field.name}\":${jsonValue(field.type)}"
        }
    }

    /** JSON 字段值：数字/布尔直接输出，字符串加引号，集合给 []，其他对象给 null */
    private fun jsonValue(type: PsiType): String {
        if (type.canonicalText.contains('<') || type.canonicalText.endsWith("[]")) {
            return "[]"
        }
        if (!isSimple(type)) {
            return "null"
        }
        val value = sample(type)
        return if (value == "text" || value == "a") {
            "\"$value\""
        } else {
            value
        }
    }
}
