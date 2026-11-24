package io.github.xbeeant.batiskit.git.issue

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.IssueNavigationConfiguration

/** 文本里识别出来的一处 issue 引用；[range] 是该引用在文本中的字符区间 */
data class IssueRef(val key: String, val url: String, val range: IntRange)

/**
 * Issue Navigation 集成（GitToolBox 的 Issue Navigation integration）。
 *
 * 复用 IDE 自带配置 `Settings / Version Control / Issue Navigation`：
 * 每条规则是「识别 issue 的正则 + 链接模板」，模板里 `$0` 代表匹配到的 issue；
 * 匹配与替换直接交给平台的 [IssueNavigationConfiguration.findIssueLinks]。
 */
object IssueLinks {

    /** 在文本里按项目配置找出 issue 引用（同时支持普通 URL） */
    fun findIn(project: Project, text: String): List<IssueRef> {
        if (text.isBlank()) {
            return emptyList()
        }
        val config = IssueNavigationConfiguration.getInstance(project)
        if (config.links.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            config.findIssueLinks(text).mapNotNull { match ->
                val range = match.range
                val key = range.substring(text)
                if (key.isEmpty()) {
                    null
                } else {
                    IssueRef(key, match.targetUrl, range.startOffset..(range.endOffset - 1))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun open(ref: IssueRef) {
        BrowserUtil.browse(ref.url)
    }
}
