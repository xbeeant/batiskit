package io.github.xbeeant.batiskit.inspect

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * `<select>` 的 resultType / resultMap 与接口方法返回类型的匹配检查。
 *
 * 这类错误运行时的表现是「查出来的不是你想要的」——轻则字段全空、
 * 重则 ClassCastException，而且只有调到那一条语句才会炸。
 * 接口方法与语句标签的对应关系是静态的，类型同样可以在编辑器里提前比对。
 *
 * 判定口径（宁漏不误）：
 *  - 只比 `<select>`（insert / update / delete 没有行类型）；
 *  - resultType / resultMap 的类型解析不到（别名如 `map`、依赖包里的类）就放弃，
 *    不能把「查不到」当成「不匹配」；resultMap 定义可以在**同 namespace 的其它 XML** 里，
 *    一个 Mapper 接口对应多个 XML 文件是合法写法；
 *  - 方法返回 `List<T>` / `Set<T>` / `Collection<T>` / `Optional<T>` / 数组 / 分页包装
 *    （`Page<T>` / `IPage<T>` / `PageInfo<T>` …）时按元素类型比，其余按返回类型本身比
 *    （rowClass 必须能赋给元素类型——`List<父类>` 配子类 resultType 合法）；
 *  - 返回类型是任意泛型包装（统一响应 `Result<List<T>>`、自定义分页容器等）时，
 *    行类型藏在泛型实参里，静态判不准就放弃，不能拿包装类本身当行类型；
 *  - 基本类型元素先装箱再比（`long` ↔ `java.lang.Long`）。
 */
class MapperReturnTypeInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) {
            // 索引没建好时解析不到类，直接不检查，避免整片 XML 报错
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : XmlElementVisitor() {
            override fun visitXmlFile(file: XmlFile) {
                inspect(file, holder)
            }
        }
    }

    private fun inspect(file: XmlFile, holder: ProblemsHolder) {
        val root = file.rootTag ?: return
        if (root.name != "mapper") {
            return
        }
        val selects = root.subTags.filter { it.name == "select" }
        if (selects.isEmpty()) {
            return
        }
        // 解析不到接口（namespace 写错 / mapper 来自依赖包）时整体放弃，与其它检查同一口径
        val iface = MapperXmlResolver.interfaceOf(root) ?: return
        selects.forEach { tag -> check(tag, iface, holder) }
    }

    private fun check(tag: XmlTag, iface: PsiClass, holder: ProblemsHolder) {
        val id = tag.getAttributeValue("id")?.trim().orEmpty()
        if (id.isEmpty()) {
            return
        }
        val method = iface.findMethodsByName(id, false).firstOrNull() ?: return
        val returnType = method.returnType ?: return
        if (returnType == PsiType.VOID) {
            return
        }
        val attr = tag.getAttribute("resultType") ?: tag.getAttribute("resultMap") ?: return
        val rowClassName = rowClassNameOf(attr, tag) ?: return
        val rowClass = JavaPsiFacade.getInstance(tag.project).findClass(rowClassName, scopeOf(tag)) ?: return

        val elementType = elementTypeOf(returnType) ?: return
        val elementClass = elementClassOf(elementType, tag.project) ?: return
        // row 类型必须能赋给元素类型（List<父类> 配子类 resultType 是合法写法）
        if (elementClass == rowClass || rowClass.isInheritor(elementClass, true)) {
            return
        }
        // 返回类型是泛型包装时行类型可能藏在实参里，静态判不准就放弃，别拿包装类当行类型误报
        if (rowTypeHiddenInGenerics(returnType, rowClass)) {
            return
        }
        holder.registerProblem(
            attr,
            "「${attr.name}=$rowClassName」与方法返回类型 \"${returnType.presentableText}\" 不匹配：" +
                "该语句产出的行类型 ${rowClass.name} 不是元素类型 ${elementClass.name} 的实例"
        )
    }

    /**
     * resultMap 取定义里的 `type` 属性；resultType 原样返回。
     * 定义按 [MapperXmlResolver.resultMapDefinitions] 解析——同 namespace 的多个 XML 都算，
     * 解析不到（别名、定义在依赖 jar 里）返回 null 放弃比对。
     */
    private fun rowClassNameOf(attr: XmlAttribute, tag: XmlTag): String? {
        if (attr.name == "resultType") {
            return attr.value
        }
        val mapId = attr.value?.trim().orEmpty()
        if (mapId.isEmpty()) {
            return null
        }
        return MapperXmlResolver.resultMapDefinitions(tag, mapId)
            .firstNotNullOfOrNull { it.getAttributeValue("type") }
    }

    /**
     * 返回类型的「行元素类型」：
     *  - List / Collection / Set / Iterable / Optional 取泛型实参；
     *  - 分页包装（Page / IPage / PageInfo …）与 Collection / Iterable 子类（如 PageHelper 的
     *    `Page<T> extends ArrayList<T>`）同样取泛型实参——它们语义上是「行的集合」而不是行本身；
     *  - 数组取分量类型，其余按返回类型本身；
     *  - 泛型实参缺失（裸 List / 裸 Page）时返回 null 放弃比对。
     */
    private fun elementTypeOf(returnType: PsiType): PsiType? {
        if (returnType is PsiArrayType) {
            return returnType.componentType
        }
        val classType = returnType as? PsiClassType ?: return returnType
        val className = classType.className ?: return classType
        if (className in CONTAINER_TYPES) {
            return classType.parameters.firstOrNull() ?: return null
        }
        if (className in PAGE_WRAPPER_TYPES || isCollectionLike(classType)) {
            return classType.parameters.firstOrNull() ?: return null
        }
        return classType
    }

    /** 是否是 java.lang.Iterable 的子类（PageHelper 的 Page<T> / 自定义 List 子类都算） */
    private fun isCollectionLike(type: PsiClassType): Boolean {
        val clazz = type.resolve() ?: return false
        val iterable = JavaPsiFacade.getInstance(clazz.project)
            .findClass("java.lang.Iterable", GlobalSearchScope.allScope(clazz.project)) ?: return false
        return clazz.isInheritor(iterable, true)
    }

    /**
     * 返回类型是泛型包装（`Result<List<T>>`、自定义分页容器、`Foo<T>` 等）时，
     * 行类型往往藏在泛型实参里而非返回类型本身。只要某个（可递归展开的）实参能容纳 rowClass，
     * 就按「包装」处理、放弃比对；实参解析不到（类型变量 / 依赖缺失）时同样放弃——宁漏不误。
     */
    private fun rowTypeHiddenInGenerics(returnType: PsiType, rowClass: PsiClass): Boolean {
        val classType = returnType as? PsiClassType ?: return false
        val queue = classType.parameters.toMutableList()
        val visited = HashSet<String>()
        var index = 0
        while (index < queue.size) {
            val type = queue[index++]
            if (!visited.add(type.canonicalText)) {
                continue
            }
            when (type) {
                is PsiWildcardType -> type.bound?.let { queue.add(it) }
                is PsiArrayType -> queue.add(type.componentType)
                is PsiClassType -> {
                    val cls = type.resolve() ?: return true
                    if (cls == rowClass || rowClass.isInheritor(cls, true)) {
                        return true
                    }
                    queue.addAll(type.parameters.toList())
                }
            }
        }
        return false
    }

    /** 元素类型解析成类；基本类型按包装类全名解析；解析不到（通配符 / 无界泛型）时返回 null 放弃比对 */
    private fun elementClassOf(type: PsiType, project: com.intellij.openapi.project.Project): PsiClass? {
        if (type is PsiPrimitiveType) {
            val boxedName = boxedNameOf(type.canonicalText) ?: return null
            return JavaPsiFacade.getInstance(project).findClass(boxedName, GlobalSearchScope.allScope(project))
        }
        return (type as? PsiClassType)?.resolve()
    }

    /** 基本类型 → 包装类全名（该版本 SDK 的 getBoxedType 需要 PsiManager/Scope，直接用映射表更稳） */
    private fun boxedNameOf(primitive: String): String? = when (primitive) {
        "int" -> "java.lang.Integer"
        "long" -> "java.lang.Long"
        "boolean" -> "java.lang.Boolean"
        "double" -> "java.lang.Double"
        "float" -> "java.lang.Float"
        "short" -> "java.lang.Short"
        "byte" -> "java.lang.Byte"
        "char" -> "java.lang.Character"
        else -> null
    }

    private fun scopeOf(tag: XmlTag): GlobalSearchScope {
        val module = ModuleUtilCore.findModuleForPsiElement(tag)
        return if (module != null) {
            GlobalSearchScope.moduleWithDependenciesScope(module)
        } else {
            GlobalSearchScope.projectScope(tag.project)
        }
    }

    companion object {
        /** 返回类型是这些容器时按元素类型比对（简名匹配，覆盖 java.util 常见集合） */
        private val CONTAINER_TYPES = setOf("List", "Collection", "Set", "Iterable", "Optional")

        /**
         * 常见分页 / 分页结果包装的简名：方法返回 `Page<User>` 时行类型是 User 而不是 Page。
         * 只按简名认，避免依赖 pagehelper / mybatis-plus / 各家公司自研分页库的类路径；
         * 认不出来的泛型包装还有 `rowTypeHiddenInGenerics` 兜底。
         */
        private val PAGE_WRAPPER_TYPES = setOf(
            "Page", "IPage", "PageInfo", "Pager", "PageResult", "PagedResult", "PageData",
            "PageBean", "PageList", "PagedList", "PageVO", "PageDto", "PageDTO",
            "PageResponse", "Slice", "Pagination"
        )
    }
}
