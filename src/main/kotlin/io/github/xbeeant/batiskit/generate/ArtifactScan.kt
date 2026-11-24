package io.github.xbeeant.batiskit.generate

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import io.github.xbeeant.batiskit.template.TemplateSpec

/**
 * 一次渲染算出来的「产物清单」：生成预览和漂移比对共用这一份。
 *
 * 以前「比对已生成代码」自己也写了一套路径推导：单独 `forSet` 取模板、单独 `renderPath`、
 * 单独用 `ModuleRoots` 找根。两条链路只要有一处口径不同（方案、框架对齐、enabled、
 * 用户排除的模板、源码根），结果就是「文件明明在磁盘上，却报产物未生成」。
 * 现在两条链路都从 [CodeGenerator.scan] 出发，路径与写盘时逐字相同。
 *
 * [existingFile] 是唯一的存在性依据：不依赖 Java PSI 索引（索引没建好、源码根没标记、
 * 文件刚生成时 `findClass` 都可能返回 null，但文件其实就在那里）。
 */
data class RenderedArtifact(
    val tableName: String,
    val spec: TemplateSpec,
    val root: String,
    val relativePath: String,
    val text: String,
    /** 磁盘上已存在的同名文件；null 才是真的「没生成过」 */
    val existingFile: VirtualFile? = null,
    /** 命中已有文件时它属于哪个模块；跨模块查找后用它决定同步写回哪里 */
    val ownerModule: Module? = null
) {
    val exists: Boolean get() = existingFile != null

    val fileName: String get() = relativePath.substringAfterLast('/')

    val templateKey: String get() = "${spec.set}:${spec.id}"
}

/**
 * [specs] 是方案里的全部模板（含被用户排除的），[activeSpecs] 是本次真正参与渲染的，
 * [artifacts] 只含实际会产出的文件（模板 `enabled` 为假时不产出，也就不该被报「未生成」）。
 */
class ArtifactScan(
    val setId: String,
    val specs: List<TemplateSpec>,
    val activeSpecs: List<TemplateSpec>,
    val artifacts: List<RenderedArtifact>,
    val errors: List<String>,
    val warnings: List<String>
) {
    /** 已有产物命中最多的模块：比对时不必猜「当时生成到了哪个模块」 */
    fun ownerModule(): Module? = artifacts
        .mapNotNull { it.ownerModule }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
}
