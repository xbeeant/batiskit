package io.github.xbeeant.batiskit.json

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager
import io.github.xbeeant.batiskit.json.ui.JsonGenerationPlan
import io.github.xbeeant.batiskit.json.ui.JsonToPojoDialog
import io.github.xbeeant.batiskit.write.JavaMerger
import io.github.xbeeant.batiskit.write.ModuleRoots
import java.awt.datatransfer.DataFlavor

/**
 * 「JSON → Java 对象」入口：编辑器右键 / 项目视图右键 / 快捷键。
 *
 * JSON 来源按优先级：编辑器选区 → 当前文件整体（看起来像 JSON 时）→ 剪贴板。
 * 目标目录按优先级：选中的目录 → 当前文件所在目录 → 主模块的 Java 源码根。
 * 已存在的类不做覆盖：交给 [JavaMerger] 按「缺什么补什么」合并，手改过的代码不会被冲掉。
 */
class JsonToPojoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = targetDirectory(e, project)
        if (directory == null) {
            Messages.showWarningDialog(project, "找不到目标目录，请在源码目录或包上右键再试", "JSON → Java 对象")
            return
        }

        val text = sourceText(e).orEmpty()
        val packageName = packageOf(e, directory)
        val settings = JsonState.getInstance().state.copyOf().apply {
            if (packageName.isNotBlank()) this.packageName = packageName
        }

        val dialog = JsonToPojoDialog(project, text, packageName, directory, settings)
        if (!dialog.showAndGet()) return
        val plan = dialog.plan()
        JsonState.getInstance().loadState(plan.settings)

        var created = 0
        var merged = 0
        var unchanged = 0
        val errors = mutableListOf<String>()
        val opened = mutableListOf<PsiFile>()
        WriteCommandAction.runWriteCommandAction(project) {
            plan.files.forEach { file ->
                val existing = directory.findFile(file.fileName)
                if (existing == null) {
                    val createdFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(file.fileName, JavaFileType.INSTANCE, file.text)
                    val added = directory.add(createdFile) as? PsiFile ?: createdFile
                    CodeStyleManager.getInstance(project).reformat(added)
                    opened += added
                    created++
                } else if (existing is PsiJavaFile) {
                    val outcome = JavaMerger.merge(existing, file.text, overwriteMethods = false)
                    when {
                        outcome.error != null -> errors += "${file.fileName}：${outcome.error}"
                        outcome.added == 0 -> unchanged++
                        else -> {
                            merged++
                            opened += existing
                        }
                    }
                } else {
                    errors += "${file.fileName}：目标文件不是 Java 文件"
                }
            }
        }

        opened.firstOrNull()?.virtualFile?.let { vf ->
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        notify(project, plan, created, merged, unchanged, errors)
    }

    private fun sourceText(e: AnActionEvent): String? {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel?.selectedText
        if (!selection.isNullOrBlank()) return selection
        val document = editor?.document?.text
        if (!document.isNullOrBlank()) {
            val trimmed = document.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return document
        }
        return clipboardText()
    }

    private fun clipboardText(): String? {
        val transferable = CopyPasteManager.getInstance().contents ?: return null
        return runCatching {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun targetDirectory(e: AnActionEvent, project: Project): PsiDirectory? {
        val element = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (element is PsiDirectory) return element
        e.getData(CommonDataKeys.PSI_FILE)?.containingDirectory?.let { return it }
        val module = ModuleRoots.suggestModule(project) ?: return null
        return ModuleRoots.javaRoot(project, module)
    }

    private fun packageOf(e: AnActionEvent, directory: PsiDirectory): String {
        (e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile)?.packageName
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return JavaDirectoryService.getInstance().getPackage(directory)?.qualifiedName.orEmpty()
    }

    private fun notify(
        project: Project,
        plan: JsonGenerationPlan,
        created: Int,
        merged: Int,
        unchanged: Int,
        errors: List<String>
    ) {
        val summary = buildList {
            add("新建 $created 个、合并 $merged 个")
            if (unchanged > 0) add("无变化 $unchanged 个")
            add("${plan.sampleCount} 份样本")
            if (plan.warnings.isNotEmpty()) add("${plan.warnings.size} 条提示")
            addAll(errors)
        }.joinToString(" · ")
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Batis Kit Generator")
        val target = plan.directory.virtualFile.presentableUrl
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        group.createNotification(
            "JSON → Java 对象",
            summary + "<br>目标目录：$target",
            if (errors.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
        ).notify(project)
    }
}
