package io.github.xbeeant.batiskit.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * MyBatis Log 的全局设置（所有工程共用，与 Git Toolbox 的设置同一套写法）。
 *
 * 新增字段一律要有默认值：老用户从旧版本升级时 XML 里没有这个字段，
 * 平台反序列化会保留默认值，不需要写迁移代码。
 */
class MyBatisLogSettingsState {
    /** 自动捕获 Run / Debug 控制台输出 */
    var captureEnabled: Boolean = true

    /** 默认格式化 SQL（列表预览与复制都生效，工具窗口里可临时切换） */
    var formatSql: Boolean = true

    /** 复制时补一个分号 */
    var appendSemicolon: Boolean = false

    /** 把 `java.util.Date` 的 toString 转成 yyyy-MM-dd HH:mm:ss */
    var normalizeDate: Boolean = true

    /** 最多保留的语句条数（超出丢最早的） */
    var maxEntries: Int = 200

    // ---------------------------------------------------------------- 日志解析

    /** SQL 行的识别前缀；自定义日志格式时对齐 */
    var preparingPrefix: String = MyBatisLogConfig.DEFAULT_PREPARING

    /** 参数行的识别前缀 */
    var parametersPrefix: String = MyBatisLogConfig.DEFAULT_PARAMETERS

    /** logback 里 `%m` 与 `%n` 之间额外写的字符，解析前从行尾剥掉 */
    var endMask: String = ""

    /** 单行日志超过该长度不解析，0 不限制 */
    var maxLineLength: Int = 0

    /** 识别 iBatis 2.x 的 PreparedStatement / Parameters[] / Types[] */
    var iBatisEnabled: Boolean = true

    // ---------------------------------------------------------------- 输出格式

    /** 命中关键字的语句不在列表里显示（按行或逗号分隔，非破坏性） */
    var filterKeywords: String = ""

    /** 批量复制时带 `-- #序号` 头 */
    var indexLog: Boolean = true

    /** 批量复制时带 `-- Total: n` / `-- Updates: n` 尾注 */
    var impactLog: Boolean = true

    /** 简单格式化：只在主要关键字前换行，不缩进 AND / OR */
    var simpleFormat: Boolean = false

    /** 日志里带 `--` 注释时让注释另起一行 */
    var newLineWithComment: Boolean = false
}

/** 把持久化状态拍成解析器能直接消费的配置快照 */
fun MyBatisLogSettingsState.toConfig(): MyBatisLogConfig = MyBatisLogConfig(
    preparingPrefix = preparingPrefix,
    parametersPrefix = parametersPrefix,
    endMask = endMask,
    maxLineLength = maxLineLength,
    iBatisEnabled = iBatisEnabled,
    filteredKeywords = splitFilterKeywords(filterKeywords),
    indexLog = indexLog,
    impactLog = impactLog,
)

/** 关键字按行或逗号分隔，去空、去重，大小写不敏感比较留给调用方 */
fun splitFilterKeywords(raw: String): List<String> = raw
    .split(Regex("[,\\r\\n]+"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

@Service(Service.Level.APP)
@State(name = "BatisKitLogSettings", storages = [Storage("batiskit-log.xml")])
class MyBatisLogSettings : PersistentStateComponent<MyBatisLogSettingsState> {

    /** 保持单例：调用方都持有 `getInstance().state` 的引用，loadState 只能就地拷贝字段。
     *  @JvmField 避免生成与 `PersistentStateComponent.getState()` 同签名的 getter */
    @JvmField
    val state = MyBatisLogSettingsState()

    override fun getState(): MyBatisLogSettingsState = state

    override fun loadState(state: MyBatisLogSettingsState) {
        // 与 GitSettings 同一套写法：就地拷贝，保持所有调用方持有的 state 单例不变
        MyBatisLogSettingsState::class.java.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            field.set(this.state, field.get(state))
        }
    }

    companion object {
        fun getInstance(): MyBatisLogSettings = ApplicationManager.getApplication().getService(MyBatisLogSettings::class.java)
    }
}
