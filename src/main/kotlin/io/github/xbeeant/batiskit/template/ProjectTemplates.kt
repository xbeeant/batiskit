package io.github.xbeeant.batiskit.template

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目级模板：项目根下 `.batiskit/templates/` 里，每个方案文件夹放自己的 `.tpl`。
 *
 * 与内置模板同构（目录即方案），但**只能新增方案**：方案名与内置 / 全局已有方案重名时整个目录跳过，
 * 由 [scan] 的 `errors` 说明原因 —— 项目模板不允许悄悄改掉别人那套方案的产物清单。
 *
 * 存在项目里而不是 IDE 设置里，是为了能进版本库、跟团队共享：克隆下来就能用，不必每人配一遍。
 *
 * ## 为什么用「文件指纹」缓存
 *
 * 模板仓库在生成链路与配置面板里被反复调用（设置页的 `isModified()` 更是高频），
 * 每次都把所有 `.tpl` 读一遍再解析头部纯属浪费。这里每次只 `stat` 一遍文件
 * （名字 + 大小 + 修改时间），指纹没变就复用上次的解析结果，改了文件立刻能感知 ——
 * 既不脏读，也不用用户手动点「刷新」。
 */
object ProjectTemplates {

    /** 相对项目根的模板目录 */
    const val DIR = ".batiskit/templates"

    /** 一次扫描的结果：方案 → 模板；`errors` 是「哪个文件 / 目录不能用，为什么」 */
    class Scan(
        val sets: Map<String, List<TemplateSpec>> = emptyMap(),
        val errors: Map<String, String> = emptyMap()
    ) {
        /** 模板总数（用于配置面板上的一行提示） */
        val count: Int get() = sets.values.sumOf { it.size }
    }

    private val EMPTY = Scan()

    private class Entry(val fingerprint: List<String>, val scan: Scan)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 项目模板目录（不保证存在） */
    fun dirOf(basePath: String): File = File(basePath, DIR)

    /**
     * 扫描项目模板。
     *
     * @param basePath 项目根目录；`null` / 空 / 目录不存在都返回空结果（不是错误）
     */
    fun scan(basePath: String?): Scan {
        if (basePath.isNullOrBlank()) {
            return EMPTY
        }
        val root = dirOf(basePath)
        if (!root.isDirectory) {
            cache.remove(basePath)
            return EMPTY
        }
        val files = tplFiles(root)
        val fingerprint = files.map { "${it.path}\u0000${it.length()}\u0000${it.lastModified()}" }
        val cached = cache[basePath]
        if (cached != null && cached.fingerprint == fingerprint) {
            return cached.scan
        }
        return parse(files).also { cache[basePath] = Entry(fingerprint, it) }
    }

    /** 丢弃缓存（全部或某个项目）：模板在 IDE 之外被替换、mtime 又恰好没变时兜底用 */
    fun invalidate(basePath: String? = null) {
        if (basePath == null) cache.clear() else cache.remove(basePath)
    }

    /** 只取「方案目录下的一层 `.tpl`」：方案目录本身是文件夹，隐藏目录跳过（`.git` 之类） */
    private fun tplFiles(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .flatMap { dir ->
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "tpl" }
                    .sortedBy { it.name }
            }

    private fun parse(files: List<File>): Scan {
        val errors = LinkedHashMap<String, String>()
        val sets = LinkedHashMap<String, MutableList<TemplateSpec>>()
        // 方案 → 已用过的 id：同方案里两份同 id 的模板，后一份会被静默覆盖，不如直接报出来
        val usedIds = HashMap<String, MutableSet<String>>()

        files.forEach { file ->
            val setDir = file.parentFile.name
            val path = "$DIR/$setDir/${file.name}"
            val text = try {
                file.readText()
            } catch (e: Exception) {
                errors[path] = "读取失败：${e.message}"
                return@forEach
            }
            val parsed = TemplateSpec.parse(text, path, builtin = false)
            if (parsed.error != null) {
                errors[path] = parsed.error
                return@forEach
            }
            val seen = usedIds.getOrPut(setDir) { mutableSetOf() }
            if (!seen.add(parsed.spec.id)) {
                errors[path] = "方案 $setDir 里已有 id 为 ${parsed.spec.id} 的模板，这一份被忽略"
                return@forEach
            }
            sets.getOrPut(setDir) { mutableListOf() } += parsed.spec.copy(set = setDir)
        }

        return Scan(
            sets.mapValues { (_, specs) -> specs.sortedWith(compareBy({ it.order }, { it.id })) },
            errors
        )
    }
}
