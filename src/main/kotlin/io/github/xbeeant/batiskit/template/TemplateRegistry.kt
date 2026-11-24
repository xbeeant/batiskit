package io.github.xbeeant.batiskit.template

import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.TargetFramework
import io.github.xbeeant.batiskit.config.frameworkEnum
import java.io.File
import java.util.jar.JarFile

/**
 * 模板仓库：**三层来源取并集** —— 内置模板（插件资源 `templates/<方案>/`）、
 * 全局用户模板（IDE 设置，跨工程）、项目模板（`<项目根>/.batiskit/templates/<方案>/`）。
 *
 * ## 三层的合并规则
 *
 * | 来源 | 作用范围 | 能做什么 |
 * | --- | --- | --- |
 * | 内置 | 所有工程 | 打底；`templates/` 下的每个文件夹一套方案 |
 * | 全局（设置页） | 本机所有工程 | 与内置同 `id` 时替换内置那个模板；写 `set:` 可自建方案 |
 * | 项目（目录） | 只在当前工程 | **只能新增方案**：方案名与内置 / 全局重名时整目录跳过并报原因 |
 *
 * 已有方案的内容不会被项目模板悄悄改掉——「这套方案到底会生成哪些文件」必须稳定，
 * 否则同事之间、新旧分支之间生成结果会对不上。想改某套方案，用设置页的全局模板（它本就是「我的默认」）。
 *
 * ## 目录即方案
 *
 * 内置的 `resources/templates/` 下的**每个文件夹就是一套模板方案**，文件夹名就是方案名，
 * 文件夹里的 `.tpl` 就是这套方案要跑的全部产物。没有「套」的注册表、也不需要头部写 `set:`：
 * 想加一套方案，新建一个文件夹；想改一套方案的产物清单，往那个文件夹里加删文件。
 * 项目模板与它同构（见 [ProjectTemplates]）。
 *
 * 排版块（`{fields}` 等）**不在 `templates/` 下**，而在资源根的 `blocks/`（见 [TemplateBlocks]）——
 * 块是两套方案共用的片段，不属于任何一套；挪出去之后 `templates/` 里每个文件夹都必然是方案，
 * 扫描不需要任何例外，「一个文件夹 = 一套方案」是字面成立的。
 *
 * ## 包的来源
 *
 * 模板的 `output` 是相对源码根 / 资源根的完整路径，**路径里的目录就是包**：
 * `output: {basePackageDir}/model/{Model}.java` → 文件写到 `io/github/xbeeant/model/`，
 * 正文里写 `package {package};` 即可拿到 `io.github.xbeeant.model`。
 * 因此「包结构」不再是配置项，改包就改模板的 output。
 *
 * 各产物之间的互相引用（import）也由模板自己写：模板开头用
 * `#set($entityFqn = "${cfg.basePackage}.model.${entity}")` 声明它要引用谁，
 * 生成器不替模板记「哪类产物在哪个包」。
 */
object TemplateRegistry {

    /** 一套模板方案 = `templates/` 下的一个文件夹 */
    class TemplateSet(
        /** 文件夹名 */
        val id: String,
        /** 这套对应的目标框架；套内模板没声明时为 null（沿用配置里的框架） */
        val framework: TargetFramework?,
        /** 这套里有几个模板 */
        val count: Int,
        /** 来源：内置 or 项目（配置面板据此在下拉里标注） */
        val source: String = SOURCE_BUILTIN
    ) {
        companion object {
            const val SOURCE_BUILTIN = "builtin"
            const val SOURCE_PROJECT = "project"
        }
    }

    // ------------------------------------------------------------------ 扫描

    /** 一次扫描的结果：方案文件夹 → 模板，外加解析失败的文件与原因 */
    private class BuiltinScan(
        val sets: Map<String, List<TemplateSpec>>,
        val errors: Map<String, String>
    )

    /**
     * 内置模板只在第一次用到时扫描一遍。
     *
     * 它们是打进插件里的资源，运行期不会变；而 [all] / [sets] / [forSet] / [frameworkOf] 都在
     * 生成流程里被反复调用，每次都重读一遍 jar 里的 20 个 `.tpl` 并重新解析头部纯属浪费。
     */
    private val builtinScan: BuiltinScan by lazy { scanBuiltin() }

    /** 内置模板：方案文件夹 → 该文件夹下的模板 */
    fun builtin(): Map<String, List<TemplateSpec>> = builtinScan.sets

    private fun scanBuiltin(): BuiltinScan {
        val errors = LinkedHashMap<String, String>()
        val result = LinkedHashMap<String, MutableList<TemplateSpec>>()
        builtinEntries(errors).forEach { (setDir, fileName, text) ->
            val parsed = TemplateSpec.parse(text, "$setDir/$fileName", builtin = true)
            if (parsed.error != null) {
                errors["$setDir/$fileName"] = parsed.error
                return@forEach
            }
            result.getOrPut(setDir) { mutableListOf() }.add(parsed.spec.copy(set = setDir))
        }
        return BuiltinScan(
            result.mapValues { (_, specs) -> specs.sortedWith(compareBy({ it.order }, { it.id })) },
            errors
        )
    }

    /**
     * 全部可用模板（扁平）：内置打底，全局用户模板替换同 id，项目模板只补充新方案。
     *
     * 去重键是「套 + id」而不是 id —— 目录即方案之后，`dto` 这种东西两套里各有一份，
     * 按 id 去重会让后扫到的那套把另一套吃掉。
     *
     * @param basePath 项目根目录；传 null 表示「没有项目上下文」（设置页、单测），此时只有内置 + 全局
     */
    fun all(basePath: String? = null): List<TemplateSpec> {
        // 内置的解析问题缓存着，用户模板与项目模板的问题每次现扫，所以这里统一重填一次快照
        val found = LinkedHashMap<String, String>()
        found.putAll(builtinScan.errors)
        val merged = LinkedHashMap<String, TemplateSpec>()
        builtin().values.flatten().forEach { merged[keyOf(it.set, it.id)] = it }
        userTemplates(found).forEach { user ->
            // 没写 set 的用户模板视为「改内置模板的内容」：含同 id 内置模板的每一套都换，
            // 只换内容不搬家（套由文件夹决定）
            val targets = if (user.set.isNotBlank()) {
                listOf(keyOf(user.set, user.id))
            } else {
                merged.values.filter { it.builtin && it.id == user.id }.map { keyOf(it.set, it.id) }
                    .ifEmpty { listOf(keyOf("", user.id)) }
            }
            targets.forEach { key ->
                val previous = merged[key]
                merged[key] = if (previous == null) user else user.copy(set = previous.set)
            }
        }
        // 项目模板：只能新增方案。与已有方案重名时整目录跳过 —— 一套方案生成哪些文件必须稳定，
        // 不能因为某个工程放了同名目录，同一套方案在不同工程里生成出不同的东西
        val project = ProjectTemplates.scan(basePath)
        found.putAll(project.errors)
        val occupied = merged.values.mapTo(HashSet()) { it.set }
        project.sets.forEach { (setId, specs) ->
            if (setId in occupied) {
                found["${ProjectTemplates.DIR}/$setId"] =
                    "方案名 $setId 已被内置或全局模板占用：项目模板只能新增方案，这个目录被跳过"
                return@forEach
            }
            specs.forEach { merged[keyOf(it.set, it.id)] = it }
        }
        problemSnapshot = found
        return merged.values.sortedWith(compareBy({ it.order }, { it.id }))
    }

    private fun keyOf(set: String, id: String): String = "$set\u0000$id"

    /** 全局用户模板：解析失败或用户关掉的都跳过，失败原因进 [found] */
    private fun userTemplates(found: MutableMap<String, String>): List<TemplateSpec> =
        TemplateSettings.getInstance().state.templates.mapNotNull { item ->
            if (item.text.isBlank()) {
                found[item.id.ifBlank { "（未命名）" }] = "模板内容为空"
                return@mapNotNull null
            }
            val parsed = TemplateSpec.parse(item.text, "${item.id.ifBlank { "custom" }}.tpl", builtin = false)
            if (parsed.error != null) {
                found[item.id.ifBlank { "（未命名）" }] = parsed.error
                return@mapNotNull null
            }
            if (!item.enabled) {
                return@mapNotNull null
            }
            val spec = parsed.spec
            if (item.name.isNotBlank()) spec.copy(name = item.name) else spec
        }

    // ------------------------------------------------------------------ 方案

    /**
     * 某个模板属于哪套方案。
     *
     * 内置模板的 `set` 由所在文件夹决定；用户模板覆盖同 id 时沿用内置的套（只换内容不搬家），
     * 用户模板写了自己的 `set` 就进那个套。
     */
    fun belongsTo(spec: TemplateSpec, setId: String): Boolean = spec.set == setId

    /** 一套方案里的全部模板 */
    fun forSet(setId: String, basePath: String? = null): List<TemplateSpec> =
        all(basePath).filter { belongsTo(it, setId) }

    /** 一套方案对应哪个目标框架：套内第一个声明了具体框架的模板 */
    fun frameworkOf(setId: String, basePath: String? = null): TargetFramework? =
        forSet(setId, basePath).firstNotNullOfOrNull { frameworkEnumOf(it) }

    /** 框架对应的方案：内置约定 `mybatis` / `mybatis-plus`，找不到就取第一个 */
    fun setOf(framework: TargetFramework, available: List<TemplateSet> = sets()): String {
        val wanted = if (framework == TargetFramework.MYBATIS_PLUS) "mybatis-plus" else "mybatis"
        return available.firstOrNull { it.id == wanted }?.id ?: available.firstOrNull()?.id ?: wanted
    }

    /**
     * 配置里实际生效的套：用户选过就用选的，没选过跟随目标框架的文件夹。
     * 配置里存了套名但那个文件夹已经不存在时，同样回退，不让生成空转。
     */
    fun resolveSet(config: GenerationConfig, available: List<TemplateSet> = sets()): String {
        val chosen = config.templateSet.trim()
        if (chosen.isNotEmpty() && available.any { it.id == chosen }) {
            return chosen
        }
        val fallback = setOf(config.frameworkEnum, available)
        return if (available.any { it.id == fallback }) fallback else available.firstOrNull()?.id ?: fallback
    }

    /** 全部方案：内置在前（`mybatis` 永远第一），项目方案排在后面 */
    fun sets(basePath: String? = null): List<TemplateSet> {
        val projectIds = ProjectTemplates.scan(basePath).sets.keys
        val bySet = LinkedHashMap<String, MutableList<TemplateSpec>>()
        all(basePath).filter { it.set.isNotBlank() }.forEach { bySet.getOrPut(it.set) { mutableListOf() }.add(it) }
        return bySet.entries
            .sortedWith(
                compareBy(
                    { if (it.key == "mybatis") 0 else 1 },
                    { if (it.key in projectIds) 1 else 0 },
                    { it.key }
                )
            )
            .map { (id, specs) ->
                TemplateSet(
                    id = id,
                    framework = specs.firstNotNullOfOrNull { frameworkEnumOf(it) },
                    count = specs.size,
                    source = if (id in projectIds) TemplateSet.SOURCE_PROJECT else TemplateSet.SOURCE_BUILTIN
                )
            }
    }

    private fun frameworkEnumOf(spec: TemplateSpec): TargetFramework? = when (spec.framework) {
        TemplateSpec.FRAMEWORK_MYBATIS -> TargetFramework.MYBATIS
        TemplateSpec.FRAMEWORK_MYBATIS_PLUS -> TargetFramework.MYBATIS_PLUS
        else -> null
    }

    fun find(id: String, basePath: String? = null): TemplateSpec? = all(basePath).firstOrNull { it.id == id }

    /**
     * 解析失败等需要展示给用户的问题：文件（或模板 id） → 原因。
     *
     * 每次 [all] 重建一份快照再整体替换，读到的永远是自洽的一份（生成在后台线程跑、
     * 展示在 EDT，不必担心读到清空到一半的 map）。
     */
    @Volatile
    private var problemSnapshot: Map<String, String> = emptyMap()

    val problems: Map<String, String> get() = problemSnapshot

    // ------------------------------------------------------------------ 渲染

    /** 一份渲染结果：写哪个根（java/resources）、相对路径、文件内容 */
    class Rendered(val spec: TemplateSpec, val path: String, val text: String)

    /** 只渲染路径（用于校验 output 写对没有；正文里的 `{package}` 也从这里来） */
    fun renderPath(spec: TemplateSpec, model: Map<String, Any?>): String =
        pathOf(spec, model, TemplatePlaceholders.namesOf(model))

    private fun pathOf(spec: TemplateSpec, model: Map<String, Any?>, names: Set<String>): String {
        val path = compile(spec, spec.output, names).render(model).text.trim().trimStart('/', '\\')
        if (path.isBlank()) {
            throw TemplateException(spec.fileName, 1, 1, "output 渲染出来是空路径")
        }
        return path
    }

    /** 路径 → 包名：`com/example/model/User.java` → `com.example.model`；资源目录没有包，返回空串 */
    fun packageOf(spec: TemplateSpec, path: String): String {
        if (spec.root != TemplateSpec.ROOT_JAVA) {
            return ""
        }
        return path.substringBeforeLast('/', "").replace('/', '.').trim('.')
    }

    /**
     * 渲染一个模板。
     *
     * 返回 null 表示这份模板这次不产出（`enabled` 为假），不是错误；
     * 模板写错会抛 [TemplateException]，里面的行列号能直接指到用户的模板文件。
     */
    fun render(spec: TemplateSpec, model: Map<String, Any?>): Rendered? {
        // 一份模板要渲染三次（enabled / output / 正文），词表只按模型算一次
        val names = TemplatePlaceholders.namesOf(model)
        if (!isEnabled(spec, model, names)) {
            return null
        }
        val path = pathOf(spec, model, names)
        // 正文里的 `{package}` 由路径推导：包不出现在配置里，只出现在模板的 output 上
        val text = compile(spec, spec.body, names)
            .render(model, mapOf("package" to packageOf(spec, path)))
            .text
        return Rendered(spec, path, text)
    }

    private fun isEnabled(spec: TemplateSpec, model: Map<String, Any?>, names: Set<String>): Boolean {
        val value = compile(spec, spec.enabled, names).render(model).text.trim()
        return value.isNotEmpty() && !value.equals("false", ignoreCase = true)
    }

    /**
     * 编译（结果由 [Template.compile] 内部按「源码 + 词表」缓存，这里不再包一层）。
     *
     * @param names 大括号占位符认可的根名字，来自 [TemplatePlaceholders.namesOf]
     */
    private fun compile(spec: TemplateSpec, source: String, names: Set<String>): Template =
        Template.compile(source, spec.fileName, names)

    // ------------------------------------------------------------------ 资源扫描

    /** 内置模板条目：方案目录 / 文件名 / 文件内容；读取失败的原因写进 [errors] */
    private fun builtinEntries(errors: MutableMap<String, String>): List<Triple<String, String, String>> {
        val url = TemplateRegistry::class.java.getResource("/templates") ?: return emptyList()
        return try {
            when (url.protocol) {
                "file" -> scanDirectory(File(url.toURI()))
                // IDE 的类加载器会给 jar 里的目录返回自定义 URLConnection（不是 JarURLConnection），
                // 所以这里按 URL 文本自己定位 jar 文件，不依赖具体实现类
                else -> scanJar(url)
            }
        } catch (e: Exception) {
            errors["templates"] = "模板目录读取失败：${e.message}"
            emptyList()
        }
    }

    private fun scanDirectory(root: File): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .forEach { setDir ->
                setDir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "tpl" }
                    .sortedBy { it.name }
                    .forEach { file ->
                        result += Triple(setDir.name, file.name, file.readText())
                    }
            }
        return result
    }

    private fun scanJar(url: java.net.URL): List<Triple<String, String, String>> {
        val external = url.toExternalForm()
        val marker = external.indexOf("!/")
        if (marker < 0) {
            return emptyList()
        }
        val jarPath = external.substring(0, marker).substringAfter("file:", "")
        if (jarPath.isBlank()) {
            return emptyList()
        }
        val jarFile = File(java.net.URLDecoder.decode(jarPath, "UTF-8"))
        val result = mutableListOf<Triple<String, String, String>>()
        JarFile(jarFile).use { jar ->
            val prefix = "templates/"
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".tpl") }
                .sortedBy { it.name }
                .forEach { entry ->
                    val parts = entry.name.removePrefix(prefix).split('/')
                    if (parts.size != 2) {
                        return@forEach
                    }
                    val text = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    result += Triple(parts[0], parts[1], text)
                }
        }
        return result
    }
}
