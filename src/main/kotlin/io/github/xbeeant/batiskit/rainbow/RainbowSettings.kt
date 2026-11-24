package io.github.xbeeant.batiskit.rainbow

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.ProjectManager

/**
 * 彩虹括号的全局设置（IDE 级，跨工程复用）。
 *
 * 与插件自带的「语义彩虹高亮」是两套独立开关：这里只管括号，关掉不影响 IDE 对局部变量 / 参数的着色。
 */
@Service(Service.Level.APP)
@State(name = "BatisKitRainbowBrackets", storages = [Storage("batiskit-rainbow-brackets.xml")])
class RainbowSettings : PersistentStateComponent<RainbowSettings.State> {

    class State {
        /** 总开关 */
        var enabled: Boolean = true

        /** XML / HTML 标签的尖括号按标签嵌套层级着色（默认开；Java 泛型与比较运算符不参与） */
        var xmlTagBrackets: Boolean = true

        /** 标签名本身也按层级着色（默认关：早期版本只给尖括号上色，保持升级后的视觉不变） */
        var rainbowifyTagNames: Boolean = false

        /** 参与循环的颜色数，1..[RainbowColors.COUNT]（取 RainbowColors.KEYS 的前 N 个） */
        var colorCount: Int = RainbowColors.DEFAULT_ACTIVE_COLORS

        /** 只在这些语言 / 文件里生效，逗号分隔（语言 ID、文件名或扩展名）；留空 = 所有语言 */
        var onlyLanguages: String = ""

        /** 排除这些语言 / 文件，逗号分隔；优先级高于 [onlyLanguages] */
        var excludeLanguages: String = ""

        /** 超过这个大小的文件整体跳过，避免大文件每次重算层级；单位 KB */
        var maxFileSizeKb: Int = 1024

        /** 颜色生成器上次选择的色相 / 亮度，重开设置页时带回 */
        var paletteHue: String = RainbowPalette.Hue.BLUE.name
        var paletteLuminosity: String = RainbowPalette.Luminosity.BRIGHT.name
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {

        /** 单测 / 没有应用容器时的兜底，保证纯逻辑链路不被服务容器卡住 */
        private val fallback by lazy { RainbowSettings() }

        fun getInstance(): RainbowSettings =
            ApplicationManager.getApplication()?.getService(RainbowSettings::class.java) ?: fallback

        /** 颜色数可能来自手改的 xml，读到脏数据时收进合法区间 */
        fun colorCount(): Int = getInstance().state.colorCount.coerceIn(1, RainbowColors.COUNT)

        /** 黑白名单 + 扩展名过滤，交给纯逻辑 [RainbowFilter] */
        fun acceptsFile(languageId: String, fileName: String): Boolean =
            RainbowFilter.accepts(
                languageId,
                fileName,
                getInstance().state.onlyLanguages,
                getInstance().state.excludeLanguages,
            )

        /** 兼容旧调用：只按语言 ID 判断（无文件名），黑名单仍然生效 */
        fun acceptsLanguage(languageId: String): Boolean = acceptsFile(languageId, "")

        fun maxFileLength(): Int = getInstance().state.maxFileSizeKb.coerceAtLeast(1) * 1024

        /** 设置改完立即重跑所有打开工程的高亮，不用重新打开文件 */
        fun restartHighlighting() {
            val application = ApplicationManager.getApplication() ?: return
            if (application.isDisposed) return
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) {
                    // 无参 restart() 是最老的重载（重跑该工程所有文件的高亮）；
                    // restart(String reason) 要 2024.2+ 才有，为兼容 2024.1 不用它。
                    DaemonCodeAnalyzer.getInstance(project).restart()
                }
            }
        }
    }
}
