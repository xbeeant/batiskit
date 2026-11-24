package io.github.xbeeant.batiskit.git

import java.nio.charset.Charset

/**
 * Git 输出的文本编码处理。
 *
 * git4idea 统一按 UTF-8 解码命令输出，遇到老仓库（提交信息是 GBK / GB18030，
 * 或 `i18n.logOutputEncoding` 被配成 GBK）就会解出「乱码」——非法字节被替换成 U+FFFD。
 * 这里以替换符作为判据：命中就用 GB18030 重跑一次命令再取文本。
 */
object GitText {

    /** UTF-8 解码失败时 Java 产生的替换符 */
    const val REPLACEMENT: Char = '\uFFFD'

    /** 老仓库的兜底字符集（GB18030 覆盖 GBK / GB2312） */
    val LEGACY_CHARSET: Charset =
        runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)

    fun looksGarbled(text: String): Boolean = text.indexOf(REPLACEMENT) >= 0

    fun looksGarbled(values: Iterable<String>): Boolean = values.any { looksGarbled(it) }
}
