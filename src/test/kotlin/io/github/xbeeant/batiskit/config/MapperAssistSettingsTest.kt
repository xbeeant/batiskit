package io.github.xbeeant.batiskit.config

import io.github.xbeeant.batiskit.meta.MapperSqlRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapper 辅助设置的纯 JVM 测试：默认值、规范化与快照收敛，不依赖 IDE 服务容器 */
class MapperAssistSettingsTest {

    @Test
    fun `默认五个开关都打开且前缀表为默认值`() {
        val state = MapperAssistSettingsState()
        assertTrue(state.navigationIconsEnabled)
        assertTrue(state.autoDiscoverTables)
        assertTrue(state.columnCompletionEnabled)
        assertTrue(state.sqlContextCompletionEnabled)
        assertTrue(state.crudSqlEnabled)
        assertEquals(MapperSqlRules.DEFAULT_PREFIX_RULES, state.prefixRules)
        assertTrue(state.tableMappings.isEmpty())
        assertTrue(state.recentTables.isEmpty())
        assertTrue(state.tableSnapshots.isEmpty())
    }

    @Test
    fun `规范化把非法前缀配置恢复成默认值`() {
        val state = MapperAssistSettingsState().apply { prefixRules = "not a rule" }
        state.normalize()
        assertEquals(MapperSqlRules.DEFAULT_PREFIX_RULES, state.prefixRules)
        assertEquals("select", MapperSqlRules.kindOf("getUser", MapperSqlRules.parsePrefixRules(state.prefixRules)))
    }

    @Test
    fun `规范化保留合法前缀配置并去掉注释行`() {
        val state = MapperAssistSettingsState().apply { prefixRules = "# note\nfind:select\nsave=insert" }
        state.normalize()
        assertTrue(state.prefixRules.contains("find=select"))
        assertTrue(state.prefixRules.contains("save=insert"))
        assertEquals("insert", MapperSqlRules.kindOf("saveUser", MapperSqlRules.parsePrefixRules(state.prefixRules)))
    }

    @Test
    fun `规范化把手写映射整理成稳定格式`() {
        val state = MapperAssistSettingsState().apply {
            tableMappings = "# x\nUserMapper : `sys_user`\nBad Line\ncom.e.OrderMapper=t_order"
        }
        state.normalize()
        assertEquals("UserMapper=sys_user\ncom.e.OrderMapper=t_order", state.tableMappings)
    }

    @Test
    fun `最近使用表去重并限量`() {
        val state = MapperAssistSettingsState().apply {
            recentTables = (1..12).map { "table_$it" }.toMutableList()
        }
        state.normalize()
        assertEquals(MapperSqlRules.MAX_RECENT, state.recentTables.size)
        assertEquals("table_1", state.recentTables.first())
    }

    @Test
    fun `快照列名被清洗 去空 去重且数量收敛`() {
        val state = MapperAssistSettingsState().apply {
            tableSnapshots = mutableListOf(
                TableColumnSnapshot().apply {
                    name = "`public`.`sys_user`"
                    dataSource = "local"
                    columns = mutableListOf(
                        ColumnSnapshot().apply { name = "`id`" },
                        ColumnSnapshot().apply { name = "id" },
                        ColumnSnapshot().apply { name = "###" },
                        ColumnSnapshot().apply { name = "user_name" }
                    )
                }
            )
        }
        state.normalize()
        val snapshot = state.tableSnapshots.single()
        assertEquals("sys_user", snapshot.name)
        assertEquals(listOf("id", "user_name"), snapshot.columns.map { it.name })
    }

    @Test
    fun `同名快照只保留最后写入的一张`() {
        val state = MapperAssistSettingsState().apply {
            tableSnapshots = mutableListOf(
                TableColumnSnapshot().apply { name = "user" },
                TableColumnSnapshot().apply { name = "USER" }
            )
        }
        state.normalize()
        assertEquals(1, state.tableSnapshots.size)
    }

    @Test
    fun `恢复默认时状态类可用无参构造创建`() {
        val fresh = MapperAssistSettingsState::class.java.getDeclaredConstructor().newInstance()
        assertEquals(MapperSqlRules.DEFAULT_PREFIX_RULES, fresh.prefixRules)
        assertTrue(fresh.navigationIconsEnabled)
        assertTrue("自动发现必须默认开启：首次使用不能要求用户先选表", fresh.autoDiscoverTables)
    }

    @Test
    fun `loadState 会带回自动发现开关的新值`() {
        val service = MapperAssistSettings()
        val loaded = MapperAssistSettingsState().apply { autoDiscoverTables = false }
        service.loadState(loaded)
        assertEquals(false, service.state.autoDiscoverTables)
        // loadState 会 normalize 并按前缀长度重排，断言语义等价而非字面相等
        assertEquals(
            "select",
            MapperSqlRules.kindOf("getUser", MapperSqlRules.parsePrefixRules(service.state.prefixRules))
        )
        assertEquals(
            "delete",
            MapperSqlRules.kindOf("removeById", MapperSqlRules.parsePrefixRules(service.state.prefixRules))
        )
    }
}
