package io.github.xbeeant.batiskit.sequence

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 时序图的 IDE 级设置：目前是「跳过基础类 / 工具类」的三份名单，
 * 对应 Settings → Tools → Batis Kit → 时序图。名单不再写死在 [SequenceRules] 里。
 *
 * 字段用整段文本存（每行一条），设置页可直接在文本框里增删、注释、恢复默认；
 * [BasicClassRules.parse] 负责解析，所以导入的设置或手改 XML 都不会把「名单格式」绑死。
 */
class SequenceDiagramSettingsState {
    /** 跳过的包前缀（每行一个），默认 [BasicClassRules.DEFAULT_PREFIXES_TEXT] */
    var basicPackagePrefixes: String = BasicClassRules.DEFAULT_PREFIXES_TEXT

    /** 解析不出全限定名时按简单类名兜底的名单，默认 [BasicClassRules.DEFAULT_SIMPLE_NAMES_TEXT] */
    var basicSimpleNames: String = BasicClassRules.DEFAULT_SIMPLE_NAMES_TEXT

    /** 始终保留、永不跳过的类（全限定名或简单名），默认空 */
    var keepClassNames: String = BasicClassRules.DEFAULT_KEEP_NAMES_TEXT
}

@Service(Service.Level.APP)
@State(
    name = "BatisKitequenceDiagramSettings",
    storages = [Storage("batiskit-sequence-diagram.xml")]
)
class SequenceDiagramSettings : PersistentStateComponent<SequenceDiagramSettingsState> {

    /** 保持单例：调用方持有 state 引用，loadState 只能就地拷字段（与 MapperXmlFormatSettings 同套路） */
    @JvmField
    val state = SequenceDiagramSettingsState()

    override fun getState(): SequenceDiagramSettingsState = state

    override fun loadState(state: SequenceDiagramSettingsState) {
        SequenceDiagramSettingsState::class.java.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            field.set(this.state, field.get(state))
        }
    }

    /** 每次生成 / 重算都现读设置，改完名单不用重启 IDE */
    fun rules(): BasicClassRules = BasicClassRules.parse(
        state.basicPackagePrefixes, state.basicSimpleNames, state.keepClassNames
    )

    companion object {
        /** 纯 JVM 单测 / 没有应用容器时退化为进程内实例 */
        private val fallback = SequenceDiagramSettings()

        fun getInstance(): SequenceDiagramSettings =
            runCatching {
                ApplicationManager.getApplication()?.getService(SequenceDiagramSettings::class.java)
            }.getOrNull() ?: fallback
    }
}
