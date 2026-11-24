package io.github.xbeeant.batiskit.support

/**
 * 复制到剪贴板前的敏感值脱敏。
 *
 * 「复制为 cURL」「复制还原后的 SQL」会把参数值原样带出去，一旦剪贴板内容被贴到
 * 工单、聊天群或日志里，密码 / token 就跟着泄露了。这里做一层轻量遮蔽：
 * 按**参数名**判断，命中常见敏感词就把值换成 [MASK]。
 *
 * 刻意不解析 SQL / JSON 语法：还原后的 SQL 与 cURL 都是一行文本，用 `key=value`、
 * `"key": "value"` 这类成对写法就足够覆盖，也不影响正常字段。
 */
object SensitiveData {

    const val MASK = "<redacted>"

    private val SENSITIVE_NAME = Regex(
        "(?i).*(password|passwd|pwd|secret|token|apikey|api_key|accesskey|access_key|" +
            "credential|authorization|cookie|sessionid|privatekey|private_key).*"
    )

    /** 形如 `password=abc`、`"token": "xyz"`、`X-Api-Key: 123` 的键值对 */
    private val PAIR = Regex(
        """(?i)("?)([A-Za-z_][A-Za-z0-9_-]*)("?\s*[:=](?![/=])\s*["']?)([^"',;&\s)]+)"""
    )

    fun isSensitive(name: String): Boolean = SENSITIVE_NAME.matches(name)

    fun mask(name: String, value: String): String = if (isSensitive(name)) MASK else value

    /** 整段文本脱敏：只替换敏感键后面的值，其余字符逐字保留 */
    fun maskText(text: String): String = PAIR.replace(text) { match ->
        val key = match.groupValues[2]
        if (!isSensitive(key)) {
            match.value
        } else {
            match.groupValues[1] + key + match.groupValues[3] + MASK
        }
    }
}
