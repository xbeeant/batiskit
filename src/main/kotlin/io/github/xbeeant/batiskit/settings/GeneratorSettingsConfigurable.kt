package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.options.SearchableConfigurable
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.GeneratorState
import io.github.xbeeant.batiskit.ui.ConfigPanel
import javax.swing.JComponent

/**
 * Settings → Tools → Batis Kit → Generator
 *
 * 这里维护的是全局默认配置（与工程无关），右键生成时弹窗里的值以它作为初始值。
 */
class GeneratorSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.settings"

    private val panel = ConfigPanel(showModuleChooser = false)
    private val state: GeneratorState get() = GeneratorState.getInstance()

    private var current: GenerationConfig = GenerationConfig()

    override fun getDisplayName(): String = "Generator"

    override fun createComponent(): JComponent {
        reset()
        return panel.component
    }

    override fun isModified(): Boolean {
        val edited = current.copyOf()
        panel.applyTo(edited)
        return !sameAs(edited, current)
    }

    override fun apply() {
        // 生成弹窗会校验，设置页以前直接保存：非法 / 空包名能静默写进全局配置
        panel.validate()?.let { throw com.intellij.openapi.options.ConfigurationException(it) }
        val edited = current.copyOf()
        panel.applyTo(edited)
        state.loadState(edited)
        current = edited
    }

    override fun reset() {
        current = state.state.copyOf()
        panel.resetFrom(current, emptyList())
    }

    override fun disposeUIResources() {
        // 面板由平台释放
    }

    private fun sameAs(left: GenerationConfig, right: GenerationConfig): Boolean =
        GenerationConfig::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .all { field ->
                field.isAccessible = true
                field.get(left) == field.get(right)
            }

    companion object {
        const val ID = "io.github.xbeeant.batiskit.settings"
    }
}
