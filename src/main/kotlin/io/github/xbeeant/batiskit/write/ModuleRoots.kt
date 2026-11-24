package io.github.xbeeant.batiskit.write

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager

/**
 * 解析模块里的源码目录 / 资源目录。
 *
 * 约定（覆盖 Maven / Gradle 标准结构）：
 *  - Java 源码：<content root>/src/main/java，找不到时退化为第一个源码根
 *  - 资源目录：<content root>/src/main/resources，不存在则按需创建
 */
object ModuleRoots {

    fun modulesWithJava(project: Project): List<Module> =
        ModuleManager.getInstance(project).modules.filter { module ->
            ModuleRootManager.getInstance(module).sourceRoots.isNotEmpty() ||
                ModuleRootManager.getInstance(module).contentRoots.any {
                    it.findFileByRelativePath("src/main/java") != null
                }
        }

    /**
     * 推荐写入的目标模块：
     * 优先内容根直接包含 src/main/java 的「工程主模块」（Maven/Gradle 标准结构），
     * 避免选中 Gradle 同步生成的源集子模块（如 xxx.main，内容根指向 <工程>/src/main）
     */
    fun suggestModule(project: Project): Module? {
        val withJava = modulesWithJava(project)
        withJava.firstOrNull { module ->
            ModuleRootManager.getInstance(module).contentRoots.any {
                it.findFileByRelativePath(JAVA_PATH) != null
            }
        }?.let { return it }
        return withJava.firstOrNull()
    }

    fun javaRoot(project: Project, module: Module): PsiDirectory? {
        ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
            root.findFileByRelativePath(JAVA_PATH)?.takeIf { it.isDirectory }?.let { vf ->
                return PsiManager.getInstance(project).findDirectory(vf)
            }
        }
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots
        val candidate = sourceRoots.firstOrNull { it.name == "java" } ?: sourceRoots.firstOrNull() ?: return null
        return PsiManager.getInstance(project).findDirectory(candidate)
    }

    /** 路径是否落进了 src/main/src/main 这类历史误建的重复目录（不再往里写） */
    fun isDoubledMainPath(vf: VirtualFile): Boolean = vf.path.contains("src/main/src/main")

    /**
     * 资源根目录解析（只找已存在的目录，创建由调用方兜底）：
     * 1. 最可靠：从 Java 源码根反推——.../src/main/java 的兄弟目录 resources
     * 2. 标准结构：<content root>/src/main/resources
     * 3. 内容根本身指向 src / src/main / main 时，是其下的 resources
     * 所有候选都排除 src/main/src/main 重复路径（早期版本误建的目录）
     */
    fun resourceRoot(project: Project, module: Module): PsiDirectory? {
        val manager = PsiManager.getInstance(project)
        javaRoot(project, module)?.let { javaRoot ->
            val mainDir = javaRoot.parent
            val srcDir = mainDir?.parent
            if (mainDir?.name == "main" && srcDir?.name == "src") {
                srcDir.findSubdirectory("resources")?.let { dir ->
                    val vf = dir.virtualFile
                    if (vf.isDirectory && !isDoubledMainPath(vf)) {
                        return dir
                    }
                }
            }
        }
        val roots = ModuleRootManager.getInstance(module).contentRoots
        roots.forEach { root ->
            root.findFileByRelativePath(RESOURCE_PATH)?.takeIf { it.isDirectory && !isDoubledMainPath(it) }?.let { vf ->
                return manager.findDirectory(vf)
            }
        }
        roots.forEach { root ->
            if (root.name == "src" || root.name == "src/main" || root.name == "main") {
                root.findChild("resources")?.takeIf { it.isDirectory && !isDoubledMainPath(it) }?.let { vf ->
                    return manager.findDirectory(vf)
                }
            }
        }
        return null
    }

    fun contentRoot(module: Module): VirtualFile? =
        ModuleRootManager.getInstance(module).contentRoots.firstOrNull()

    /**
     * 资源根目录解析，不存在时按需创建（write action 内调用）：
     * 优先从 Java 源码根的兄弟目录创建 src/main/resources，内容根兜底时不拼 src/main 前缀
     */
    fun ensureResourceRoot(project: Project, module: Module): PsiDirectory? {
        resourceRoot(project, module)?.let { return it }
        javaRoot(project, module)?.let { javaRoot ->
            val mainDir = javaRoot.parent
            val srcDir = mainDir?.parent
            if (mainDir?.name == "main" && srcDir?.name == "src") {
                val dir = srcDir.findSubdirectory("resources") ?: mainDir.createSubdirectory("resources")
                return dir
            }
        }
        val contentRoot = contentRoot(module) ?: return null
        val dir = PsiManager.getInstance(project).findDirectory(contentRoot) ?: return null
        val relative = if (contentRoot.name == "src" || contentRoot.name == "src/main" || contentRoot.name == "main") {
            "resources"
        } else {
            RESOURCE_PATH
        }
        return dir.ensureRelative(relative)
    }

    const val JAVA_PATH = "src/main/java"
    const val RESOURCE_PATH = "src/main/resources"
}

/** 逐级创建 / 查找子目录 */
fun PsiDirectory.ensureRelative(path: String): PsiDirectory {
    var dir = this
    path.split('/', '.').filter { it.isNotBlank() }.forEach { part ->
        dir = dir.findSubdirectory(part) ?: dir.createSubdirectory(part)
    }
    return dir
}
