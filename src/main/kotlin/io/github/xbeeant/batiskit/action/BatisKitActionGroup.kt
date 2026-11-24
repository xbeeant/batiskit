package io.github.xbeeant.batiskit.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 编辑器 / 项目视图右键的统一父菜单。
 *
 * 子项各自还有上下文判断（例如 Git 链接要仓库、字符串转换要选区），这里只负责
 * 「没有工程 / 没有编辑器或文件上下文时整个父菜单不出现」，避免在任何空白处右键
 * 都多出一个点开是空的 Batis Kit 菜单。
 */
class BatisKitActionGroup : DefaultActionGroup(), DumbAware {

    override fun update(event: AnActionEvent) {
        val hasContext = event.project != null && (
            event.getData(CommonDataKeys.EDITOR) != null ||
                event.getData(CommonDataKeys.VIRTUAL_FILE) != null ||
                event.getData(CommonDataKeys.PSI_ELEMENT) != null
            )
        event.presentation.isEnabledAndVisible = hasContext
    }

    /** 按模块开关裁掉右键子项；Separator 与无 id 的动态项原样保留 */
    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val all = super.getChildren(event)
        if (ModuleToggles.MODULES.all { ModuleToggles.isEnabled(it) }) return all
        val manager = ActionManager.getInstance()
        return all.filter { action ->
            val id = manager.getId(action) ?: return@filter true
            val module = ModuleToggles.moduleOfActionId(id) ?: return@filter true
            ModuleToggles.isEnabled(module)
        }.toTypedArray()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
