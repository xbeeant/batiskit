package io.github.xbeeant.batiskit.log

import com.intellij.DynamicBundle
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * MyBatis Log 模块的文案入口。
 *
 * 基础 properties 是中文，`_en` 是英文；语言跟随 IDE 的界面语言（[DynamicBundle.getLocale]），
 * 而不是 JVM 默认 Locale —— 中文系统上装英文 IDE 时也能拿到英文文案。
 * 这里刻意不抛异常：缺 key、资源包没打进产物、IDE 尚未初始化时都退化成 key 本身，
 * 保证设置页 / 工具窗口不会因为一句文案加载失败而整页空白。
 */
object MyBatisLogBundle {

    private const val BUNDLE_NAME = "messages.MyBatisLogBundle"

    private val locale: Locale
        get() = runCatching { DynamicBundle.getLocale() }.getOrDefault(Locale.getDefault())

    private val bundle: ResourceBundle?
        get() = runCatching {
            ResourceBundle.getBundle(BUNDLE_NAME, locale, MyBatisLogBundle::class.java.classLoader)
        }.getOrNull()

    fun message(key: String, vararg params: Any): String {
        val raw = runCatching { bundle?.getString(key) }.getOrNull() ?: return key
        if (params.isEmpty()) return raw
        return runCatching { MessageFormat.format(raw, *params) }.getOrDefault(raw)
    }
}
