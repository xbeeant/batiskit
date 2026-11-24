package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 模块级总开关（IDE 全局）。
 *
 * 本插件同时内置了多个常见独立插件的替代实现（Grep Console / Rainbow Brackets / Git Toolbox /
 * String Manipulation），用户本机可能已经装了原版，于是出现两套图标、两套右键、两套控制台过滤。
 * 这里提供一处统一开关：关闭后同时隐藏右键入口、工具窗口、启动活动与控制台 / 高亮扩展点，
 * 让插件可以「按需启用」，而不是只能整个卸载。
 *
 * 默认全部开启；未知模块名按开启处理，旧配置里没有的键自动补齐。
 */
@Service(Service.Level.APP)
@State(name = "BatisKitModuleToggles", storages = [Storage("batiskit-modules.xml")])
class ModuleToggles : PersistentStateComponent<ModuleToggles.State> {

    data class State(var enabled: MutableMap<String, Boolean> = linkedMapOf())

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = State(MODULES.associateWith { state.enabled[it] ?: true }.toMutableMap())
    }

    fun isEnabled(module: String): Boolean = myState.enabled[module] ?: true

    fun setEnabled(module: String, enabled: Boolean) {
        myState.enabled[module] = enabled
    }

    companion object {
        const val GENERATOR = "generator"
        const val MAPPER = "mapper"
        const val LOG = "mybatis-log"
        const val GREP = "grep-console"
        const val SEQUENCE = "sequence"
        const val RAINBOW = "rainbow"
        const val GIT = "git-toolbox"
        const val STRING = "string"
        const val JSON = "json-to-pojo"
        const val SETTER = "all-setter"

        val MODULES: List<String> = listOf(
            GENERATOR, MAPPER, LOG, GREP, SEQUENCE, RAINBOW, GIT, STRING, JSON, SETTER
        )

        val LABELS: Map<String, String> = linkedMapOf(
            GENERATOR to "代码生成 / 模板工作台 / 表结构比对",
            MAPPER to "Mapper XML 格式化、字段补全与 CRUD 预填",
            LOG to "MyBatis Log 工具窗口与还原 SQL",
            GREP to "Grep Console 着色、过滤与日志工具窗口",
            SEQUENCE to "调用链时序图",
            RAINBOW to "彩虹括号与作用域高亮",
            GIT to "Git Toolbox（blame / 远程链接 / 分支工具）",
            STRING to "字符串处理神器",
            JSON to "JSON → Java 对象",
            SETTER to "生成所有 Setter"
        )

        private val fallback by lazy { ModuleToggles() }

        fun getInstance(): ModuleToggles = runCatching {
            ApplicationManager.getApplication()?.getService(ModuleToggles::class.java)
        }.getOrNull() ?: fallback

        /** 无 IDE 容器（纯逻辑测试）时返回 true，不影响默认行为 */
        fun isEnabled(module: String): Boolean =
            runCatching { getInstance().isEnabled(module) }.getOrDefault(true)

        /**
         * action / group id → 模块。纯逻辑，供父菜单过滤与单测共用。
         * 返回 null 表示这是平台级或父级入口，不参与模块过滤。
         */
        fun moduleOfActionId(actionId: String): String? {
            if (actionId.isEmpty()) return null
            fun has(vararg parts: String): Boolean = parts.any { actionId.contains(it, ignoreCase = true) }
            return when {
                has("Rainbow", "BracketScope") -> RAINBOW
                has("GrepConsole", "GrepTab") -> GREP
                has("RestoreMyBatisSql", "MyBatisLog") -> LOG
                has("StringManipulation") -> STRING
                has("SequenceDiagram") -> SEQUENCE
                has("Git", "RemoteLink", "CommitLink", "PullRequest", "IssueLink") -> GIT
                has("FormatMapperXml", "Mapper") -> MAPPER
                has("GenerateAllSetter") -> SETTER
                has("Generate", "CompareTable", "TemplateWorkbench") -> GENERATOR
                has("JsonToPojo") -> JSON
                else -> null
            }
        }
    }
}
