package io.github.xbeeant.batiskit.git

import io.github.xbeeant.batiskit.git.blame.BlameBlockRenderer
import io.github.xbeeant.batiskit.git.blame.BlameRenderer
import io.github.xbeeant.batiskit.git.blame.GitBlameService
import io.github.xbeeant.batiskit.git.link.RepositoryLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git Toolbox 纯逻辑测试：远程链接构造、blame porcelain 解析。
 */
class GitToolboxTest {

    // ------------------------------------------------------------------ 远程链接

    @Test
    fun `github ssh 地址构造 blob 链接`() {
        val link = RepositoryLinks.buildLink(
            remoteUrl = "git@github.com:xbeeant/demo.git",
            branch = "master",
            path = "src/main/java/A.java",
            line = 42,
            template = null
        )
        assertEquals("https://github.com/xbeeant/demo/blob/master/src/main/java/A.java#L42", link)
    }

    @Test
    fun `gitlab 与 gitee 与 github 同构`() {
        val gitlab = RepositoryLinks.buildLink("https://gitlab.com/g/p.git", "dev", "a.kt", 3, null)
        assertEquals("https://gitlab.com/g/p/blob/dev/a.kt#L3", gitlab)
        val gitee = RepositoryLinks.buildLink("https://gitee.com/g/p.git", "dev", "a.kt", null, null)
        assertEquals("https://gitee.com/g/p/blob/dev/a.kt", gitee)
    }

    @Test
    fun `bitbucket 使用 src 与 lines 锚点`() {
        val link = RepositoryLinks.buildLink(
            "https://bitbucket.org/team/proj.git",
            "main",
            "b.py",
            7,
            null
        )
        assertEquals("https://bitbucket.org/team/proj/src/main/b.py#lines-7", link)
    }

    @Test
    fun `自定义模板优先`() {
        val link = RepositoryLinks.buildLink(
            "https://git.example.com/team/proj.git",
            "main",
            "a/b.java",
            9,
            "{remoteUrl}/files/{path}({line})"
        )
        assertEquals("https://git.example.com/team/proj/files/a/b.java(9)", link)
    }

    @Test
    fun `未知平台且无模板返回 null`() {
        assertNull(RepositoryLinks.buildLink("https://git.unknown.com/t/p.git", "main", "a", 1, null))
    }

    // ------------------------------------------------------------------ blame 解析

    private fun porcelain(author: String, time: Long, summary: String, sha: String = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b") =
        listOf(
            "$sha 12 12 1",
            "author $author",
            "author-mail <a@b.c>",
            "author-time $time",
            "author-tz +0800",
            "committer $author",
            "committer-time $time",
            "summary $summary",
            ""
        )

    @Test
    fun `解析已提交行`() {
        val now = 1_800_000_000_000L
        val info = GitBlameService.parsePorcelain(
            porcelain("Amy", now / 1000 - 86400 * 3, "fix: 修复登录"),
            now
        )!!
        assertEquals("Amy", info.author)
        assertEquals("fix: 修复登录", info.subject)
        assertEquals("3 天前", info.whenText)
        assertFalse(info.uncommitted)
    }

    @Test
    fun `解析未提交行`() {
        val now = 1_800_000_000_000L
        val info = GitBlameService.parsePorcelain(
            porcelain("Not Committed Yet", now / 1000, "Not Committed Yet", sha = "0".repeat(40)),
            now
        )!!
        assertTrue(info.uncommitted)
        assertEquals("未提交", info.whenText)
    }

    @Test
    fun `空输出返回 null`() {
        assertNull(GitBlameService.parsePorcelain(emptyList(), 0L))
    }

    // ------------------------------------------------------------------ 整文件 blame 解析（性能优化：一次调用覆盖所有行）

    private val shaA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val shaB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private fun fileBlame(vararg blocks: List<String>): List<String> {
        val out = mutableListOf<String>()
        blocks.forEach { out += it }
        return out
    }

    private fun block(
        sha: String,
        finalLine: Int,
        numLines: Int,
        author: String,
        time: Long,
        summary: String
    ): List<String> {
        val header = if (numLines == 1) {
            "$sha 1 $finalLine"
        } else {
            "$sha 1 $finalLine $numLines"
        }
        val out = mutableListOf(header)
        out += "author $author"
        out += "author-mail <a@b.c>"
        out += "author-time $time"
        out += "author-tz +0800"
        out += "committer $author"
        out += "committer-time $time"
        out += "summary $summary"
        for (i in 0 until numLines) {
            out += "\t第 ${finalLine + i} 行内容"
        }
        return out
    }

    @Test
    fun `整文件 porcelain 按行号区间映射`() {
        val now = 1_800_000_000_000L
        val byLine = GitBlameService.parseFullPorcelain(
            fileBlame(
                block(shaA, 1, 2, "Amy", now / 1000 - 86400, "fix: 一"),
                block(shaB, 3, 1, "Bob", now / 1000 - 30, "fix: 二"),
                block("0".repeat(40), 4, 1, "Not Committed Yet", now / 1000, "Not Committed Yet")
            ),
            now
        )
        assertEquals(4, byLine.size)
        assertEquals("Amy", byLine[1]!!.author)
        assertEquals("Amy", byLine[2]!!.author)
        assertEquals("Bob", byLine[3]!!.author)
        assertEquals("刚刚", byLine[3]!!.whenText)
        assertTrue(byLine[4]!!.uncommitted)
        assertEquals("fix: 一", byLine[2]!!.subject)
    }

    @Test
    fun `整文件 porcelain 跳过 previous 与 boundary 行`() {
        val now = 1_800_000_000_000L
        val lines = mutableListOf<String>()
        lines += "$shaA 1 1"
        lines += "author Amy"
        lines += "author-time ${now / 1000 - 3600}"
        lines += "summary fix: 边界"
        lines += "previous $shaB src/A.java"
        lines += "boundary"
        lines += "\t内容"
        val byLine = GitBlameService.parseFullPorcelain(lines, now)
        assertEquals("Amy", byLine[1]!!.author)
    }

    @Test
    fun `批量 show 输出按 40 位 sha 行切分`() {
        val parsed = GitBlameService.parseShowBatch(
            listOf(
                shaA,
                "fix: 标题甲",
                "",
                "正文一段",
                shaB,
                "fix: 标题乙"
            )
        )
        assertEquals(2, parsed.size)
        assertEquals("fix: 标题甲\n\n正文一段", parsed[shaA])
        assertEquals("fix: 标题乙", parsed[shaB])
    }

    // ------------------------------------------------------------------ 行内 blame 文案（换行显示开关）

    @Test
    fun `不换行时多行提交信息也把首行摘要放进行内`() {
        val info = io.github.xbeeant.batiskit.git.blame.BlameInfo(
            sha = shaA,
            author = "Amy",
            subject = "fix: 标题",
            whenText = "3 天前",
            uncommitted = false,
            messageLines = listOf("fix: 标题", "正文")
        )
        val text = io.github.xbeeant.batiskit.git.blame.BlameInlayManager.inlineTextOf(info, useBlock = false)
        assertEquals(" Amy · 3 天前 · fix: 标题", text)
    }

    @Test
    fun `换行展开时行内不重复标题`() {
        val info = io.github.xbeeant.batiskit.git.blame.BlameInfo(
            sha = shaA,
            author = "Amy",
            subject = "fix: 标题",
            whenText = "3 天前",
            uncommitted = false,
            messageLines = listOf("fix: 标题", "正文")
        )
        val text = io.github.xbeeant.batiskit.git.blame.BlameInlayManager.inlineTextOf(info, useBlock = true)
        assertEquals(" Amy · 3 天前", text)
    }

    @Test
    fun `未提交行始终不显示摘要`() {
        val info = io.github.xbeeant.batiskit.git.blame.BlameInfo(
            sha = "0".repeat(40),
            author = "you",
            subject = "未提交",
            whenText = "未提交",
            uncommitted = true
        )
        assertEquals(" you · 未提交", io.github.xbeeant.batiskit.git.blame.BlameInlayManager.inlineTextOf(info, useBlock = false))
    }

    // ------------------------------------------------------------------ blame 显示截断

    @Test
    fun `过长 blame 文本按宽度截断加省略号`() {
        val text = "Amy · 3 天前 · " + "这是一个非常长的提交信息".repeat(10)
        val fitted = BlameRenderer.ellipsize(text, { it.length }, 40)
        assertEquals(40, fitted.length)
        assertTrue(fitted.endsWith("…"))
        assertTrue(text.startsWith(fitted.removeSuffix("…")))
    }

    @Test
    fun `宽度足够的文本不截断`() {
        val text = "Amy · 3 天前 · fix: 修复登录"
        assertEquals(text, BlameRenderer.ellipsize(text, { it.length }, 100))
    }

    @Test
    fun `恰好放下的文本不加省略号`() {
        assertEquals("12345", BlameRenderer.ellipsize("12345", { it.length }, 5))
    }

    @Test
    fun `宽度极小时至少显示省略号`() {
        assertEquals("…", BlameRenderer.ellipsize("anything", { it.length }, 0))
        assertEquals("…", BlameRenderer.ellipsize("anything", { it.length }, -1))
    }

    // ------------------------------------------------------------------ 折行 commit message 的 subject 重组

    @Test
    fun `标题折行时按 git 规则用空格连接`() {
        val subject = GitBlameService.subjectOf(listOf("标题甲：第一段", "第二段", "", "正文"))
        assertEquals("标题甲：第一段 第二段", subject)
    }

    @Test
    fun `以空行开头的消息跳过空行取标题`() {
        val subject = GitBlameService.subjectOf(listOf("", "", "  实际标题  ", "", "正文"))
        assertEquals("实际标题", subject)
    }

    @Test
    fun `单行消息原样返回`() {
        assertEquals("fix: 修复登录", GitBlameService.subjectOf(listOf("fix: 修复登录")))
    }

    @Test
    fun `全空消息返回空串`() {
        assertEquals("", GitBlameService.subjectOf(listOf("", " ")))
    }

    // ------------------------------------------------------------------ 多行消息的块状显示

    @Test
    fun `行数未超限时逐行保留`() {
        val lines = listOf("标题", "", "正文一", "正文二")
        assertEquals(lines, BlameBlockRenderer.displayLinesOf(lines, 10))
    }

    @Test
    fun `行数超限时截取并追加省略行`() {
        val lines = (1..15).map { "第 $it 行" }
        val display = BlameBlockRenderer.displayLinesOf(lines, 10)
        assertEquals(11, display.size)
        assertEquals("第 10 行", display[9])
        assertEquals("…", display[10])
    }

    // ------------------------------------------------------------------ 过长行自动换行

    @Test
    fun `中文长行按字符断行且每段不超宽`() {
        val text = "这是一个很长的提交信息".repeat(6) // 66 字
        val segments = BlameBlockRenderer.wrap(text, { it.length }, 20)
        assertTrue(segments.size > 1)
        assertTrue(segments.dropLast(1).all { it.length <= 20 })
        assertTrue(segments.all { it.isNotEmpty() })
        // 内容无丢失（断行处无空格）
        assertEquals(text, segments.joinToString(""))
    }

    @Test
    fun `英文长行优先在空格处断行`() {
        val text = "fix login issue and update session token handling logic"
        val segments = BlameBlockRenderer.wrap(text, { it.length }, 25)
        assertTrue(segments.size > 1)
        // 每段不超宽
        assertTrue(segments.dropLast(1).all { it.length <= 25 })
        // 单词未被截断：按空格拼回并规范空白后与原文一致
        assertEquals(text, segments.joinToString(" ").replace(Regex(" +"), " "))
    }

    @Test
    fun `短行不折行`() {
        assertEquals(listOf("fix: 修复登录"), BlameBlockRenderer.wrap("fix: 修复登录", { it.length }, 50))
        assertEquals(listOf(""), BlameBlockRenderer.wrap("", { it.length }, 50))
    }

    @Test
    fun `超长单词强制按字符断行`() {
        val segments = BlameBlockRenderer.wrap("abcdefghijklmnopqrstuvwxyz", { it.length }, 10)
        assertTrue(segments.size >= 3)
        assertTrue(segments.dropLast(1).all { it.length <= 10 })
        assertEquals("abcdefghijklmnopqrstuvwxyz", segments.joinToString(""))
    }

    // ------------------------------------------------------------------ 仓库相对路径（blame 的前置步骤）

    @Test
    fun `unix 风格路径取仓库内相对路径`() {
        assertEquals(
            "src/main/java/A.java",
            GitPaths.relativePath("/work/demo", "/work/demo/src/main/java/A.java")
        )
    }

    @Test
    fun `windows 反斜杠路径也能取到相对路径`() {
        // 旧实现按 '/' 切分，Windows 上切不开导致 blame 恒返回 null
        assertEquals(
            "src/main/java/A.java",
            GitPaths.relativePath("E:\\tmp\\demo", "E:\\tmp\\demo\\src\\main\\java\\A.java")
        )
    }

    @Test
    fun `混用分隔符与多余斜杠`() {
        assertEquals(
            "src/main/java/A.java",
            GitPaths.relativePath("E:/tmp/demo/", "E:\\tmp\\demo\\src\\main\\java\\A.java")
        )
        assertEquals(
            "a.kt",
            GitPaths.relativePath("/work/demo/", "/work//demo/a.kt")
        )
    }

    @Test
    fun `文件不在仓库内返回 null`() {
        assertNull(GitPaths.relativePath("/work/demo", "/work/other/a.java"))
        // 前缀相同但目录名不同，不能被误判为仓库内文件
        assertNull(GitPaths.relativePath("/work/demo", "/work/demo2/a.java"))
    }

    @Test
    fun `仓库根目录本身与空路径返回 null`() {
        assertNull(GitPaths.relativePath("/work/demo", "/work/demo"))
        assertNull(GitPaths.relativePath("/work/demo", "/work/demo/"))
        assertNull(GitPaths.relativePath("", "/work/demo/a.java"))
    }

    @Test
    fun `嵌套仓库按最长根目录匹配`() {
        assertEquals("a.java", GitPaths.relativePath("/work/demo/sub", "/work/demo/sub/a.java"))
    }
}
