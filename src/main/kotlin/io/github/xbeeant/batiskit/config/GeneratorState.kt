package io.github.xbeeant.batiskit.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 全局记住上一次的生成配置（与具体工程无关），
 * 下次在任意工程里右键打开弹窗时直接带出来。
 *
 * 应用级配置存放在 IDE 全局配置目录（options/batiskit-generator.xml），
 * 不再写入工程的 .idea 目录。
 *
 * 注意：moduleName 只是「上次使用的模块名」提示；换工程后若找不到同名模块，
 * 弹窗会回退到推断的模块（见 GenerateCodeAction）。
 */
@Service(Service.Level.APP)
@State(name = "BatisKitGeneratorSettings", storages = [Storage("batiskit-generator.xml")])
class GeneratorState : PersistentStateComponent<GenerationConfig> {

    private var config: GenerationConfig = GenerationConfig()

    override fun getState(): GenerationConfig = config

    override fun loadState(state: GenerationConfig) {
        config = state
    }

    companion object {
        /** 全局实例（应用级） */
        fun getInstance(): GeneratorState = ApplicationManager.getApplication().service<GeneratorState>()

        /** 兼容旧的工程级调用签名 */
        @Deprecated("配置已全局化，与工程无关；改用 getInstance()", replaceWith = ReplaceWith("getInstance()"))
        fun getInstance(project: Project): GeneratorState = getInstance()
    }
}
