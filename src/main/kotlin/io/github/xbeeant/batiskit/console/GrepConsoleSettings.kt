package io.github.xbeeant.batiskit.console

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Grep Console 的全局设置（IDE 级，跨工程复用）。
 *
 * 颜色统一写成 `浅色hex|深色hex`，平台侧拆成 JBColor，这样亮 / 暗主题切换时
 * 不用手动改配置；只写一个 hex 时两个主题共用。
 */
class GrepConsoleSettingsState {
    /** 总开关：关掉后着色、过滤、工具窗口捕获全部停 */
    var enabled: Boolean = true

    /** 按级别给整行着色 */
    var colorizeLevel: Boolean = true

    /** 自定义规则命中的片段着色 */
    var highlightKeywords: Boolean = true

    /** 工具窗口：捕获 Run / Debug 控制台输出 */
    var captureEnabled: Boolean = true

    /** 工具窗口最多保留多少行（超出丢最早的） */
    var maxEntries: Int = 2000

    /** 控制台文本过滤模式，见 [ConsoleFilterMode]。默认关闭：隐藏日志不可逆 */
    var filterMode: String = ConsoleFilterMode.OFF.name

    /** 过滤用的关键词 / `/正则/`，只在 MATCH_ONLY / HIDE_MATCH 下生效 */
    var filterPattern: String = ""

    /** 规则驱动的输入过滤（Filtering 表），语义对齐原版 Grep Console 插件。默认关闭：隐藏日志不可逆 */
    var ruleFilterEnabled: Boolean = false

    var filterRules: MutableList<FilterRule> = FilterRule.defaultRules()

    /** 控制台里的类 / 方法 / 相对路径可点击导航 */
    var linkNavigation: Boolean = true

    /** key 是 [LogLevel.name] */
    var levelColors: MutableMap<String, String> = defaultLevelColors()

    var rules: MutableList<HighlightRule> = defaultRules()

    /** Grep 子控制台的表达式预设（在子控制台顶部管理，IDE 级共用） */
    var expressionPresets: MutableList<GrepExpressionPreset> = mutableListOf()

    /** 最近 Apply 过的表达式，最新在前 */
    var recentExpressions: MutableList<String> = mutableListOf()

    /** Grep 子控制台命中片段的底色（浅色hex|深色hex），非法值在 normalize 里回退默认 */
    var matchHighlightColor: String = GrepHighlightColor.DEFAULT

    fun colors(): Map<LogLevel, String> = LogLevel.entries.associateWith { levelColors[it.name].orEmpty() }

    fun mode(): ConsoleFilterMode = ConsoleFilterMode.fromName(filterMode)

    /** 旧配置缺键、外部改坏了 xml 时补齐，避免着色静默失效 */
    fun normalize() {
        val defaults = defaultLevelColors()
        defaults.forEach { (key, value) -> if (levelColors[key] == null) levelColors[key] = value }
        maxEntries = maxEntries.coerceIn(100, 20000)
        if (ConsoleFilterMode.entries.none { it.name == filterMode }) filterMode = ConsoleFilterMode.OFF.name
        filterRules.forEach { rule ->
            if (FilterAction.entries.none { it.name == rule.action }) {
                rule.action = FilterAction.REMOVE.name
            }
        }
        expressionPresets = GrepExpressionHistory.normalizePresets(expressionPresets).toMutableList()
        recentExpressions = GrepExpressionHistory.normalizeRecent(recentExpressions).toMutableList()
        matchHighlightColor = GrepHighlightColor.normalize(matchHighlightColor)
    }

    companion object {
        fun defaultLevelColors(): MutableMap<String, String> = mutableMapOf(
            LogLevel.ERROR.name to "#C62828|#FF6B6B",
            LogLevel.WARN.name to "#A56300|#FFB74D",
            LogLevel.INFO.name to "#2E7D32|#81C784",
            LogLevel.DEBUG.name to "#5C6BC0|#9FA8DA",
            LogLevel.TRACE.name to "#707780|#9AA0A6",
            // OTHER 不着色：普通 stdout 保持控制台原色
        )

        fun defaultRules(): MutableList<HighlightRule> = mutableListOf(
            HighlightRule(
                pattern = "\\b(?:Exception|Error|Throwable|Caused by)\\b",
                regex = true,
                color = "#B3261E|#FF8A80",
            ),
            HighlightRule(
                pattern = "\\b(?:timeout|timed out|deadlock)\\b",
                regex = true,
                color = "#A56300|#FFD54F",
            ),
        )
    }
}

@State(name = "BatisKitGrepConsole", storages = [Storage("batiskit-grep-console.xml")])
@Service(Service.Level.APP)
class GrepConsoleSettings : PersistentStateComponent<GrepConsoleSettingsState> {

    private var myState = GrepConsoleSettingsState()

    override fun getState(): GrepConsoleSettingsState = myState

    override fun loadState(state: GrepConsoleSettingsState) {
        myState = state
        myState.normalize()
    }

    /** 插件代码读配置的入口。名字不叫 `state`，是为了不与平台的 getState() 撞 JVM 签名 */
    val config: GrepConsoleSettingsState get() = myState

    companion object {
        fun getInstance(): GrepConsoleSettings =
            ApplicationManager.getApplication().getService(GrepConsoleSettings::class.java)

        /**
         * 把 `浅色|深色` 解析成随主题切换的颜色。非法 hex 返回 null（那一段不着色），
         * 不能因为用户手滑填了个颜色就把整个控制台过滤器弄崩。
         */
        fun parseColor(spec: String?): Color? {
            if (spec.isNullOrBlank()) return null
            val parts = spec.split('|').map { it.trim() }
            val light = parseHex(parts.getOrNull(0)) ?: return null
            val dark = parseHex(parts.getOrNull(1)) ?: light
            return if (light == dark) light else JBColor(light, dark)
        }

        private fun parseHex(value: String?): Color? {
            if (value.isNullOrBlank()) return null
            return runCatching { Color(value.removePrefix("#").toInt(16)) }.getOrNull()
        }
    }
}

/**
 * 命中底色的运行时缓存：控制台过滤器是逐行调用的，不能每行都拆字符串 / 新建 Color。
 * 配置串没变就复用上一次的结果，变了立刻换色（设置页 Apply 后不用重开子控制台）。
 */
class GrepHighlightPalette(private val specProvider: () -> String?) {

    @Volatile
    private var cachedSpec: String? = null

    @Volatile
    private var cachedColor: Color? = null

    fun color(): Color? {
        val spec = specProvider() ?: return null
        val cached = cachedColor
        if (cached != null && cachedSpec == spec) return cached
        val parsed = GrepConsoleSettings.parseColor(spec) ?: return null
        cachedSpec = spec
        cachedColor = parsed
        return parsed
    }
}
