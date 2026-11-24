package io.github.xbeeant.batiskit.template.ide

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/** `.tpl` 专属语言：让工程里的项目模板、全局模板草稿不再是「一片黑的纯文本」 */
object TemplateLanguage : Language("BatisKitTemplate")

object TemplateFileType : LanguageFileType(TemplateLanguage) {
    override fun getName(): String = "Batis Kit Template"
    override fun getDescription(): String = "Batis Kit code generation template"
    override fun getDefaultExtension(): String = "tpl"
    override fun getIcon(): Icon? = AllIcons.FileTypes.Text
}
