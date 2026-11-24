package io.github.xbeeant.batiskit.write

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile

/** 单次生成的统计结果 */
class GenerationStats {
    var created: Int = 0
    var merged: Int = 0
    val skipped: MutableList<String> = mutableListOf()
    val errors: MutableList<String> = mutableListOf()
    val files: MutableList<PsiFile> = mutableListOf()
}

/**
 * 把生成的源码写进模块。
 *
 * 写文件的两个原则：
 *  1. 文件不存在 -> 创建；
 *  2. 文件已存在 -> 合并：缺的成员补上；同签名方法实现有差异时按覆盖开关决定是否替换。
 */
class CodeWriter(
    private val project: Project,
    private val module: Module,
    private val overwriteMethods: Boolean = true
) {

    fun writeJava(packageName: String, fileName: String, text: String, stats: GenerationStats) {
        val root = ModuleRoots.javaRoot(project, module)
        if (root == null) {
            stats.errors += "$fileName: 模块 ${module.name} 下没有找到 Java 源码目录（src/main/java）"
            return
        }
        val dir = root.ensureRelative(packageName)
        val existing = dir.findFile(fileName)
        if (existing == null) {
            val created = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, JavaFileType.INSTANCE, text)
            dir.add(created)
            CodeStyleManager.getInstance(project).reformat(created)
            stats.created++
            stats.files += created
            return
        }
        if (existing !is PsiJavaFile) {
            stats.skipped += fileName
            return
        }
        val outcome = JavaMerger.merge(existing, text, overwriteMethods)
        if (outcome.error != null) {
            stats.errors += "$fileName: ${outcome.error}"
        } else if (outcome.added == 0) {
            stats.skipped += fileName
        } else {
            stats.merged++
            stats.files += existing
        }
    }

    /**
     * @param overwrite true 时直接覆盖已有 XML（对应原插件 Overwrite Old Mapper XML File），
     *                  false 按 id 合并补缺（默认，保护人工修改）
     */
    fun writeXml(relativeDir: String, fileName: String, text: String, stats: GenerationStats, overwrite: Boolean = false) {
        val root = resourceRoot() ?: run {
            stats.errors += "$fileName: 模块 ${module.name} 下没有找到资源目录"
            return
        }
        val dir = root.ensureRelative(relativeDir)
        val existing = dir.findFile(fileName)
        if (existing == null) {
            val created = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, XmlFileType.INSTANCE, text)
            dir.add(created)
            CodeStyleManager.getInstance(project).reformat(created)
            stats.created++
            stats.files += created
            return
        }
        if (overwrite) {
            existing.delete()
            val created = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, XmlFileType.INSTANCE, text)
            dir.add(created)
            CodeStyleManager.getInstance(project).reformat(created)
            stats.created++
            stats.files += created
            return
        }
        if (existing !is XmlFile) {
            stats.skipped += fileName
            return
        }
        val outcome = XmlMerger.merge(existing, text)
        if (outcome.error != null) {
            stats.errors += "$fileName: ${outcome.error}"
        } else if (outcome.added == 0) {
            stats.skipped += fileName
        } else {
            stats.merged++
            stats.files += existing
        }
    }

    /**
     * 按模板声明的相对路径写入（模板驱动生成的入口）。
     *
     * 与 [writeJava] / [writeXml] 的区别是路径不再由「包名 + 文件名」拼，而是模板里写好的
     * `com/example/model/User.java` 这样的相对路径 —— 用户新增模板时不需要回到代码里加映射。
     *
     * @param root `java` 写到源码根，`resources` 写到资源根
     * @param merge `java` 按签名补缺 / `xml` 按 id 补缺 / `overwrite` 直接覆盖
     */
    fun writeRelative(root: String, relativePath: String, text: String, merge: String, stats: GenerationStats) {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        val fileName = normalized.substringAfterLast('/')
        val dirPart = normalized.substringBeforeLast('/', "")
        if (fileName.isBlank()) {
            stats.errors += "模板输出的路径不合法：$relativePath"
            return
        }
        val base = if (root == ROOT_RESOURCES) resourceRoot() else ModuleRoots.javaRoot(project, module)
        if (base == null) {
            val which = if (root == ROOT_RESOURCES) "资源目录" else "Java 源码目录（src/main/java）"
            stats.errors += "$fileName: 模块 ${module.name} 下没有找到$which"
            return
        }
        val dir = dirPart.takeIf { it.isNotBlank() }?.let { base.ensureRelative(it) } ?: base
        val fileType = if (fileName.endsWith(".xml", ignoreCase = true)) XmlFileType.INSTANCE else JavaFileType.INSTANCE
        val existing = dir.findFile(fileName)
        if (existing != null && merge == MERGE_OVERWRITE) {
            existing.delete()
        }
        val current = dir.findFile(fileName)
        if (current == null) {
            val created = PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, text)
            dir.add(created)
            CodeStyleManager.getInstance(project).reformat(created)
            stats.created++
            stats.files += created
            return
        }
        when (merge) {
            MERGE_XML -> {
                if (current !is XmlFile) {
                    stats.skipped += fileName
                    return
                }
                val outcome = XmlMerger.merge(current, text)
                record(outcome.error, outcome.added, fileName, current, stats)
            }

            else -> {
                if (current !is PsiJavaFile) {
                    stats.skipped += fileName
                    return
                }
                val outcome = JavaMerger.merge(current, text, overwriteMethods)
                record(outcome.error, outcome.added, fileName, current, stats)
            }
        }
    }

    private fun record(error: String?, added: Int, fileName: String, file: PsiFile, stats: GenerationStats) {
        if (error != null) {
            stats.errors += "$fileName: $error"
        } else if (added == 0) {
            stats.skipped += fileName
        } else {
            stats.merged++
            stats.files += file
        }
    }

    /**
     * 资源目录解析与创建（所有兜底都保证结果不落进 src/main/src/main）：
     * 1. 已存在的资源目录（ModuleRoots.resourceRoot，含源码根反推）
     * 2. 从 Java 源码根创建：src/main/java 的兄弟目录 resources
     * 3. 内容根下按需创建（内容根在 src / src/main / main 里时只建 resources）
     */
    private fun resourceRoot(): PsiDirectory? {
        ModuleRoots.resourceRoot(project, module)?.let { return it }
        val manager = PsiManager.getInstance(project)

        // 从 Java 源码根创建资源目录：.../src/main/resources
        ModuleRoots.javaRoot(project, module)?.let { javaRoot ->
            val mainDir = javaRoot.parent
            val srcDir = mainDir?.parent
            if (mainDir?.name == "main" && srcDir?.name == "src") {
                val vf = (srcDir.findSubdirectory("resources") ?: mainDir.createSubdirectory("resources")).virtualFile
                if (!ModuleRoots.isDoubledMainPath(vf)) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                    manager.findDirectory(vf)?.let { return it }
                }
            }
        }

        val contentRoot = ModuleRoots.contentRoot(module) ?: return null
        val contentRootDir = manager.findDirectory(contentRoot) ?: return null
        // 内容根本身就在 src / src/main / main 里时，只建 resources，不再拼 src/main 前缀
        val relative = if (contentRoot.name == "src" || contentRoot.name == "src/main" || contentRoot.name == "main") {
            "resources"
        } else {
            ModuleRoots.RESOURCE_PATH
        }
        val resources = contentRootDir.ensureRelative(relative)
        VfsUtil.markDirtyAndRefresh(false, true, true, contentRoot)
        return resources
    }

    companion object {
        /** 模板里 `root:` 的取值（与 TemplateSpec 保持一致，这里用字面量避免 write 包反向依赖模板包） */
        const val ROOT_RESOURCES = "resources"

        /** 模板里 `merge:` 的取值 */
        const val MERGE_XML = "xml"
        const val MERGE_OVERWRITE = "overwrite"
    }
}
