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
import com.intellij.psi.PsiDirectory
import io.github.xbeeant.batiskit.config.GeneratorState
import io.github.xbeeant.batiskit.generate.CodeGenerator
import io.github.xbeeant.batiskit.generate.GenerationPlan
import io.github.xbeeant.batiskit.meta.TableInfoReader
import io.github.xbeeant.batiskit.meta.TableMetadata
import io.github.xbeeant.batiskit.ui.GenerationPreviewDialog
import io.github.xbeeant.batiskit.ui.GeneratorDialog
import io.github.xbeeant.batiskit.write.GenerationStats
import io.github.xbeeant.batiskit.write.ModuleRoots

/**
 * Database 视图右键入口：选中表 -> 配置弹窗 -> 后台渲染 -> 变更预览 -> 一次可撤销写入。
 *
 * 读取表结构、渲染模板、读回已有文件内容都在后台线程的 ReadAction 里，带进度、可取消；
 * 只有真正写 PSI 的那一步回到 EDT。这样多表批量不会冻住 UI，用户也能在写盘前看清
 * 哪些文件是新建、哪些要合并、哪些会被整份覆盖。
 */
class GenerateCodeAction : AnAction() {

    /**
     * 必须用 BGT：多选读取依赖 PlatformDataKeys.PSI_ELEMENT_ARRAY，
     * 该 key 是 BGT-only（EDT 上请求会告警 "requested on EDT" 并返回 null，表现为多选时菜单不出现）；
     * PSI_ELEMENT 是 async key，BGT 上可读（单选一直正常的兜底通道）。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedTables(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tables = selectedTables(e)
        if (tables.isEmpty()) {
            Messages.showInfoMessage(project, "请先在 Database 视图中选择要生成的表", "Batis Kit 代码生成")
            return
        }

        val state = GeneratorState.getInstance()
        val config = state.state.copyOf()

        // 首次使用时做一次「推断」：基础包名取模块里现有的包结构，方言取数据源类型
        if (config.basePackage.isBlank()) {
            moduleOf(project, tables.first())?.let { module ->
                config.basePackage = suggestBasePackage(project, module)
            }
            TableInfoReader.detectDialect(tables.first())?.let { config.dialect = it.name }
        }
        // 配置是全局的，moduleName 只做提示：为空或当前工程没有同名模块时回退到推断
        val moduleManager = ModuleManager.getInstance(project)
        if (moduleManager.findModuleByName(config.moduleName) == null) {
            config.moduleName = moduleOf(project, tables.first())?.name.orEmpty()
        }

        val dialog = GeneratorDialog(project, tables, config)
        if (!dialog.showAndGet()) return

        // 从全局配置 copy 而不是 new：预览 / 来源标记 / 模板排除这些「不在表单上」的字段要保留
        val applied = config.copyOf()
        dialog.applyTo(applied)
        if (applied.moduleName.isBlank()) {
            applied.moduleName = config.moduleName
        }
        val module = ModuleManager.getInstance(project).findModuleByName(applied.moduleName) ?: run {
            Messages.showWarningDialog(project, "找不到模块：${applied.moduleName}", "Batis Kit 代码生成")
            return
        }

        state.loadState(applied.copyOf())

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "准备 Batis Kit 代码生成", true) {

                private var plan: GenerationPlan? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "读取表结构并渲染模板"
                    plan = ReadAction.compute<GenerationPlan, RuntimeException> {
                        val infos = tables.map { table -> TableInfoReader.read(table, applied) }
                        // 记住本次选中的表结构，供 Mapper XML 字段补全 / CRUD 预填离线复用
                        TableMetadata.rememberAll(tables, infos)
                        CodeGenerator.plan(project, module, infos, applied, indicator)
                    }
                }

                /** onSuccess 在 EDT 回调；预览与写盘都不能放在后台线程 */
                override fun onSuccess() {
                    val ready = plan ?: return
                    if (applied.previewBeforeWrite) {
                        val preview = GenerationPreviewDialog(project, ready)
                        if (!preview.showAndGet()) return
                        // 用户在预览里取消勾选的模板记进全局配置，下次生成继续生效
                        applied.disabledTemplates = ready.disabledTemplates.joinToString(",")
                        state.loadState(applied.copyOf())
                    }
                    this@GenerateCodeAction.write(project, module, ready, tables.size)
                }
            }
        )
    }

    /** 整批写在一个写命令里：Ctrl+Z 一次撤销本次生成的全部改动 */
    private fun write(project: Project, module: Module, plan: GenerationPlan, tableCount: Int) {
        var stats: GenerationStats? = null
        WriteCommandAction.runWriteCommandAction(project) {
            stats = CodeGenerator.apply(project, module, plan)
        }
        report(project, stats ?: GenerationStats(), tableCount, plan)
    }

    /**
     * 读取 Database 视图选中的表（BGT 线程约束下的两级通道）：
     * 1. PlatformDataKeys.PSI_ELEMENT_ARRAY —— 树多选的标准 key，BGT 专属
     *    （MybatisX 等现代插件同款；DB_ELEMENTS 是 EDT-only key，BGT 上恒为 null，不再使用）；
     * 2. CommonDataKeys.PSI_ELEMENT —— 单选兜底（async key）
     */
    private fun selectedTables(e: AnActionEvent): List<DbTable> {
        val context = e.dataContext
        context.getData(PlatformDataKeys.PSI_ELEMENT_ARRAY)
            ?.filterIsInstance<DbTable>()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val single = context.getData(CommonDataKeys.PSI_ELEMENT)
        return if (single is DbTable) listOf(single) else emptyList()
    }

    private fun moduleOf(project: Project, table: DbTable): Module? {
        table.virtualFile?.let { vf ->
            ProjectFileIndex.getInstance(project).getModuleForFile(vf)?.let { return it }
        }
        return ModuleRoots.suggestModule(project)
    }

    /**
     * 从模块的源码目录结构推断基础包名：
     * 沿「唯一子目录」向下走，遇到分层目录名（model/mapper/service...）或分支结构就停。
     */
    private fun suggestBasePackage(project: Project, module: Module): String {
        var dir: PsiDirectory = ModuleRoots.javaRoot(project, module) ?: return ""
        val parts = mutableListOf<String>()
        while (true) {
            val subdirectories = dir.subdirectories
            if (subdirectories.size != 1) break
            val next = subdirectories[0]
            if (next.name.lowercase() in LAYER_NAMES) break
            parts += next.name
            dir = next
        }
        return parts.joinToString(".")
    }

    private fun report(project: Project, stats: GenerationStats, tableCount: Int, plan: GenerationPlan) {
        val errors = plan.errors + stats.errors
        val summary = buildString {
            append("表 $tableCount 张：新建 ${stats.created} 个文件")
            if (stats.merged > 0) append("，合并 ${stats.merged} 个已有文件")
            if (stats.skipped.isNotEmpty()) append("，${stats.skipped.size} 个文件无变化")
            if (errors.isNotEmpty()) append("，${errors.size} 个失败")
        }
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        val type = if (errors.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
        val content = buildString {
            append(escape(summary))
            if (errors.isNotEmpty()) {
                append("<br/>")
                errors.take(MAX_ERRORS).forEach { append("<br/>· ").append(escape(it)) }
                if (errors.size > MAX_ERRORS) append("<br/>… 另有 ${errors.size - MAX_ERRORS} 项")
            }
            if (stats.skipped.isNotEmpty()) {
                append("<br/>无变化：").append(escape(stats.skipped.take(5).joinToString("、")))
            }
            if (stats.created + stats.merged > 0) {
                append("<br/>可用 Ctrl+Z 一次撤销本次生成")
            }
        }
        group.createNotification("Batis Kit 代码生成", content, type).notify(project)
    }

    /** 表名 / 模板文件名可能带尖括号，通知内容是 HTML，必须转义 */
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val NOTIFICATION_GROUP = "Batis Kit Generator"
        private const val MAX_ERRORS = 8

        private val LAYER_NAMES = setOf(
            "model", "entity", "po", "pojo", "domain", "dto", "vo", "query", "param",
            "mapper", "dao", "repository", "service", "impl", "controller", "web", "api",
            "config", "common", "util", "utils", "exception", "handler"
        )
    }
}
