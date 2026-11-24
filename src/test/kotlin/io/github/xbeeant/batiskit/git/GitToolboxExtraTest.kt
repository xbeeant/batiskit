package io.github.xbeeant.batiskit.git

import io.github.xbeeant.batiskit.git.blame.BlameRenderer
import io.github.xbeeant.batiskit.git.commit.CommitMessageRules
import io.github.xbeeant.batiskit.git.config.BranchCleanup
import io.github.xbeeant.batiskit.git.config.FetchExclusions
import io.github.xbeeant.batiskit.git.config.GitPatterns
import io.github.xbeeant.batiskit.git.link.RepositoryLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git Toolbox 新增能力的纯逻辑测试：
 * 设置解析 / glob、fetch 排除、最近分支排序、分支清理、远程链接、blame 高亮分段、提交信息校验。
 */
class GitToolboxExtraTest {

    // ------------------------------------------------------------------ 设置解析与 glob

    @Test
    fun `逗号分隔文本解析去空去重`() {
        assertEquals(
            listOf("master", "main", "release"),
            GitPatterns.parseCsv(" master, main，release ;; , main ")
        )
        assertEquals(emptyList<String>(), GitPatterns.parseCsv("   "))
    }

    @Test
    fun `glob 支持星号与问号`() {
        assertTrue(GitPatterns.matchesGlob("release/v1*", "release/v1.2"))
        assertTrue(GitPatterns.matchesGlob("feat/?", "feat/a"))
        assertFalse(GitPatterns.matchesGlob("feat/?", "feat/ab"))
        assertTrue(GitPatterns.anyMatches(listOf("master", "hotfix/*"), "hotfix/1"))
        assertFalse(GitPatterns.anyMatches(listOf("master", "hotfix/*"), "feature/x"))
    }

    // ------------------------------------------------------------------ 自动 fetch 排除

    @Test
    fun `仓库路径命中排除项时整仓跳过`() {
        val exclusions = listOf("E:/work/big-repo")
        assertTrue(FetchExclusions.repoExcluded("E:/work/big-repo", exclusions))
        assertFalse(FetchExclusions.repoExcluded("E:/work/small", exclusions))
    }

    @Test
    fun `remote 名命中排除项时只跳过该 remote`() {
        val exclusions = listOf("upstream")
        assertTrue(FetchExclusions.remoteExcluded("upstream", exclusions))
        assertFalse(FetchExclusions.remoteExcluded("origin", exclusions))
        assertEquals(listOf("origin", "backup"), FetchExclusions.remotesToFetch(listOf("origin", "upstream", "backup"), exclusions))
    }

    // ------------------------------------------------------------------ 最近分支排序

    @Test
    fun `最近分支记录按使用顺序去重且最多五个`() {
        var list = emptyList<String>()
        listOf("main", "dev", "feat/a", "dev", "feat/b", "feat/c", "feat/d").forEach {
            list = RecentBranchOrder.record(list, it)
        }
        assertEquals(RecentBranchOrder.MAX, list.size)
        // 最近使用的在最前
        assertEquals("feat/d", list.first())
        assertEquals("feat/c", list[1])
        // 被重新使用的 dev 排在后面仍未重复
        assertEquals(listOf("feat/d", "feat/c", "feat/b", "dev", "feat/a"), list)
        assertEquals(list.size, list.distinct().size)
        assertFalse(list.contains("main"))
    }

    @Test
    fun `弹窗顺序为最久未使用在前`() {
        assertEquals(listOf("feat/a", "dev", "main"), RecentBranchOrder.forPopup(listOf("main", "dev", "feat/a")))
    }

    // ------------------------------------------------------------------ 分支清理

    @Test
    fun `解析 git branch 输出去掉标记`() {
        assertEquals(
            listOf("main", "feature/x", "wt"),
            BranchCleanup.parseBranchLines(listOf("* main", "  feature/x", "+ wt", ""))
        )
    }

    @Test
    fun `已合并与陈旧分支进入候选并排除保留分支`() {
        val candidates = BranchCleanup.select(
            merged = listOf("main", "old-feature", "release/v1"),
            notMergedIdle = mapOf("stale" to 120, "fresh" to 3, "main" to 500),
            current = "dev",
            base = "main",
            excludes = listOf("main", "release/*"),
            staleDays = 90
        )
        assertEquals(listOf("old-feature", "stale"), candidates.map { it.branch })
        assertTrue(candidates.first().merged)
        assertEquals(120, candidates.last().idleDays)
    }

    // ------------------------------------------------------------------ 远程链接

    @Test
    fun `提交链接按平台拼接`() {
        assertEquals(
            "https://github.com/o/r/commit/abc123",
            RepositoryLinks.commitLink("git@github.com:o/r.git", "abc123")
        )
        assertEquals(
            "https://bitbucket.org/t/p/commits/abc123",
            RepositoryLinks.commitLink("https://bitbucket.org/t/p.git", "abc123")
        )
    }

    @Test
    fun `提交链接模板含 sha 占位符时优先生效`() {
        assertEquals(
            "https://git.example.com/o/r/-/commit/abc123",
            RepositoryLinks.commitLink(
                "https://git.example.com/o/r.git",
                "abc123",
                "{remoteUrl}/-/commit/{sha}"
            )
        )
        // 模板里没有 {sha} 时不能用于提交链接
        assertNull(
            RepositoryLinks.commitLink("https://git.unknown.com/o/r.git", "abc123", "{remoteUrl}/{path}#L{line}")
        )
    }

    @Test
    fun `azure devops 文件链接带行号`() {
        val link = RepositoryLinks.buildLink(
            "https://dev.azure.com/org/proj/_git/repo",
            "main",
            "src/A.java",
            12,
            null
        )
        assertEquals(
            "https://dev.azure.com/org/proj/_git/repo?path=/src/A.java&version=GBmain" +
                "&line=12&lineEnd=12&lineStartColumn=1&lineEndColumn=1&lineStyle=plain&_a=contents",
            link
        )
    }

    @Test
    fun `仓库根链接与 PR 链接`() {
        assertEquals("https://github.com/o/r", RepositoryLinks.repoLink("git@github.com:o/r.git"))
        assertEquals(
            "https://github.com/o/r/compare/feat/a?expand=1",
            RepositoryLinks.pullRequestLink("git@github.com:o/r.git", "feat/a")
        )
        assertEquals(
            "https://gitlab.com/o/r/-/merge_requests/new?merge_request%5Bsource_branch%5D=dev",
            RepositoryLinks.pullRequestLink("https://gitlab.com/o/r.git", "dev")
        )
        assertNull(RepositoryLinks.pullRequestLink("https://git.unknown.com/o/r.git", "dev"))
    }

    @Test
    fun `未知平台且无模板时不生成链接`() {
        assertNull(RepositoryLinks.buildLink("https://git.unknown.com/o/r.git", "main", "a.java", 1, null))
        assertEquals(
            "https://git.unknown.com/o/r/blob/main/a.java#L1",
            RepositoryLinks.buildLink(
                "https://git.unknown.com/o/r.git",
                "main",
                "a.java",
                1,
                "{remoteUrl}/blob/{branch}/{path}#L{line}"
            )
        )
    }

    // ------------------------------------------------------------------ blame 文本编码

    @Test
    fun `解码出现替换符时判定为乱码`() {
        assertTrue(GitText.looksGarbled("fix: " + GitText.REPLACEMENT + " login"))
        assertFalse(GitText.looksGarbled("黄榕标 · 3 天前 · fix: 修复登录"))
        assertTrue(GitText.looksGarbled(listOf("正常", GitText.REPLACEMENT.toString())))
        assertFalse(GitText.looksGarbled(listOf("正常", "ok")))
    }

    @Test
    fun `兜底字符集覆盖 gbk 提交信息`() {
        // GBK 的「中文」字节按 UTF-8 解码会产生替换符，按 GB18030 解码应还原
        val gbkBytes = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        val asUtf8 = String(gbkBytes, Charsets.UTF_8)
        val asLegacy = String(gbkBytes, GitText.LEGACY_CHARSET)
        assertTrue(GitText.looksGarbled(asUtf8))
        assertEquals("中文", asLegacy)
    }

    // ------------------------------------------------------------------ blame 的 issue 高亮分段

    @Test
    fun `按区间把 blame 文本切成高亮段`() {
        val text = "Amy · 3 天前 · fix PROJ-12 login"
        val start = text.indexOf("PROJ-12")
        val segments = BlameRenderer.segments(text, listOf(start until (start + "PROJ-12".length)))
        assertEquals(text, segments.joinToString("") { it.text })
        assertEquals(1, segments.count { it.highlighted })
        assertEquals("PROJ-12", segments.first { it.highlighted }.text)
    }

    @Test
    fun `越界区间被忽略`() {
        val segments = BlameRenderer.segments("abc", listOf(5..8, -1..2))
        assertEquals(listOf(BlameRenderer.Segment("abc", false)), segments)
    }

    @Test
    fun `没有区间时只有一段普通文本`() {
        assertEquals(listOf(BlameRenderer.Segment("abc", false)), BlameRenderer.segments("abc", emptyList()))
    }

    // ------------------------------------------------------------------ 提交信息校验

    @Test
    fun `正则不匹配时给出提示`() {
        val problem = CommitMessageRules.check("随便写写", "^(feat|fix): .+", requireIssue = false, issueKeys = emptyList())
        assertTrue(problem != null && problem.contains("必须匹配正则"))
    }

    @Test
    fun `正则匹配且不要求 issue 时通过`() {
        assertNull(CommitMessageRules.check("fix: 修复登录", "^(feat|fix): .+", requireIssue = false, issueKeys = emptyList()))
    }

    @Test
    fun `要求 issue 引用但没识别到时给出提示`() {
        val problem = CommitMessageRules.check("fix: 修复登录", "", requireIssue = true, issueKeys = emptyList())
        assertTrue(problem != null && problem.contains("issue"))
        assertNull(CommitMessageRules.check("fix: PROJ-1 修复", "", requireIssue = true, issueKeys = listOf("PROJ-1")))
    }

    @Test
    fun `非法正则不阻塞提交但给出提示`() {
        val problem = CommitMessageRules.check("fix: x", "([", requireIssue = false, issueKeys = emptyList())
        assertTrue(problem != null && problem.contains("无效"))
    }
}
