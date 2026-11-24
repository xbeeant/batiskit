package io.github.xbeeant.batiskit.console

import com.intellij.execution.filters.ConsoleDependentInputFilterProvider
import com.intellij.execution.filters.InputFilter
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.psi.search.GlobalSearchScope
import io.github.xbeeant.batiskit.console.grep.GrepFilterRegistry
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 控制台文本过滤：在文本写进 ConsoleView **之前**把不匹配的行去掉 / 改写。
 *
 * 两阶段：先跑规则链（[FilterRule]，默认关闭），再跑级别 / 关键词模式（[ConsoleFilterMode]，默认关闭）。
 * 隐藏日志不可逆——关掉过滤后之前被丢掉的行不会自己回来，所以「快速找错」首选底部工具窗口，
 * 这些开关更适合「只想盯着错误看」的调试现场。
 */
class ConsoleLogInputFilter : InputFilter {

    override fun applyFilter(
        text: String,
        contentType: ConsoleViewContentType,
    ): List<Pair<String, ConsoleViewContentType>>? {
        val settings = GrepConsoleSettings.getInstance().config
        val mode = settings.mode()
        if (!settings.enabled || mode == ConsoleFilterMode.OFF && !settings.ruleFilterEnabled) return null
        // 系统提示（"Process finished with exit code 0"）与用户回显不过滤
        if (contentType === ConsoleViewContentType.SYSTEM_OUTPUT ||
            contentType === ConsoleViewContentType.USER_INPUT
        ) {
            return null
        }

        var current = text
        if (settings.ruleFilterEnabled) {
            val outcome = LogBlockFilter.filter(current, settings.filterRules)
            if (outcome.changed) {
                current = outcome.text
            }
        }
        if (mode != ConsoleFilterMode.OFF) {
            val outcome = LogBlockFilter.filter(current, mode, FilterQuery(settings.filterPattern))
            if (outcome.changed) {
                current = outcome.text
            }
        }
        if (current == text) return null
        // 空列表 = 整段都被过滤掉；非空列表 = 用剩下的文本替换原输出
        return if (current.isEmpty()) {
            emptyList()
        } else {
            listOf(Pair.create(current, contentType))
        }
    }
}

/**
 * 对标原版 `MyConsoleInputFilterProvider`（ConsoleDependent 版本）：
 * 控制台初始化时拿到 ConsoleView 实例，为它注册一个 [io.github.xbeeant.batiskit.console.grep.MyGrepFilter]，
 * Grep 过滤控制台正是寄生在这个过滤器上拿到父控制台的每一行输出。
 */
class GrepConsoleInputFilterProvider : ConsoleDependentInputFilterProvider() {
    override fun getDefaultFilters(
        consoleView: ConsoleView,
        project: Project,
        globalSearchScope: GlobalSearchScope,
    ): List<InputFilter> {
        if (!ModuleToggles.isEnabled(ModuleToggles.GREP)) return emptyList()
        val grepFilter = GrepFilterRegistry.getInstance(project).register(consoleView)
        return listOf(ConsoleLogInputFilter(), grepFilter)
    }
}
