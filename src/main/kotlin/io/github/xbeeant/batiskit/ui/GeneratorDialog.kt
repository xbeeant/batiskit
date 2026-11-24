package io.github.xbeeant.batiskit.ui

import com.intellij.database.psi.DbTable
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.support.Naming
import io.github.xbeeant.batiskit.write.ModuleRoots
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * 「生成代码」配置弹窗：选择模块 / 包名 / 模板方案，并预览将要生成的实体类名。
 */
class GeneratorDialog(
    project: Project,
    private val tables: List<DbTable>,
    private val initialConfig: GenerationConfig
) : DialogWrapper(project, true) {

    private val modules: List<Module> = ModuleRoots.modulesWithJava(project)
    private val tableModel = TablePreviewModel(tables)

    /** 表预览嵌进「生成」Tab 顶部（随 Tab 滚动），避免与 Tabs 在弹窗底部争抢高度互相遮挡 */
    private val previewPanel: JComponent = JPanel(BorderLayout()).apply {
        val table = com.intellij.ui.table.JBTable(tableModel)
        // 用视口首选尺寸声明高度（表在 scrollpane 里的标准做法），
        // 面板级 preferredSize 在 FormBuilder 网格里可能被压成 0 导致整个预览不可见
        table.preferredScrollableViewportSize = Dimension(700, 62)
        table.fillsViewportHeight = true
        table.setShowGrid(true)
        val search = TableSearchSupport(table, tableModel)
        val listTitle = JBLabel("待生成的表：${tables.size} 张（点列头排序）").apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        val header = JPanel(BorderLayout(JBUI.scale(8), 0))
        header.add(listTitle, BorderLayout.WEST)
        header.add(search.field, BorderLayout.CENTER)
        header.add(search.countLabel, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        // 多了一行搜索框，同步抬高面板，否则 FormBuilder 网格会把表格挤没
        preferredSize = Dimension(720, 140)
        minimumSize = Dimension(720, 140)
    }

    private val panel = ConfigPanel(
        showModuleChooser = true,
        preview = previewPanel,
        // 项目模板（.batiskit/templates/）只在有工程上下文时可见
        basePath = { project.basePath }
    )

    init {
        title = "Batis Kit 代码生成"
        panel.resetFrom(initialConfig, modules)
        tableModel.refresh(initialConfig.tablePrefixes, initialConfig.entitySuffix)
        watchNamingChanges()
        init()
    }

    override fun createCenterPanel(): JComponent = panel.component

    override fun doValidate(): ValidationInfo? {
        val error = panel.validate()
        return if (error != null) ValidationInfo(error, panel.component) else null
    }

    fun selectedModule(): Module? = panel.selectedModule() ?: modules.firstOrNull()

    fun applyTo(config: GenerationConfig) = panel.applyTo(config)

    private fun watchNamingChanges() {
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshNames()
            override fun removeUpdate(e: DocumentEvent) = refreshNames()
            override fun changedUpdate(e: DocumentEvent) = refreshNames()
        }
        (panel.tablePrefixComponent() as? javax.swing.text.JTextComponent)?.document?.addDocumentListener(listener)
        (panel.entitySuffixComponent() as? javax.swing.text.JTextComponent)?.document?.addDocumentListener(listener)
    }

    private fun refreshNames() {
        val prefixes = (panel.tablePrefixComponent() as? javax.swing.text.JTextComponent)?.text.orEmpty()
        val suffix = (panel.entitySuffixComponent() as? javax.swing.text.JTextComponent)?.text.orEmpty()
        tableModel.refresh(prefixes, suffix)
    }
}

/** 表名 -> 实体类名的预览表格 */
private class TablePreviewModel(private val tables: List<DbTable>) : AbstractTableModel() {

    private val entityNames = mutableListOf<String>()

    private val headers = arrayOf("表名", "实体类名", "注释")

    init {
        entityNames.addAll(tables.map { it.name })
    }

    fun refresh(prefixes: String, suffix: String) {
        tables.forEachIndexed { index, table ->
            entityNames[index] = Naming.entityName(table.name, prefixes, suffix)
        }
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = tables.size

    override fun getColumnCount(): Int = headers.size

    override fun getColumnName(column: Int): String = headers[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val table = tables[rowIndex]
        return when (columnIndex) {
            0 -> table.name
            1 -> entityNames.getOrElse(rowIndex) { table.name }
            else -> table.comment?.trim().orEmpty()
        }
    }
}
