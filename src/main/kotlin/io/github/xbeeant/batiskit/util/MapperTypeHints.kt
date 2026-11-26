package io.github.xbeeant.batiskit.util

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTypesUtil

/**
 * MyBatis 场景下的 Java 类型解析与属性收集，补全和引用共用一套规则。
 *
 * 类型属性既可以写全限定名，也可以写 TypeAliasRegistry 的内置别名；
 * `<result property>` / `keyProperty` / `#{bean.prop}` 都按「字段 + getter + setter」取属性名。
 */
object MapperTypeHints {

    /** MyBatis 内置别名（TypeAliasRegistry 默认注册项） */
    val TYPE_ALIASES: Map<String, String> = mapOf(
        "string" to "String", "byte" to "Byte", "long" to "Long", "short" to "Short",
        "int" to "Integer", "integer" to "Integer", "float" to "Float", "double" to "Double",
        "boolean" to "Boolean", "decimal" to "BigDecimal", "bigdecimal" to "BigDecimal",
        "biginteger" to "BigInteger", "object" to "Object", "date" to "Date",
        "map" to "Map", "hashmap" to "HashMap", "list" to "List", "arraylist" to "ArrayList",
        "collection" to "Collection", "iterator" to "Iterator",
        "_byte" to "byte[]", "_short" to "short[]", "_int" to "int[]", "_long" to "long[]",
        "_float" to "float[]", "_double" to "double[]", "_boolean" to "boolean[]",
    )

    /** 把 FQN 或内置别名解析成 Java 类 */
    fun resolveClass(project: Project, name: String): PsiClass? {
        val value = name.trim().trim('"', '\'')
        if (value.isEmpty()) return null
        val fqn = TYPE_ALIASES[value.lowercase()] ?: value
        return JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
    }

    fun typeOf(project: Project, name: String): PsiType? =
        resolveClass(project, name)?.let(PsiTypesUtil::getClassType)

    data class Member(val name: String, val typeText: String)

    /** 字段 + getter + setter 去重后的属性名；setter 的类型取参数类型 */
    fun members(type: PsiType?): List<Member> {
        if (type == null) return emptyList()
        val result = LinkedHashMap<String, Member>()
        val aClass = PsiTypesUtil.getPsiClass(type) ?: return emptyList()
        aClass.allFields.forEach { field ->
            if (!field.hasModifierProperty(PsiModifier.STATIC) && !field.name.contains('$')) {
                result.putIfAbsent(field.name, Member(field.name, field.type.presentableText))
            }
        }
        aClass.allMethods.forEach { method ->
            if (method.hasModifierProperty(PsiModifier.STATIC)) return@forEach
            val name = method.name
            val member = when {
                name.startsWith("get") && name.length > 3 -> property(name.substring(3), method.returnType)
                name.startsWith("is") && name.length > 2 && method.returnType == PsiTypes.booleanType() ->
                    property(name.substring(2), method.returnType)
                name.startsWith("set") && name.length > 3 && method.parameterList.parametersCount == 1 ->
                    property(name.substring(3), method.parameterList.parameters[0].type)
                else -> null
            } ?: return@forEach
            result.putIfAbsent(member.name, member)
        }
        return result.values.toList()
    }

    private fun property(raw: String, type: PsiType?): Member? {
        val name = if (raw.length > 1 && raw[0].isUpperCase() && raw[1].isUpperCase()) {
            raw
        } else {
            raw.replaceFirstChar { it.lowercase() }
        }
        val typeText = type?.presentableText ?: return null
        return Member(name, typeText)
    }
}
