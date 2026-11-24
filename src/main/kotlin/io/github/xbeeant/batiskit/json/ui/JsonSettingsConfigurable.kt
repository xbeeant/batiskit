package io.github.xbeeant.batiskit.json.ui

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBScrollPane
import io.github.xbeeant.batiskit.json.JsonSettings
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.json.JsonState
import javax.swing.JComponent

/**
 * Settings → Tools → Batis Kit → JSON → Java 对象：全局默认值。
 * 弹窗里的值每次以这里为初始值，确认生成后回写；字段表里的临时编辑不进设置。
 */
class JsonSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.json.settings"

    private val options = JsonOptionsPanel(onRender = {}, onInfer = {}, showTemplateEditor = true)
    private val state: JsonState get() = JsonState.getInstance()
    private var current = JsonSettings()

    override fun getDisplayName(): String = "JSON → Java 对象"

    override fun createComponent(): JComponent {
        options.setDirectory("目标目录由右键位置 / 当前文件决定")
        reset()
        return settingsScrollPane(options.component)
    }

    override fun isModified(): Boolean {
        val edited = current.copyOf()
        options.applyTo(edited)
        return !sameAs(edited, current)
    }

    override fun apply() {
        val edited = current.copyOf()
        options.applyTo(edited)
        state.loadState(edited)
        current = edited
    }

    override fun reset() {
        current = state.state.copyOf()
        options.resetFrom(current)
    }

    override fun disposeUIResources() {
        // 面板由平台释放
    }

    private fun sameAs(left: JsonSettings, right: JsonSettings): Boolean =
        JsonSettings::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .all { field ->
                field.isAccessible = true
                field.get(left) == field.get(right)
            }

    companion object {
        const val ID = "io.github.xbeeant.batiskit.json.settings"

        /**
         * 选项面板是 8+ 行的 BoxLayout，设置页窗口较矮时必须能滚动。
         * 留白用 JScrollPane.setViewportBorder：JViewport.setBorder() 会直接抛
         * IllegalArgumentException("JViewport.setBorder() not supported")，
         * 导致 createComponent() 失败、整个设置页空白。
         */
        fun settingsScrollPane(view: JComponent): JComponent = JBScrollPane(view).apply {
            border = null
            setViewportBorder(UiTokens.empty(8, 12, 8, 12))
        }
    }
}
