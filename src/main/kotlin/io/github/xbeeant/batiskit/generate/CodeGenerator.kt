package io.github.xbeeant.batiskit.generate

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.frameworkEnum
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.template.TemplateException
import io.github.xbeeant.batiskit.template.TemplateMigration
import io.github.xbeeant.batiskit.template.TemplateModel
import io.github.xbeeant.batiskit.template.TemplateRegistry
import io.github.xbeeant.batiskit.template.TemplateSpec
import io.github.xbeeant.batiskit.write.CodeWriter
import io.github.xbeeant.batiskit.write.GenerationStats
import io.github.xbeeant.batiskit.write.ModuleRoots

/*
 * 生成编排：对每张表跑一遍「选中的模板方案」里的所有模板。
 *
 * 这里不再知道「实体叫什么、DTO 叫什么、生成到哪个包」——那些都在模板里：
 * 文件名写 `output: {basePackageDir}/dto/{entity}DTO.java`，类名写 `public class ${entity}DTO`，
 * 跨产物引用写 `#set($dtoFqn = "${cfg.basePackage}.dto.${entity}DTO")`。
 * 编排层只做三件事：选出这次要跑的模板方案、建模型、渲染成一份可预览的计划。
 *
 * ## 两阶段
 *
 * - [plan] 纯读：渲染模板 + 取回已存在文件的文本，不创建目录、不改 PSI。
 *   调用方放在后台线程的 ReadAction 里跑，进度可显示、可取消。
 * - [apply] 纯写：只写用户确认过的项，调用方放在 EDT 的 WriteCommandAction 里，
 *   整批是一个 undo 事务。
 *
 * 拆开之后，用户在写盘前能看到「哪些文件是新建、哪些会合并、哪些会被整份覆盖」，
 * 也能逐项取消，不必再靠「生成完再看通知」猜发生了什么。
 *
 * [scan] 是渲染阶段的唯一入口：生成预览、漂移比对都从它拿产物清单，
 * 保证「这次会写哪些文件、这些文件现在在不在」在两条链路里永远一致。
 */
object CodeGenerator {

    /**
     * 只渲染、不写盘。
     *
     * 必须在读动作里调用（后台线程 + ReadAction）：会读表模型、模板资源，
     * 以及目标目录下已有文件的文本。这里绝不创建任何目录或文件。
     */
    fun plan(
        project: Project,
        module: Module,
        tables: List<TableInfo>,
        config: GenerationConfig,
        indicator: ProgressIndicator? = null
    ): GenerationPlan {
        val scan = scan(project, module, tables, config, indicator)
        val plan = GenerationPlan(
            setId = scan.setId,
            config = config,
            templates = scan.specs,
            disabledTemplates = disabledKeys(config)
        )
        plan.errors += scan.errors
        plan.warnings += scan.warnings

        scan.artifacts.forEach { artifact ->
            // 存在性只看 VirtualFile；文本读不出来（编码 / 权限）不影响「这个文件生成过」
            val existing = artifact.existingFile?.let { runCatching { VfsUtil.loadText(it) }.getOrNull() }
            var proposed = artifact.text
            if (existing == null && config.markGenerated) {
                proposed = GeneratedMarker.annotate(proposed, artifact.spec, scan.setId, artifact.fileName)
            }
            plan.files += PlannedFile(
                spec = artifact.spec,
                root = artifact.root,
                relativePath = artifact.relativePath,
                proposedText = proposed,
                merge = artifact.spec.merge,
                existingText = existing,
                kind = kindOf(artifact.exists, existing, proposed, artifact.spec.merge),
                tableName = artifact.tableName
            )
        }
        return plan
    }

    /**
     * 渲染产物清单（纯读，必须在 ReadAction 里调用）。
     *
     * 方案解析 → 框架对齐 → 排除模板 → `enabled` 判定 → output 渲染 → 定位已有文件，
     * 与 [plan] 逐字同源。比对动作也走这里，不再自己拼路径和源码根。
     *
     * @param module 生成 / 同步时写入的目标模块，也是默认的查找模块
     * @param searchModules 查找已有产物时额外覆盖的模块。多模块工程里生成时选的模块未必是
     *   比对时推断出来的那个，只看一个模块会把「文件在别的模块」误报成「没生成过」
     */
    fun scan(
        project: Project,
        module: Module,
        tables: List<TableInfo>,
        config: GenerationConfig,
        indicator: ProgressIndicator? = null,
        searchModules: List<Module> = listOf(module)
    ): ArtifactScan {
        val basePath = project.basePath
        val available = TemplateRegistry.sets(basePath)
        val setId = TemplateRegistry.resolveSet(config, available)
        val allSpecs = TemplateRegistry.forSet(setId, basePath)
        val disabled = disabledKeys(config)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        TemplateRegistry.problems.forEach { (name, reason) ->
            warnings += "模板 $name 不可用：$reason"
        }
        if (allSpecs.isEmpty()) {
            errors += "模板方案 $setId 里没有可用模板，请检查设置里的模板列表"
            return ArtifactScan(setId, allSpecs, emptyList(), emptyList(), errors, warnings)
        }

        val activeSpecs = allSpecs.filterNot { "${it.set}:${it.id}" in disabled }
        if (activeSpecs.isEmpty()) {
            warnings += "当前方案的模板都被排除了，没有需要生成的内容"
            return ArtifactScan(setId, allSpecs, emptyList(), emptyList(), errors, warnings)
        }

        // 升级后的迁移体检：旧自建模板里用到已下线的模型键时，渲染会静默输出空串，
        // 这里在写盘前把问题列进预览，而不是让用户对着缺字段的产物发呆
        activeSpecs.forEach { spec -> warnings += TemplateMigration.inspect(spec) }

        // 方案自带的框架优先：选中自定义套时，代码语义跟着套走
        val effective = alignFramework(config, setId, basePath)
        val ctx = GenContext(effective)
        val artifacts = mutableListOf<RenderedArtifact>()

        indicator?.text = "正在渲染模板（方案 $setId，${tables.size} 张表）"
        tables.forEachIndexed { tableIndex, table ->
            if (indicator?.isCanceled == true) throw ProcessCanceledException()
            indicator?.fraction = tableIndex.toDouble() / tables.size.coerceAtLeast(1)

            if (table.columns.isEmpty()) {
                errors += "${table.tableName}: 未读取到表结构（可能是视图或元数据未加载）"
                return@forEachIndexed
            }
            val model = try {
                TemplateModel.build(table, ctx)
            } catch (e: TemplateException) {
                errors += "${table.tableName}: ${e.message}"
                return@forEachIndexed
            }

            activeSpecs.forEach { spec ->
                val rendered = try {
                    TemplateRegistry.render(spec, model)
                } catch (e: TemplateException) {
                    // 报错带上模板文件名与行列号，用户能直接定位到自己写的哪一行
                    errors += "${table.tableName}/${spec.fileName}: ${e.message}"
                    null
                } ?: return@forEach // enabled 为假：这次不产出，不该被当成「没生成」

                val path = rendered.path.replace('\\', '/').trimStart('/')
                if (path.isBlank() || path.substringAfterLast('/').isBlank()) {
                    errors += "${spec.fileName}: output 渲染出来不是合法文件路径（$path）"
                    return@forEach
                }
                val existing = locateExisting(rendered.spec.root, path, searchModules)
                artifacts += RenderedArtifact(
                    tableName = table.tableName,
                    spec = spec,
                    root = rendered.spec.root,
                    relativePath = path,
                    text = rendered.text,
                    existingFile = existing?.first,
                    ownerModule = existing?.second
                )
            }
        }
        return ArtifactScan(setId, allSpecs, activeSpecs, artifacts, errors, warnings)
    }

    /**
     * 落盘。调用方需包在 EDT 的 WriteCommandAction 内。
     * 只写 [GenerationPlan.selected] 里用户确认过的项，整批共享一个撤销事务。
     */
    fun apply(project: Project, module: Module, plan: GenerationPlan): GenerationStats {
        val stats = GenerationStats()
        val writer = CodeWriter(project, module, plan.config.overwriteMethods)
        plan.selected().forEach { file ->
            writer.writeRelative(
                file.root, file.relativePath, file.proposedText, file.merge, stats
            )
        }
        return stats
    }

    /** 兼容入口：跳过预览直接生成（供脚本与旧调用方使用，仍需包在写动作里） */
    fun generate(
        project: Project,
        module: Module,
        tables: List<TableInfo>,
        config: GenerationConfig
    ): GenerationStats = apply(project, module, plan(project, module, tables, config))

    /** `方案:模板id` 的排除清单，配置里以逗号分隔 */
    fun disabledKeys(config: GenerationConfig): Set<String> = config.disabledTemplates.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** 是否「新建」只认文件本身；文本读不出来时保守按合并，不假装文件不存在 */
    private fun kindOf(fileExists: Boolean, existing: String?, proposed: String, merge: String): ChangeKind = when {
        !fileExists -> ChangeKind.CREATE
        merge == TemplateSpec.MERGE_OVERWRITE -> ChangeKind.OVERWRITE
        existing == null -> ChangeKind.MERGE
        normalize(existing) == normalize(proposed) -> ChangeKind.UNCHANGED
        else -> ChangeKind.MERGE
    }

    /** 忽略行尾与文件末尾空行差异，避免二次生成时全是「无意义变化」 */
    private fun normalize(text: String): String = text.replace("\r\n", "\n").trimEnd()

    /**
     * 只读地定位目标根。与 [CodeWriter] 的写入版区别：这里绝不调 ensureRelative / createSubdirectory，
     * 目录还不存在时返回 null，让产物被判为「新建」，真正创建留给写盘阶段。
     */
    private fun readRoot(module: Module, root: String): VirtualFile? {
        val manager = ModuleRootManager.getInstance(module)
        val roots = manager.contentRoots
        if (root == TemplateSpec.ROOT_RESOURCES) {
            roots.forEach {
                it.findFileByRelativePath(ModuleRoots.RESOURCE_PATH)
                    ?.takeIf { vf -> vf.isDirectory }
                    ?.let { return it }
            }
            // 从 src/main/java 反推兄弟 resources
            roots.asSequence()
                .mapNotNull { it.findFileByRelativePath(ModuleRoots.JAVA_PATH) }
                .firstOrNull()
                ?.parent?.parent
                ?.takeIf { it.name == "src" }
                ?.findChild("resources")
                ?.takeIf { it.isDirectory }
                ?.let { return it }
            roots.forEach { r ->
                if (r.name == "src" || r.name == "main" || r.name == "src/main") {
                    r.findChild("resources")?.takeIf { it.isDirectory }?.let { return it }
                }
            }
            return null
        }
        roots.forEach {
            it.findFileByRelativePath(ModuleRoots.JAVA_PATH)
                ?.takeIf { vf -> vf.isDirectory }
                ?.let { return it }
        }
        val sourceRoots = manager.sourceRoots
        return sourceRoots.firstOrNull { it.name == "java" } ?: sourceRoots.firstOrNull()
    }

    /** 在候选模块里找已有的产物文件；返回文件与它所属的模块 */
    private fun locateExisting(root: String, relativePath: String, modules: List<Module>): Pair<VirtualFile, Module>? {
        modules.distinct().forEach { candidate ->
            val base = readRoot(candidate, root) ?: return@forEach
            val file = base.findFileByRelativePath(relativePath)?.takeIf { !it.isDirectory } ?: return@forEach
            return file to candidate
        }
        return null
    }

    /** 套声明了具体框架且与配置不一致时，以套为准（自定义套可以整包换成另一套写法） */
    private fun alignFramework(config: GenerationConfig, setId: String, basePath: String?): GenerationConfig {
        val framework = TemplateRegistry.frameworkOf(setId, basePath) ?: return config
        if (framework == config.frameworkEnum) return config
        return config.copyOf().also { it.frameworkEnum = framework }
    }
}
