package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel

/**
 * Settings → Tools → **Batis Kit** 的父节点。
 *
 * 本身没有表单状态（不标脏、不写盘，避免影响子页的 Apply / Reset），只做两件事：
 * 说明十个子页各管什么，以及承载整组设置的「导入 / 导出 / 恢复默认」。
 * 后者直接写各服务的 `loadState()`，不需要把子页逐个打开点 Apply。
 */
class BatisKitettingsGroup : SearchableConfigurable {

    override fun getId(): String = ID

    private var root: JPanel? = null

    override fun getDisplayName(): String = "Batis Kit"

    override fun createComponent(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(16)
        add(
            JBLabel(
                """
                <html>
                <h2>Batis Kit</h2>
                <p>插件的十组设置都收在这个节点下，从左侧选择子项即可修改：</p>
                <ul>
                  <li><b>Generator</b>：代码生成的默认配置、模板方案与写入策略</li>
                  <li><b>JSON → Java 对象</b>：JSON 转 POJO 的全局默认值</li>
                  <li><b>Rainbow Brackets</b>：彩虹括号的开关、循环层数与语言白名单</li>
                  <li><b>Git Toolbox</b>：行尾 blame、自动 fetch、分支清理与提交校验</li>
                  <li><b>MyBatis Log</b>：控制台 SQL 的捕获、格式化与复制</li>
                  <li><b>Grep Console</b>：控制台级别着色、关键词高亮与日志过滤</li>
                  <li><b>Mapper XML 格式化</b>：总开关、缩进宽度与保存时格式化</li>
                  <li><b>Mapper 辅助</b>：导航图标开关、表字段 / SQL 上下文补全、CRUD 前缀规则、表名映射与最近使用表的记忆</li>
                  <li><b>模块开关</b>：按模块启停代码生成 / 日志 / Grep / 彩虹括号 / Git / 字符串 / 时序图等能力，避免与本机其它插件重复入口</li>
                  <li><b>时序图</b>：编辑生成时序图时跳过的基础类 / 工具类名单（包前缀、简单类名与始终保留的例外），不再写死在代码里</li>
                </ul>
                <p>下面可以整组导出成 XML、从 XML 导回，或一次性恢复默认值。用户模板 / 项目模板与最近分支记录不在此列；
                模板请用模板工作台里的「导入 / 导出方案」。</p>
                <p>括号配色属于平台配色方案，仍在 Settings → Editor → Color Scheme → Rainbow Brackets 里改。</p>
                </html>
                """.trimIndent()
            ),
            BorderLayout.NORTH
        )
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttons.add(JButton("导出设置…").apply { addActionListener { export() } })
        buttons.add(JButton("导入设置…").apply { addActionListener { import() } })
        buttons.add(JButton("恢复默认设置…").apply { addActionListener { resetDefaults() } })
        add(buttons, BorderLayout.SOUTH)
        root = this
    }

    private fun export() {
        val descriptor = FileSaverDescriptor(
            "导出 Batis Kit 设置", "把十组偏好保存为一个 XML 文件（不含模板与最近分支）", "xml"
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, null as Project?)
            .save(null as VirtualFile?, "batiskit-settings.xml") ?: return
        // sections()/encode 读各服务状态，留在 EDT；只有磁盘写入下后台，结果回到 EDT 弹提示。
        // 记下当前模态状态，避免设置对话框打开期间 invokeLater 被模态循环挡住。
        val encoded = runCatching { SettingsBundle.encode(SettingsBundle.sections()) }
        val modality = ModalityState.current()
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            val outcome = encoded.mapCatching { FileUtil.writeToFile(wrapper.file, it) }
            ApplicationManager.getApplication().invokeLater({
                if (outcome.isSuccess) {
                    info("已导出到 ${wrapper.file.absolutePath}")
                } else {
                    error("写入失败：${outcome.exceptionOrNull()?.message ?: "未知错误"}")
                }
            }, modality)
        })
    }

    private fun import() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("导入 Batis Kit 设置")
            .withDescription("选择之前导出的 XML 设置文件")
        val file = FileChooser.chooseFile(descriptor, null as Project?, null as VirtualFile?) ?: return
        // VFS → java.io.File 在 EDT 上取，后台只做 readText；decode 会 loadState 改服务状态，放回 EDT。
        val ioFile = VfsUtil.virtualToIoFile(file)
        val modality = ModalityState.current()
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            val text = runCatching { ioFile.readText(Charsets.UTF_8) }
            ApplicationManager.getApplication().invokeLater({
                text.fold(
                    onSuccess = { content ->
                        val report = SettingsBundle.decode(content, SettingsBundle.sections())
                        if (report.isSuccess) info(report.summary()) else error(report.summary())
                    },
                    onFailure = { error("读取文件失败：${it.message ?: "未知错误"}") },
                )
            }, modality)
        })
    }

    private fun resetDefaults() {
        val answer = JOptionPane.showConfirmDialog(
            root,
            "把十组偏好设置全部恢复为默认值？\n用户模板、项目模板与最近分支记录不受影响。",
            "恢复默认设置",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (answer != JOptionPane.OK_OPTION) return
        val count = SettingsBundle.reset(SettingsBundle.sections())
        info("已恢复 $count 组设置为默认值。部分改动需要重新打开设置页或重启 IDE 才会完全生效。")
    }

    private fun info(message: String) =
        JOptionPane.showMessageDialog(root, message, "Batis Kit 设置", JOptionPane.INFORMATION_MESSAGE)

    private fun error(message: String) =
        JOptionPane.showMessageDialog(root, message, "Batis Kit 设置", JOptionPane.ERROR_MESSAGE)

    /** 没有自己的状态：不标脏、不写盘，避免父节点影响子页的 Apply / Reset */
    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun reset() {}

    override fun disposeUIResources() {
        root = null
    }

    companion object {
        /** 子项 id 都以它为前缀，符合平台对父子 configurable 的 id 约定 */
        const val ID = "io.github.xbeeant.batiskit"
    }
}
