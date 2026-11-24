package io.github.xbeeant.batiskit.string

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import io.github.xbeeant.batiskit.string.ui.StringOpChooser

/**
 * 编辑器右键的「String Manipulation（字符串处理神器）」菜单。
 *
 * 子菜单按分组列全量操作，**每一项都直接一键转换**，并在菜单文字里给出对当前选区的
 * 实时结果预览（不必先记住「驼峰是哪一个」）。列表由 [StringOps.ALL] 生成，加一个操作
 * 就等于加一个菜单项，不需要在 plugin.xml 里再注册一条。
 */
class StringManipulationGroup : ActionGroup(), DumbAware {

    private val children: Array<AnAction> = buildList {
        add(StringChooserAction())
        add(Separator.getInstance())
        for (group in StringOpGroup.values()) {
            val sub = DefaultActionGroup().apply {
                templatePresentation.text = group.title
                isPopup = true
            }
            StringOps.of(group).forEach { sub.add(StringOpAction(it)) }
            add(sub)
        }
    }.toTypedArray()

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        // 只判断是否有编辑器：菜单 update 触发很频繁，别在这里反复去读系统剪贴板
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }
}

/** 单个操作的一键转换：菜单文字里带一行预览，选中即原地替换。 */
private class StringOpAction(private val op: StringOp) : AnAction(op.title, op.description, null), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val target = StringTransform.targetOf(e)
        e.presentation.isEnabled = target != null
        val sample = target?.text?.take(2000)
        if (sample.isNullOrEmpty()) {
            e.presentation.text = op.title
            return
        }
        val preview = StringOps.preview(op, sample)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(48)
        e.presentation.text = if (preview.isEmpty()) op.title else "${op.title}  →  $preview"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = StringTransform.targetOf(e) ?: return
        StringTransform.apply(target, op)
    }
}

/** 「搜索全部转换…」：一次性列出全部操作 + 预览，适合不常记名字的转换。 */
class StringChooserAction : AnAction("搜索全部转换…"), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val ranges = StringTransform.rangeOptions(e.project, editor)
        if (ranges.isEmpty()) return
        StringOpChooser.show(e.dataContext, ranges) { op, target ->
            // 弹窗开着的时候文档可能被改过，写回前 apply 会把范围收进当前文档
            StringTransform.apply(target, op)
        }
    }
}
