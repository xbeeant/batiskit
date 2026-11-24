package io.github.xbeeant.batiskit.sequence

import io.github.xbeeant.batiskit.sequence.BasicClassRules

/**
 * 调用链分析共用的纯逻辑：噪声过滤、基础类过滤、标签 / 名称截断、身份键。
 * 零平台依赖，UAST 分析器与测试都直接用它；Java PSI 分析器保留自己等价的
 * 噪声 / 标签实现以免改动既有行为，但基础类判定共用这里，避免两套名单走偏。
 *
 * 基础类名单不再写死在这里：出厂默认值与实际名单都在 [BasicClassRules]，
 * 由设置页（Settings → Tools → Batis Kit → 时序图）通过 [DiagramOptions.basicClassRules] 注入。
 */
object SequenceRules {

    const val MAX_LABEL = 60
    const val MAX_DISPLAY = 28

    private val LOG_METHODS = setOf("debug", "info", "warn", "error", "trace", "log")
    private val OBJECT_METHODS = setOf("toString", "hashCode", "equals", "getClass")

    /** getter / setter / toString / 日志这类噪声调用 */
    fun isTrivial(name: String, argCount: Int, receiverText: String = ""): Boolean {
        if (name in OBJECT_METHODS) return true
        if ((name.startsWith("get") || name.startsWith("is")) && name.length > 3 && argCount == 0) return true
        if (name.startsWith("set") && name.length > 3 && argCount == 1) return true
        if (name in LOG_METHODS) {
            val receiver = receiverText.lowercase()
            if ("log" in receiver || "logger" in receiver) return true
        }
        return false
    }

    /**
     * 是否为基础类 / 工具类：这类调用画进时序图只会淹没有意义的业务调用。
     * 命中时连箭头、生命线、下钻与返回箭头都不生成——与「只展开本工程代码」不同，
     * 后者只是不下钻，仍会留一根箭头和一条生命线。
     *
     * @param qualified 全限定名（可能为 null，或解析退化成只有简单名）；`anonymous` 一律不跳过
     * @param simple    简单类名，仅当 [qualified] 为空时作为兜底依据
     * @param rules     生效的基础类名单；不传时用出厂默认值（旧调用与纯 JVM 测试保持原行为）
     */
    fun isBasicClass(
        qualified: String?,
        simple: String? = null,
        rules: BasicClassRules = BasicClassRules.DEFAULT,
    ): Boolean = rules.isBasic(qualified, simple)

    /**
     * 完整调用标签：只把参数里的换行压成空格，**不截断**。
     * 文本导出（PlantUML / Mermaid）用它，长字符串字面量才不会被 `…` 吃掉。
     */
    fun fullLabel(name: String, args: List<String>): String {
        val joined = args.joinToString(", ") { it.replace("\r", " ").replace("\n", " ").trim() }
        return if (joined.isEmpty()) "$name()" else "$name($joined)"
    }

    /** 图形页签的短标签：只有布局层需要省略，模型里同时留着 [fullLabel] */
    fun label(name: String, args: List<String>): String = abbreviate(fullLabel(name, args))

    fun abbreviate(text: String): String =
        if (text.length > MAX_LABEL) text.take(MAX_LABEL - 1) + "…" else text

    fun displayOf(name: String?): String {
        val value = name ?: return "anonymous"
        return if (value.length > MAX_DISPLAY) value.take(MAX_DISPLAY - 1) + "…" else value
    }

    fun qualifiedOf(qualified: String?, simple: String?): String = qualified ?: simple ?: "anonymous"

    fun methodKey(qualified: String, name: String, paramCount: Int): String = "$qualified.$name($paramCount)"
}
