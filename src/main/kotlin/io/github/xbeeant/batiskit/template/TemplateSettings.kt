package io.github.xbeeant.batiskit.template

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 用户自定义模板的存储（IDE 全局，跨工程复用）。
 *
 * 存的是模板全文（含 front-matter），所以「新建模板」= 往列表里加一条 `id + text`，
 * 不需要额外的字段映射；`output` 之类都在文本里，用户能整体复制给别人。
 */
@State(name = "BatisKitTemplates", storages = [Storage("batiskit-templates.xml")])
class TemplateSettings : PersistentStateComponent<TemplateSettings.State> {

    class UserTemplate {
        /** 模板 id（与内置模板同名时覆盖内置） */
        var id: String = ""

        /** 用户自己的备注名，留空取模板头部里的 name */
        var name: String = ""

        /** 模板全文，含 front-matter */
        var text: String = ""

        /** 用户开关（与头部里的 enabled 是两回事：这里关掉就不生成） */
        var enabled: Boolean = true
    }

    class State {
        var templates: MutableList<UserTemplate> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {

        /**
         * 测试 / 无应用容器时用的兜底实例。
         * 模板仓库在生成链路里被调到，而单测环境不一定注册了服务，
         * 拿不到服务就退化成一个进程内实例（不持久化，但不至于把生成整条链路打断）。
         */
        private val fallback = TemplateSettings()

        fun getInstance(): TemplateSettings =
            ApplicationManager.getApplication()?.getService(TemplateSettings::class.java) ?: fallback
    }
}
