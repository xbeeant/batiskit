package io.github.xbeeant.batiskit.log

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * 从一条 MyBatis 控制台日志跳到它对应的 Mapper XML 语句标签。
 *
 * 标准 MyBatis 日志不带 statement id，所以走 [SqlStatementMatcher] 做 SQL 文本相似度匹配：
 *  - 候选是工程内所有 Mapper XML 的 `<select|insert|update|delete id>` 标签（只扫 XML 文件索引）；
 *  - 收集与打分在后台线程 + 智能模式读锁里做，EDT 只负责跳转 / 弹选择器；
 *  - 明显领先时直接跳，多个相近语句时弹列表让用户选，避免「猜错了还一声不吭」。
 */
object MapperStatementNavigator {

    data class NavItem(
        val candidate: SqlStatementMatcher.Candidate,
        val file: VirtualFile,
        val offset: Int,
        val label: String
    )

    /** 收集工程内的语句候选（调用方需持有读锁） */
    internal fun collect(project: Project): List<NavItem> {
        val manager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val out = mutableListOf<NavItem>()
        FileTypeIndex.getFiles(XmlFileType.INSTANCE, scope).forEach { vf ->
            val file = manager.findFile(vf) as? XmlFile ?: return@forEach
            val root = file.rootTag ?: return@forEach
            if (root.name != "mapper") return@forEach
            val namespace = root.getAttributeValue("namespace").orEmpty()
            root.subTags.forEach { tag ->
                if (!MapperXmlResolver.isStatementTag(tag)) return@forEach
                val id = tag.getAttributeValue("id")?.trim().orEmpty()
                if (id.isEmpty()) return@forEach
                val fqn = if (namespace.isEmpty()) id else "$namespace.$id"
                out += NavItem(
                    candidate = SqlStatementMatcher.Candidate(id, namespace, tag.text, "$fqn@${vf.path}"),
                    file = vf,
                    offset = tag.textOffset,
                    label = "$fqn  (${tag.name}, ${vf.name})"
                )
            }
        }
        return out
    }

    /** [feedback] 在 EDT 调用，用于「没找到 / 索引没就绪 / 文件已失效」等提示 */
    fun navigate(project: Project, sql: String, feedback: (String, NotificationType) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = runCatching {
                DumbService.getInstance(project).runReadActionInSmartMode(
                    Computable<List<NavItem>> { collect(project) }
                )
            }.getOrElse {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        feedback("索引尚未就绪或读取失败，请稍后重试", NotificationType.WARNING)
                    }
                }
                return@executeOnPooledThread
            }
            val matches = SqlStatementMatcher.rank(sql, items.map { it.candidate })
            val byKey = items.associateBy { it.candidate.key }
            val targets = matches.mapNotNull { match ->
                byKey[match.candidate.key]?.let { match.score to it }
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                when {
                    targets.isEmpty() ->
                        feedback("没有找到对应的 Mapper XML 语句（按 SQL 文本相似度匹配）", NotificationType.WARNING)

                    SqlStatementMatcher.isClearWinner(matches) ->
                        open(project, targets.first().second, feedback)

                    else -> choose(project, targets, feedback)
                }
            }
        }
    }

    private fun choose(
        project: Project,
        targets: List<Pair<Int, NavItem>>,
        feedback: (String, NotificationType) -> Unit
    ) {
        val choices = targets.associateBy { "${it.second.label}  ·  ${it.first} 分" }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices.keys.sorted())
            .setTitle("选择 Mapper XML 语句（按相似度排序）")
            .setItemChosenCallback { chosen ->
                choices[chosen]?.second?.let { open(project, it, feedback) }
            }
            .createPopup()
            .showInFocusCenter()
    }

    private fun open(project: Project, item: NavItem, feedback: (String, NotificationType) -> Unit) {
        if (!item.file.isValid) {
            feedback("语句所在文件已失效，请刷新索引后重试", NotificationType.WARNING)
            return
        }
        OpenFileDescriptor(project, item.file, item.offset).navigate(true)
    }
}
