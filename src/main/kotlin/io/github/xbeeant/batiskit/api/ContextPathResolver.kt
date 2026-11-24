package io.github.xbeeant.batiskit.api

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * 工程侧探测器：把 [ContextPathRules] 的纯逻辑接到 IntelliJ VFS / 文件索引上。
 *
 * 只扫 `application*.properties|yml|yaml` / `bootstrap*` 与构建脚本，文件名走 `FilenameIndex`，
 * 不遍历全工程源码；整个扫描在「智能模式 + 读锁」里做，调用方应放在后台线程。
 *
 * 远端配置（Nacos / Spring Cloud Config）静态读不到，因此提供工程级手动覆盖
 * （`PropertiesComponent`，与时序图选项同一套记忆方式），覆盖值就是完整接口地址前缀。
 */
object ContextPathResolver {

    const val PREF_BASE_URL = "spring.mybatis.api.base-url"

    private val CONFIG_NAME = Regex(
        """^(application|bootstrap)(-[A-Za-z0-9._-]+)?\.(properties|ya?ml)$""",
        RegexOption.IGNORE_CASE
    )
    private val BUILD_NAME = Regex("""^(pom\.xml|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties)$""")
    private val GENERATED_DIRS = listOf("/build/", "/target/", "/out/", "/.gradle/", "/node_modules/")
    private const val MAX_FILES = 200
    private const val MAX_BYTES = 512 * 1024L

    /** 面板实际拿来拼 URL 的地址信息 */
    data class Address(
        /** 完整前缀：主机 + context path，已去掉尾斜杠 */
        val baseUrl: String = ContextPathRules.DEFAULT_BASE_URL,
        /** 不含 context path 的主机部分 */
        val host: String = ContextPathRules.DEFAULT_BASE_URL,
        val contextPath: String = "",
        /** 来源说明（自动探测到的文件 / 手动设置） */
        val source: String = "正在探测接口地址…",
        /** 需要用户确认的提示 */
        val note: String? = null,
        val candidates: List<String> = emptyList(),
        val manual: Boolean = false,
        val nacos: Boolean = false
    )

    fun override(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(PREF_BASE_URL)

    /** 传空白 = 恢复自动探测 */
    fun setOverride(project: Project, raw: String?) {
        val properties = PropertiesComponent.getInstance(project)
        if (raw.isNullOrBlank()) {
            properties.unsetValue(PREF_BASE_URL)
        } else {
            properties.setValue(PREF_BASE_URL, ContextPathRules.normalizeBaseUrl(raw))
        }
    }

    fun clearOverride(project: Project) = setOverride(project, null)

    /**
     * 静态探测。索引未就绪时会等到 smart mode（不要在 EDT 上调用，避免卡 UI），
     * 单个文件读失败只跳过该文件，不影响整体结果。
     */
    fun detect(project: Project): ContextPathRules.Detection =
        DumbService.getInstance(project).runReadActionInSmartMode(
            Computable<ContextPathRules.Detection> { scan(project) }
        )

    private fun scan(project: Project): ContextPathRules.Detection {
        val scope = GlobalSearchScope.projectScope(project)
        val names = FilenameIndex.getAllFilenames(project)
            .filter { CONFIG_NAME.matches(it) || BUILD_NAME.matches(it) }
            .sorted()
            .take(MAX_FILES)

        val configs = mutableListOf<ContextPathRules.ConfigFile>()
        val buildTexts = mutableListOf<String>()
        names.forEach { name ->
            val files = runCatching { FilenameIndex.getVirtualFilesByName(name, scope) }
                .getOrNull()
                .orEmpty()
            files.forEach { file ->
                if (!usable(file)) return@forEach
                val text = runCatching { readText(file) }.getOrNull() ?: return@forEach
                if (BUILD_NAME.matches(name)) {
                    buildTexts += text
                } else {
                    configs += ContextPathRules.ConfigFile(name, text, file.path)
                }
            }
        }
        return ContextPathRules.resolve(configs, buildTexts)
    }

    private fun usable(file: VirtualFile): Boolean =
        !file.isDirectory && file.length > 0 && file.length <= MAX_BYTES &&
            GENERATED_DIRS.none { file.path.contains(it) }

    private fun readText(file: VirtualFile): String = String(file.contentsToByteArray(), Charsets.UTF_8)

    /** 手动覆盖优先；否则用默认主机 `http://localhost:8080` + 探测到的 context path */
    fun address(
        project: Project,
        detection: ContextPathRules.Detection,
        manualOverride: String? = override(project)
    ): Address {
        if (!manualOverride.isNullOrBlank()) {
            val baseUrl = ContextPathRules.normalizeBaseUrl(manualOverride)
            val (host, contextPath) = ContextPathRules.splitBaseUrl(baseUrl)
            return Address(baseUrl, host, contextPath, "手动设置（工程级记忆）", null, emptyList(), true, false)
        }
        val host = ContextPathRules.normalizeBaseUrl(ContextPathRules.DEFAULT_BASE_URL)
        val baseUrl = ContextPathRules.compose(host, detection.contextPath, "")
        return Address(
            baseUrl = baseUrl,
            host = host,
            contextPath = detection.contextPath,
            source = detection.source,
            note = detection.note,
            candidates = detection.candidates,
            manual = false,
            nacos = detection.nacos
        )
    }
}
