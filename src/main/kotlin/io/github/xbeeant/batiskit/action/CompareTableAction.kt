package io.github.xbeeant.batiskit.action

import com.intellij.database.psi.DbTable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.GeneratorState
import io.github.xbeeant.batiskit.generate.ArtifactScan
import io.github.xbeeant.batiskit.generate.CodeGenerator
import io.github.xbeeant.batiskit.generate.GenerationPlan
import io.github.xbeeant.batiskit.generate.RenderedArtifact
import io.github.xbeeant.batiskit.meta.TableInfoReader
import io.github.xbeeant.batiskit.meta.TableMetadata
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.template.TemplateSpec
import io.github.xbeeant.batiskit.ui.GenerationPreviewDialog
import io.github.xbeeant.batiskit.write.ModuleRoots

/**
 * Database 视图右键「比对已生成代码」：双向漂移检测 + 选择性同步。
 *
 * 检测本身只读：除了「表里缺 / 类型不一致」，还会报实体里多出来的字段（表删列后留下的）、
 * 以及方案里新增但还没生成的产物文件。确认要同步时，按勾选的模板重跑一次生成链路——
 * 后台渲染 → 变更预览 → 一次可撤销写入，绝不直接往已有文件里塞字段。
 *
 * ## 产物存在性只认磁盘文件
 *
 * 旧实现自己拼了一套「方案 → 模板 output → 源码根」的推导，还用 `JavaPsiFacade.findClass`
 * 判断实体在不在：只要模块推断得和生成时不是同一个、索引没建好、源码根没标记、
 * 或者模板的 enabled / 排除清单没同步，文件明明在磁盘上也会报「尚未生成」。
 * 现在整条检测复用了 [CodeGenerator.scan]，也就是生成时用的那份产物清单与根目录解析；
 * 「生成过没有」只由定位到的 VirtualFile 决定，PSI 只用来读字段。
 */
class CompareTableAction : AnAction() {

    /** 与 GenerateCodeAction 相同：PSI_ELEMENT_ARRAY 是 BGT-only key */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedTables(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tables = selectedTables(e)
        if (tables.isEmpty()) {
            Messages.showInfoMessage(project, "请先在 Database 视图中选择要比对的表", TITLE)
            return
        }
        val config = GeneratorState.getInstance().state.copyOf()
        val module = moduleOf(project, tables.first(), config)
        if (module == null) {
            Messages.showWarningDialog(project, "找不到目标模块，无法定位已生成的代码", TITLE)
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "比对已生成代码", true) {
                private var result: ScanResult? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "读取表结构并定位已生成的产物"
                    result = ReadAction.compute<ScanResult, RuntimeException> {
                        val infos = tables.map { TableInfoReader.read(it, config) }
                        TableMetadata.rememberAll(tables, infos)
                        // 生成时用户选的模块未必等于这里推断出来的那个；把所有含 Java 根的模块都查一遍，
                        // 「文件在别的模块」不能算「没生成过」
                        val candidates = (listOf(module) + ModuleRoots.modulesWithJava(project)).distinct()
                        val scan = CodeGenerator.scan(project, module, infos, config, indicator, candidates)
                        val report = buildReport(project, config, infos, scan)
                        val owner = scan.ownerModule() ?: module
                        if (owner.name != module.name) {
                            report.notes += "产物文件位于模块 ${owner.name}（配置里记的是 ${module.name}），同步会写回 ${owner.name}"
                        }
                        ScanResult(infos, scan, report, owner)
                    }
                }

                /** onSuccess 在 EDT：弹窗与写盘都不能放在后台线程 */
                override fun onSuccess() {
                    val ready = result ?: return
                    val dialog = TableDriftDialog(project, ready.report, ready.scan.activeSpecs)
                    if (!dialog.showAndGet()) return
                    sync(
                        project, ready.module, ready.infos, config,
                        ready.scan.specs, dialog.selectedTemplates()
                    )
                }
            }
        )
    }

    /** 只读检测：实体字段双向比对 + 方案产物的存在性（与生成链路同源） */
    private fun buildReport(
        project: Project,
        config: GenerationConfig,
        infos: List<TableInfo>,
        scan: ArtifactScan
    ): DriftReport {
        val report = DriftReport()
        // 模板解析 / 渲染问题必须可见：否则「一个产物都没算出来」会被误读成「全都没生成」
        scan.errors.forEach { report.notes += it }
        scan.warnings.forEach { report.notes += it }

        infos.forEach { info ->
            if (info.columns.isEmpty()) {
                report.notes += "【${info.tableName}】未读取到表结构（可能是视图或元数据未加载）"
                return@forEach
            }
            val artifacts = scan.artifacts.filter { it.tableName == info.tableName }
            if (artifacts.isEmpty()) {
                report.notes += "【${info.tableName}】当前方案这次没有产出文件（模板被排除或 enabled 为假），跳过比对"
                return@forEach
            }

            val entity = artifacts.firstOrNull {
                DriftRules.isEntity(it.root, it.relativePath, info.entityName)
            }
            if (entity == null) {
                report.notes += "【${info.tableName}】当前模板方案里找不到实体模板，跳过字段比对"
            } else {
                // 文件不存在就别去问索引了：findClass 命中同名类也不能算「这个产物生成过」
                val clazz = if (entity.exists) entityClass(project, entity, info) else null
                when (DriftRules.entityState(entity.exists, clazz != null)) {
                    EntityState.MISSING -> report.items += DriftItem(
                        info.tableName, DriftKind.MISSING_ARTIFACT,
                        "实体类 ${DriftRules.fqnOf(entity.root, entity.relativePath) ?: info.entityName} → ${entity.relativePath}"
                    )

                    EntityState.UNREADABLE -> report.notes +=
                        "【${info.tableName}】实体文件 ${entity.relativePath} 已存在，但暂时读不到 PSI（索引未就绪或文件无法解析），跳过字段比对"

                    EntityState.READY -> compareFields(info, clazz!!, ignoredFields(config), report)
                }
            }

            // 方案里其它产物：同样只按 VirtualFile 判定，不再另算一套路径和源码根
            artifacts.filter { it !== entity }.forEach { artifact ->
                if (!artifact.exists) {
                    report.items += DriftItem(
                        info.tableName, DriftKind.MISSING_ARTIFACT,
                        "${artifact.spec.name} → ${artifact.relativePath}"
                    )
                }
            }
        }
        return report
    }

    /** 实体字段双向比对（缺字段 / 类型不一致 / 表里删列后留下的字段） */
    private fun compareFields(
        info: TableInfo,
        clazz: PsiClass,
        ignored: Set<String>,
        report: DriftReport
    ) {
        val expected = info.columns.filterNot { isIgnored(it, ignored) }.associateBy { it.fieldName }
        val declared = clazz.fields
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .associateBy { it.name }
        expected.forEach { (name, column) ->
            val field = declared[name]
            if (field == null) {
                report.items += DriftItem(
                    info.tableName, DriftKind.MISSING_FIELD,
                    "$name（${column.columnName} ${column.dbType}）"
                )
            } else {
                val actual = field.type.presentableText.replace(" ", "")
                if (actual != column.javaType.simple) {
                    report.items += DriftItem(
                        info.tableName, DriftKind.TYPE_MISMATCH,
                        "$name：表 ${column.dbType} → 应为 ${column.javaType.simple}，实体为 $actual"
                    )
                }
            }
        }
        // 反方向：表里已经删掉的列，实体还留着。只提示、不自动删——可能是人工新增的字段
        declared.keys.filterNot { it in expected || it in HAND_WRITTEN_FIELDS }.forEach { name ->
            report.items += DriftItem(
                info.tableName, DriftKind.EXTRA_FIELD,
                "$name（表中已无对应列，可能是手写的字段，请人工确认）"
            )
        }
    }

    /**
     * 解析实体类：索引优先，索引没建好时直接按产物文件读 PSI。
     * 两条路都失败也不代表「没生成过」——存在性由 [RenderedArtifact.exists] 说了算。
     */
    private fun entityClass(project: Project, artifact: RenderedArtifact, info: TableInfo): PsiClass? {
        val fqn = DriftRules.fqnOf(artifact.root, artifact.relativePath)
        val byIndex = fqn?.let {
            JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.projectScope(project))
        }
        if (byIndex != null) return byIndex
        val file = artifact.existingFile ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        return psi.classes.firstOrNull { it.name == info.entityName } ?: psi.classes.firstOrNull()
    }

    /** 只重跑勾选的模板：未勾选的写进 disabledTemplates，让 plan 阶段直接跳过 */
    private fun sync(
        project: Project,
        module: Module,
        infos: List<TableInfo>,
        config: GenerationConfig,
        allSpecs: List<TemplateSpec>,
        selected: Set<String>
    ) {
        val syncConfig = config.copyOf()
        // 保留用户此前排除的模板（弹窗里根本不列它们），再叠加本次取消勾选的，
        // 否则一次同步会把「预览里关掉的产物」悄悄重新打开
        val disabled = DriftRules.disabledKeys(config.disabledTemplates).toMutableSet()
        disabled += allSpecs.map { "${it.set}:${it.id}" }.filterNot { it in selected }
        syncConfig.disabledTemplates = disabled.joinToString(",")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "同步已生成代码", true) {
                private var plan: GenerationPlan? = null

                override fun run(indicator: ProgressIndicator) {
                    plan = ReadAction.compute<GenerationPlan, RuntimeException> {
                        CodeGenerator.plan(project, module, infos, syncConfig, indicator)
                    }
                }

                override fun onSuccess() {
                    val ready = plan ?: return
                    if (!GenerationPreviewDialog(project, ready).showAndGet()) return
                    var created = 0
                    var merged = 0
                    WriteCommandAction.runWriteCommandAction(project) {
                        val stats = CodeGenerator.apply(project, module, ready)
                        created = stats.created
                        merged = stats.merged
                    }
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Batis Kit Generator")
                        .createNotification(
                            "同步完成",
                            "新建 $created 个文件，合并 $merged 个已有文件；可用 Ctrl+Z 撤销",
                            NotificationType.INFORMATION
                        ).notify(project)
                }
            }
        )
    }

    /** 读取 Database 视图选中的表（与生成动作同一套 BGT 两级通道） */
    private fun selectedTables(e: AnActionEvent): List<DbTable> {
        val context = e.dataContext
        context.getData(PlatformDataKeys.PSI_ELEMENT_ARRAY)
            ?.filterIsInstance<DbTable>()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val single = context.getData(CommonDataKeys.PSI_ELEMENT)
        return if (single is DbTable) listOf(single) else emptyList()
    }

    /**
     * 目标模块：优先用生成时保存下来的 [GenerationConfig.moduleName]。
     * 生成弹窗里用户选的模块会回写全局配置，比对如果只按 DbTable 的 virtualFile 推断，
     * 多模块工程里很容易选中另一个模块，于是所有产物都「找不到」。
     */
    private fun moduleOf(project: Project, table: DbTable, config: GenerationConfig): Module? {
        config.moduleName.trim().takeIf { it.isNotEmpty() }?.let { name ->
            ModuleManager.getInstance(project).findModuleByName(name)?.let { return it }
        }
        table.virtualFile?.let { vf ->
            ProjectFileIndex.getInstance(project).getModuleForFile(vf)?.let { return it }
        }
        return ModuleRoots.suggestModule(project)
    }

    private fun ignoredFields(config: GenerationConfig): Set<String> =
        config.ignoreFields.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 审计字段忽略口径与实体生成一致（列名或 Java 字段名均可） */
    private fun isIgnored(column: ColumnInfo, ignored: Set<String>): Boolean =
        column.fieldName in ignored || column.columnName in ignored

    /** 后台读动作的结果，交给 EDT 展示 / 同步 */
    private data class ScanResult(
        val infos: List<TableInfo>,
        val scan: ArtifactScan,
        val report: DriftReport,
        /** 已有文件实际所在的模块，同步写回它而不是猜出来的模块 */
        val module: Module
    )

    companion object {
        private const val TITLE = "Batis Kit 表结构比对"

        /** 生成器不会产出、但实体里很常见的手写字段，不算「表里删列」的漂移 */
        private val HAND_WRITTEN_FIELDS = setOf(
            "serialVersionUID", "INSTANCE", "logger", "log", "LOGGER", "\$assertionsDisabled"
        )
    }
}
