package io.github.xbeeant.batiskit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/** 生成预览 / 表清单复用的「搜索 + 列头排序」接线（纯 Swing，不起 IDE 夹具） */
class TableSearchSupportTest {

    private fun fixture(): Pair<JTable, TableSearchSupport> {
        val model = DefaultTableModel(
            arrayOf(
                arrayOf<Any>("t_user", "User", "用户表"),
                arrayOf<Any>("t_order", "Order", "订单表"),
                arrayOf<Any>("t_user_log", "UserLog", "日志表"),
            ),
            arrayOf<Any>("表名", "实体类名", "注释")
        )
        val table = JTable(model)
        return table to TableSearchSupport(table, model)
    }

    @Test
    fun `装上之后默认显示全部行`() {
        val (table, _) = fixture()
        assertNotNull(table.rowSorter)
        assertEquals(3, table.rowCount)
        assertEquals(3, table.rowSorter.viewRowCount)
    }

    @Test
    fun `关键字搜索过滤所有列`() {
        val (table, support) = fixture()
        support.field.text = "订单"
        assertEquals(1, table.rowCount)
        assertEquals("t_order", table.getValueAt(0, 0))
    }

    @Test
    fun `空格分隔 AND 与减号排除`() {
        val (table, support) = fixture()
        support.field.text = "user -log"
        assertEquals(1, table.rowCount)
        assertEquals("t_user", table.getValueAt(0, 0))
    }

    @Test
    fun `正则搜索生效且非法正则不会让表格消失`() {
        val (table, support) = fixture()
        support.field.text = "/^t_(user|order)$/"
        assertEquals(2, table.rowCount)
        support.field.text = "/(/"
        assertTrue(table.rowCount >= 0)
        support.field.text = ""
        assertEquals(3, table.rowCount)
    }

    @Test
    fun `清空搜索恢复全部行`() {
        val (table, support) = fixture()
        support.field.text = "UserLog"
        assertEquals(1, table.rowCount)
        support.field.text = ""
        assertEquals(3, table.rowCount)
    }

    @Test
    fun `点列头排序后视图索引仍可转回模型索引`() {
        val (table, _) = fixture()
        table.rowSorter.toggleSortOrder(0)
        assertEquals(3, table.rowCount)
        val viewRows = (0 until table.rowCount).map { table.getValueAt(it, 0).toString() }
        assertEquals(listOf("t_order", "t_user", "t_user_log"), viewRows)
        (0 until table.rowCount).forEach { view ->
            val model = table.convertRowIndexToModel(view)
            assertEquals(table.getValueAt(view, 0), table.model.getValueAt(model, 0))
        }
    }

    @Test
    fun `搜索与排序可以叠加`() {
        val (table, support) = fixture()
        table.rowSorter.toggleSortOrder(2) // 按注释排，顺序会变
        support.field.text = "t_user"
        assertEquals(2, table.rowCount)
        val names = (0 until table.rowCount).map { table.getValueAt(it, 0).toString() }.toSet()
        assertEquals(setOf("t_user", "t_user_log"), names)
        (0 until table.rowCount).forEach { view ->
            assertEquals(table.getValueAt(view, 0), table.model.getValueAt(table.convertRowIndexToModel(view), 0))
        }
    }

    @Test
    fun `计数文本是纯逻辑且容错`() {
        assertEquals("共 8 项", TableSearchSupport.formatCount(8, 8))
        assertEquals("2 / 8 项", TableSearchSupport.formatCount(2, 8))
        assertEquals("共 0 项", TableSearchSupport.formatCount(0, 0))
        assertEquals("共 3 项", TableSearchSupport.formatCount(5, 3))
    }

    @Test
    fun `计数标签随搜索变化`() {
        val (_, support) = fixture()
        assertEquals("共 3 项", support.countLabel.text)
        support.field.text = "t_user -log"
        assertEquals("1 / 3 项", support.countLabel.text)
        support.field.text = ""
        assertEquals("共 3 项", support.countLabel.text)
    }

    @Test
    fun `排序不改变计数`() {
        val (table, support) = fixture()
        table.rowSorter.toggleSortOrder(0)
        assertEquals("共 3 项", support.countLabel.text)
    }

    @Test
    fun `模型行数变化后可手动刷新计数`() {
        val (table, support) = fixture()
        val model = table.model as DefaultTableModel
        model.addRow(arrayOf<Any>("t_item", "Item", "商品表"))
        support.refreshCount()
        assertEquals("共 4 项", support.countLabel.text)
        model.removeRow(0)
        support.refreshCount()
        assertEquals("共 3 项", support.countLabel.text)
    }
}
