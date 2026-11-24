package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import io.github.xbeeant.batiskit.console.LogLineParser

/**
 * 对标原版 `GrepUtils.grepThroughExistingText`：把父控制台**已有**的文本按行喂给监听器，
 * 让新开的 Grep 子控制台立刻能看到之前打印过的日志（Reload 也走这里）。
 *
 * 原版通过 markup 区段还原每行的 contentType；我们用自家级别解析器按行推断，
 * 错误行仍红、警告行仍黄。动作在 EDT 上执行，与控制台打印天然互斥，无需原版的加锁处理。
 */
object GrepReplay {

    fun grepThroughExistingText(parent: ConsoleView?, listener: GrepSubConsoleListener) {
        if (parent == null || listener.query == null) {
            return
        }
        val impl = parent as? ConsoleViewImpl ?: return
        impl.flushDeferredText()
        val editor = impl.editor ?: return
        val text = editor.document.text
        if (text.isEmpty()) {
            return
        }
        val parser = LogLineParser()
        val line = StringBuilder()
        for (ch in text) {
            line.append(ch)
            if (ch == '\n') {
                listener.process(line.toString(), GrepSubConsoleListener.contentTypeOf(parser, line.toString()))
                line.setLength(0)
            }
        }
        if (line.isNotEmpty()) {
            listener.process(line.toString() + "\n", GrepSubConsoleListener.contentTypeOf(parser, line.toString()))
        }
    }
}
