package io.github.xbeeant.batiskit.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Git Toolbox 功能开关与参数（应用级，所有工程共用）。
 *
 * state 是公开的可变 bean：Settings 页把表单值拷进拷出，XmlSerializer 负责落盘。
 */
class GitSettingsState {
    /** 编辑器光标行显示行内 blame（默认关闭，按需在 Settings 或状态栏菜单里打开） */
    var inlineBlameEnabled: Boolean = false

    /**
     * 多行提交信息在当前行下方以块状 inlay 换行展开显示（默认关闭：只保留行内一行，
     * 超宽时省略号截断）。仅在 [inlineBlameEnabled] 打开时生效。
     */
    var blameWrapEnabled: Boolean = false

    /** 自动 fetch 开关 */
    var autoFetchEnabled: Boolean = true

    /** 自动 fetch 间隔（分钟） */
    var autoFetchIntervalMinutes: Int = 10

    /** behind 数增加时通知 */
    var behindTrackerEnabled: Boolean = true

    /** 关闭工程时检查未提交改动 / 待推送提交 */
    var pendingCheckEnabled: Boolean = true

    /** 打开工程后立即执行一次 fetch */
    var autoFetchOnStartup: Boolean = true

    /**
     * 自动 fetch 排除项（逗号分隔）：可以写仓库绝对路径的前缀，也可以写 remote 名。
     * 命中的仓库整仓跳过；只命中 remote 时跳过该 remote。
     */
    var autoFetchExclusions: String = ""

    /** 提交信息校验开关 */
    var commitMessageCheckEnabled: Boolean = true

    /** 提交信息必须匹配的正则，留空不校验 */
    var commitMessageRegex: String = ""

    /** 提交信息必须包含 issue 引用（依赖 IDE 的 Issue Navigation 配置） */
    var commitMessageRequireIssue: Boolean = false

    /** 分支清理的基础分支，留空自动取 main / master / 当前分支 */
    var branchCleanupBase: String = ""

    /** 分支清理的排除 glob（逗号分隔），如 master,main,release/v1* */
    var branchCleanupExcludes: String = "master,main,develop"

    /** 未合并分支超过该天数视为陈旧 */
    var branchStaleDays: Int = 90

    /**
     * 远程链接自定义模板（留空则按 GitHub/GitLab/Gitee/Bitbucket 自动识别）。
     * 可用占位符：{remoteUrl} {path} {line} {branch}
     */
    var remoteLinkTemplate: String = ""

    fun copyOf(): GitSettingsState = GitSettingsState().also { copy ->
        copy.inlineBlameEnabled = inlineBlameEnabled
        copy.blameWrapEnabled = blameWrapEnabled
        copy.autoFetchEnabled = autoFetchEnabled
        copy.autoFetchIntervalMinutes = autoFetchIntervalMinutes
        copy.behindTrackerEnabled = behindTrackerEnabled
        copy.pendingCheckEnabled = pendingCheckEnabled
        copy.autoFetchOnStartup = autoFetchOnStartup
        copy.autoFetchExclusions = autoFetchExclusions
        copy.commitMessageCheckEnabled = commitMessageCheckEnabled
        copy.commitMessageRegex = commitMessageRegex
        copy.commitMessageRequireIssue = commitMessageRequireIssue
        copy.branchCleanupBase = branchCleanupBase
        copy.branchCleanupExcludes = branchCleanupExcludes
        copy.branchStaleDays = branchStaleDays
        copy.remoteLinkTemplate = remoteLinkTemplate
    }
}

@Service(Service.Level.APP)
@State(name = "BatisKitGitSettings", storages = [Storage("batiskit-git.xml")])
class GitSettings : PersistentStateComponent<GitSettingsState> {

    /** 保持单例：所有调用方都持有 `getInstance().state` 的引用，loadState 只能就地拷贝字段。
     *  @JvmField 避免生成与 `PersistentStateComponent.getState()` 同签名的 getter */
    @JvmField
    val state = GitSettingsState()

    override fun getState(): GitSettingsState = state

    override fun loadState(state: GitSettingsState) {
        // 就地拷贝（不能使用 XmlSerializerUtil，那会额外依赖已启动的 Application）。
        // 走反射是为了新增字段自动生效，不用每加一项设置就回来补一行赋值。
        GitSettingsState::class.java.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            field.set(this.state, field.get(state))
        }
    }

    companion object {
        fun getInstance(): GitSettings = ApplicationManager.getApplication().getService(GitSettings::class.java)
    }
}
