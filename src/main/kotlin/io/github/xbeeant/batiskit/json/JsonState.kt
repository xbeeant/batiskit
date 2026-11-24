package io.github.xbeeant.batiskit.json

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 「JSON → Java 对象」的全局默认配置（IDE 级，跨工程复用），存 `options/batiskit-json.xml`。
 *
 * 与生成器配置分开存：两套开关互不相干，升级其中一边不会把另一边的设置带走。
 */
@Service(Service.Level.APP)
@State(name = "BatisKitJsonSettings", storages = [Storage("batiskit-json.xml")])
class JsonState : PersistentStateComponent<JsonSettings> {

    private var settings = JsonSettings()

    override fun getState(): JsonSettings = settings

    override fun loadState(state: JsonSettings) {
        settings = state
    }

    companion object {
        /** 单元测试 / 没有应用容器时退化成进程内实例（与 TemplateSettings 同一套路） */
        private val fallback = JsonState()

        fun getInstance(): JsonState =
            ApplicationManager.getApplication()?.getService(JsonState::class.java) ?: fallback
    }
}
