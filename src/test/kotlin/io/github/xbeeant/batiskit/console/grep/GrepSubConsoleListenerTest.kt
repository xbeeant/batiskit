package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xbeeant.batiskit.console.FilterQuery
import io.github.xbeeant.batiskit.console.LogLineParser

/** Grep 子控制台转发监听器的纯逻辑测试：攒行 / 匹配 / 保留 contentType / query 即时生效 */
class GrepSubConsoleListenerTest : BasePlatformTestCase() {

    private lateinit var printed: MutableList<Pair<String, ConsoleViewContentType>>

    private fun newListener(): GrepSubConsoleListener {
        printed = mutableListOf()
        return GrepSubConsoleListener { text, type -> printed.add(text to type) }
    }

    fun `test 完整行命中才打印 半行先缓冲`() {
        val listener = newListener()
        listener.query = FilterQuery("boom")

        listener.process("[ERROR] boom\n", ConsoleViewContentType.ERROR_OUTPUT)
        assertEquals(1, printed.size)
        assertEquals("[ERROR] boom\n", printed[0].first)
        assertEquals(ConsoleViewContentType.ERROR_OUTPUT, printed[0].second)

        // 半行先缓冲，直到出现换行才作为一个整体匹配；打印与原版一致：逐段保留各自 contentType
        listener.process("[INFO] half ", ConsoleViewContentType.NORMAL_OUTPUT)
        listener.process("boom line\n", ConsoleViewContentType.NORMAL_OUTPUT)
        assertEquals(3, printed.size)
        assertEquals("[INFO] half ", printed[1].first)
        assertEquals("boom line\n", printed[2].first)
        assertEquals(ConsoleViewContentType.NORMAL_OUTPUT, printed[2].second)
    }

    fun `test 未命中不打印 一次多行只打印命中的行`() {
        val listener = newListener()
        listener.query = FilterQuery("boom")

        listener.process("[INFO] a\n[ERROR] boom\n[INFO] c\n", ConsoleViewContentType.NORMAL_OUTPUT)
        assertEquals(1, printed.size)
        assertEquals("[ERROR] boom\n", printed[0].first)
    }

    fun `test query 更新立即生效`() {
        val listener = newListener()
        listener.query = FilterQuery("boom")
        listener.process("[ERROR] boom\n", ConsoleViewContentType.ERROR_OUTPUT)
        assertEquals(1, printed.size)

        listener.query = FilterQuery("timeout")
        listener.process("[ERROR] boom\n", ConsoleViewContentType.ERROR_OUTPUT)
        assertEquals(1, printed.size)
        listener.process("[INFO] request timeout\n", ConsoleViewContentType.NORMAL_OUTPUT)
        assertEquals(2, printed.size)
    }

    fun `test contentTypeOf 按级别映射回放样式`() {
        val parser = LogLineParser()
        assertEquals(
            ConsoleViewContentType.ERROR_OUTPUT,
            GrepSubConsoleListener.contentTypeOf(parser, "2026-11-05 22:33:34 ERROR 1 --- [main] c.e.Demo : boom\n"),
        )
        assertEquals(
            ConsoleViewContentType.LOG_INFO_OUTPUT,
            GrepSubConsoleListener.contentTypeOf(parser, "2026-11-05 22:33:34  INFO 1 --- [main] c.e.Demo : ok\n"),
        )
    }
}
