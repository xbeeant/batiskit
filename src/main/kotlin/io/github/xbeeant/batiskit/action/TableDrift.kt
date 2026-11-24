package io.github.xbeeant.batiskit.action

/**
 * 表结构与已生成代码之间的漂移。
 *
 * 旧版只报「实体缺字段 / 类型不一致」，方向是单向的：表里删了列、DTO / XML 没跟上、
 * 或者新增了模板产物却没生成，全都看不见。现在按三类收拢，交给 [TableDriftDialog] 展示，
 * 用户勾选模板后有选择地重跑生成（仍然走变更预览 + 可撤销写入）。
 */
enum class DriftKind(val title: String) {
    MISSING_FIELD("缺字段"),
    TYPE_MISMATCH("类型不一致"),
    EXTRA_FIELD("实体多出字段"),
    MISSING_ARTIFACT("产物未生成")
}

data class DriftItem(
    val table: String,
    val kind: DriftKind,
    val detail: String
) {
    val message: String get() = "【$table】${kind.title}：$detail"
}

class DriftReport {
    val items: MutableList<DriftItem> = mutableListOf()
    val notes: MutableList<String> = mutableListOf()

    val isClean: Boolean get() = items.isEmpty()

    fun summary(): String = if (isClean) {
        "未发现漂移"
    } else {
        buildString {
            DriftKind.entries.forEach { kind ->
                val n = items.count { it.kind == kind }
                if (n > 0) append("${kind.title} $n  ")
            }
        }.trim()
    }
}

/** 实体产物当前能不能做字段比对 */
enum class EntityState {
    /** 文件确实不存在：唯一可以报「产物未生成」的情形 */
    MISSING,

    /** 文件在磁盘上，但 PSI / 类索引暂时读不到：只跳过字段比对，绝不误报未生成 */
    UNREADABLE,
    READY
}

/**
 * 比对判定里不依赖 IDE 的那部分，单独拎出来便于回归。
 *
 * 之前的缺陷正是把「PSI 找不到类」等同于「没生成过代码」：源码根没标记、索引没建好、
 * 刚生成还没刷新、或者模块猜错，`JavaPsiFacade.findClass` 都会返回 null，而文件其实好好地躺在磁盘上。
 * 现在存在性只认渲染产物定位到的 VirtualFile。
 */
object DriftRules {

    /** java 根下的相对路径 → 全限定类名；resources 根没有包 */
    fun fqnOf(root: String, relativePath: String): String? {
        if (root != "java") return null
        return relativePath
            .removeSuffix(".java")
            .replace('/', '.')
            .trim('.')
            .takeIf { it.isNotBlank() }
    }

    /** 实体产物 = java 根 + 文件名恰好是 `<实体名>.java`（不能用 endsWith，会误伤 `XUser.java`） */
    fun isEntity(root: String, relativePath: String, entityName: String): Boolean =
        root == "java" && relativePath.substringAfterLast('/') == "$entityName.java"

    /** 解析配置里的「方案:模板id」排除清单 */
    fun disabledKeys(raw: String): Set<String> = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun entityState(exists: Boolean, classResolved: Boolean): EntityState = when {
        !exists -> EntityState.MISSING
        !classResolved -> EntityState.UNREADABLE
        else -> EntityState.READY
    }
}
