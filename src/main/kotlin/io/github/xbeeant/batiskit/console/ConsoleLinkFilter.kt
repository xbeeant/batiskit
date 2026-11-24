package io.github.xbeeant.batiskit.console

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.xbeeant.batiskit.settings.ModuleToggles
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path

/**
 * 控制台自动链接 / 快速导航：把 [LogLinkScanner] 识别出的引用变成可点击链接。
 *
 * - Java 类 / 方法引用 → 在全工程（含依赖库）里找 PSI 符号跳转；
 * - 项目相对路径 `src/main/kotlin/.../Foo.kt:42` → 打开文件并定位到行。
 *
 * 平台自带的过滤器已负责 URL、绝对路径与 `at …` 堆栈行，这里只补平台不认的引用，
 * 所以不会与平台的链接互相覆盖。可以在设置里关掉。
 */
class ConsoleLinkFilter : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val settings = GrepConsoleSettings.getInstance().config
        if (!settings.enabled || !settings.linkNavigation) return null
        val links = LogLinkScanner.scan(line)
        if (links.isEmpty()) return null
        val items = links.mapNotNull { link ->
            val hyperlink = when (link.kind) {
                // URL 由平台自带的过滤器处理，这里不再重复生成
                LogLinkScanner.Kind.URL -> null
                LogLinkScanner.Kind.JAVA_CLASS -> navigateToClass(link.text)
                LogLinkScanner.Kind.JAVA_METHOD -> navigateToMember(link.text)
                LogLinkScanner.Kind.FILE_PATH -> navigateToFile(link.text)
            } ?: return@mapNotNull null
            // 链接区间不带颜色属性，避免盖住高亮
            Filter.ResultItem(link.start, link.end, hyperlink)
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun navigateToClass(fqn: String): HyperlinkInfo = HyperlinkInfo { project ->
        val target = ReadAction.compute<PsiClass?, RuntimeException> {
            JavaPsiFacade.getInstance(project).findClass(fqn.trimEnd('.'), GlobalSearchScope.allScope(project))
        }
        if (target != null) {
            navigateToElement(project, target)
        }
    }

    /** `com.e.Demo.run`：先找类，再在类里按名字找方法 / 字段；找不到成员就落到类上 */
    private fun navigateToMember(reference: String): HyperlinkInfo = HyperlinkInfo { project ->
        val dot = reference.lastIndexOf('.')
        if (dot <= 0) {
            return@HyperlinkInfo
        }
        val fqn = reference.substring(0, dot)
        val memberName = reference.substring(dot + 1)
        val target = ReadAction.compute<PsiElement?, RuntimeException> {
            val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
            if (psiClass == null) {
                null
            } else {
                psiClass.findMethodsByName(memberName, true).firstOrNull()
                    ?: psiClass.findFieldByName(memberName, true)
                    ?: psiClass.findInnerClassByName(memberName, false)
                    ?: psiClass
            }
        }
        if (target != null) {
            navigateToElement(project, target)
        }
    }

    /** 相对路径按工程根目录解析；带行号时直接定位到那一行 */
    private fun navigateToFile(reference: String): HyperlinkInfo = HyperlinkInfo { project ->
        val cleaned = reference.trim()
        val colon = cleaned.lastIndexOf(':')
        val (path, line) = if (colon > 0 && cleaned.substring(colon + 1).all { it.isDigit() }) {
            cleaned.substring(0, colon) to (cleaned.substring(colon + 1).toIntOrNull() ?: 0)
        } else {
            cleaned to 0
        }
        val file = resolveFile(project, path) ?: return@HyperlinkInfo
        if (line > 0) {
            OpenFileDescriptor(project, file, line - 1, 0).navigate(true)
        } else {
            OpenFileDescriptor(project, file, 0).navigate(true)
        }
    }

    private fun resolveFile(project: Project, path: String): VirtualFile? {
        val base = project.basePath ?: return null
        val candidates = listOf(Path.of(base, path), Path.of(path))
        for (candidate in candidates) {
            if (Files.isRegularFile(candidate)) {
                return LocalFileSystem.getInstance().findFileByNioFile(candidate)
            }
        }
        return null
    }

    /**
     * 统一用 [OpenFileDescriptor] 跳转：源码类、jar 里的反编译类、方法 / 字段都能落盘定位，
     * 不依赖 PsiElement 的 Navigatable 接口（241 上 PsiElement 不带 navigate）。
     */
    private fun navigateToElement(project: Project, element: PsiElement) {
        val file = element.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, file, element.textOffset).navigate(true)
    }
}

/** 与高亮过滤器一样走 consoleFilterProvider，控制台里各注册一个 */
class ConsoleLinkFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> =
        if (ModuleToggles.isEnabled(ModuleToggles.GREP)) arrayOf(ConsoleLinkFilter()) else emptyArray()
}
