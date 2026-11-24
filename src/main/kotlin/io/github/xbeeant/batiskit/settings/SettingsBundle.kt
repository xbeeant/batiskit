@file:Suppress("DEPRECATION") // 平台把 JDOM 原生类标了弃用，但它仍是 XmlSerializer 输出的唯一载体，且纯 JVM 可测

package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.util.xmlb.XmlSerializer
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.GeneratorState
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.config.MapperAssistSettingsState
import io.github.xbeeant.batiskit.console.GrepConsoleSettings
import io.github.xbeeant.batiskit.console.GrepConsoleSettingsState
import io.github.xbeeant.batiskit.format.MapperXmlFormatSettings
import io.github.xbeeant.batiskit.format.MapperXmlFormatSettingsState
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.GitSettingsState
import io.github.xbeeant.batiskit.json.JsonSettings
import io.github.xbeeant.batiskit.json.JsonState
import io.github.xbeeant.batiskit.log.MyBatisLogSettings
import io.github.xbeeant.batiskit.log.MyBatisLogSettingsState
import io.github.xbeeant.batiskit.rainbow.RainbowSettings
import io.github.xbeeant.batiskit.sequence.SequenceDiagramSettings
import io.github.xbeeant.batiskit.sequence.SequenceDiagramSettingsState
import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import java.io.StringReader

/** 一组可导入导出的偏好：key 是文件里的稳定标识，label 给提示用 */
data class SettingsSection(
    val key: String,
    val label: String,
    val stateClass: Class<*>,
    val service: PersistentStateComponent<*>,
)

/**
 * 设置的导入 / 导出 / 恢复默认（纯编解码，可在无 IDE 容器下单测）。
 *
 * 格式是一个 XML：每组设置一节，节里的内容直接是平台 `XmlSerializer` 的序列化结果——
 * 不自己写字段映射，设置类加字段时导出自动跟上；导入统一走各服务的 `loadState()`，
 * 这样 `normalize()` / 反射就地拷贝（保持单例引用）这些既有语义都不会被绕过。
 *
 * 模板全文和最近分支缓存**不在**这个通道里：前者走模板方案导入导出，后者是本机路径的历史数据。
 */
object SettingsBundle {

    const val ROOT = "batiskit-settings"
    const val VERSION = "1"

    data class ImportReport(
        val applied: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null && applied.isNotEmpty()

        fun summary(): String = when {
            error != null -> error
            applied.isEmpty() -> "文件里没有可导入的设置分组"
            else -> buildString {
                append("已导入 ").append(applied.size).append(" 组设置：").append(applied.joinToString("、"))
                if (skipped.isNotEmpty()) {
                    append("\n跳过 ").append(skipped.size).append(" 组（版本不匹配或格式有误）：")
                    append(skipped.joinToString("、"))
                }
                append("\n部分改动需要重新打开设置页或重启 IDE 才会完全生效。")
            }
        }
    }

    /** IDE 里实际参与导入导出的 10 组偏好（需要 Application，纯 JVM 单测不调这个方法） */
    fun sections(): List<SettingsSection> = listOf(
        SettingsSection("generator", "Generator", GenerationConfig::class.java, GeneratorState.getInstance()),
        SettingsSection("json", "JSON → Java 对象", JsonSettings::class.java, JsonState.getInstance()),
        SettingsSection("rainbow", "Rainbow Brackets", RainbowSettings.State::class.java, RainbowSettings.getInstance()),
        SettingsSection("git", "Git Toolbox", GitSettingsState::class.java, GitSettings.getInstance()),
        SettingsSection("mybatis-log", "MyBatis Log", MyBatisLogSettingsState::class.java, MyBatisLogSettings.getInstance()),
        SettingsSection("grep-console", "Grep Console", GrepConsoleSettingsState::class.java, GrepConsoleSettings.getInstance()),
        SettingsSection(
            "mapper-xml-format", "Mapper XML 格式化",
            MapperXmlFormatSettingsState::class.java, MapperXmlFormatSettings.getInstance()
        ),
        SettingsSection(
            "mapper-assist", "Mapper 辅助",
            MapperAssistSettingsState::class.java, MapperAssistSettings.getInstance()
        ),
        SettingsSection(
            "module-toggles", "模块开关",
            ModuleToggles.State::class.java, ModuleToggles.getInstance()
        ),
        SettingsSection(
            "sequence-diagram", "时序图",
            SequenceDiagramSettingsState::class.java, SequenceDiagramSettings.getInstance()
        ),
    )

    fun encode(sections: List<SettingsSection>): String {
        val root = Element(ROOT).setAttribute("version", VERSION)
        sections.forEach { section ->
            val state = section.service.getState() ?: return@forEach
            val serialized = runCatching { XmlSerializer.serialize(state) }.getOrElse { return@forEach }
            serialized.name = "state"
            root.addContent(
                Element("section")
                    .setAttribute("key", section.key)
                    .setAttribute("type", state.javaClass.name)
                    .addContent(serialized)
            )
        }
        return XMLOutputter(Format.getPrettyFormat()).outputString(root)
    }

    fun decode(text: String, sections: List<SettingsSection>): ImportReport {
        if (text.isBlank()) return ImportReport(error = "文件为空")
        val root = runCatching { parse(text) }
            .getOrElse { return ImportReport(error = "XML 解析失败：${it.message ?: "未知错误"}") }
        if (root.name != ROOT) {
            return ImportReport(error = "这不是 Batis Kit 设置文件（根标签应为 <$ROOT>）")
        }
        val byKey = sections.associateBy { it.key }
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (child in root.getChildren("section")) {
            val key = child.getAttributeValue("key").orEmpty()
            val section = byKey[key]
            val stateElement = child.getChild("state")
            if (section == null || stateElement == null) {
                skipped += key.ifBlank { "(未知分组)" }
                continue
            }
            val value = runCatching { XmlSerializer.deserialize(stateElement, section.stateClass) }.getOrNull()
            if (value == null || load(section, value).isFailure) {
                skipped += key
            } else {
                applied += section.label
            }
        }
        return ImportReport(applied, skipped)
    }

    /** 恢复出厂默认：用各组 state 类的无参构造（即代码里的默认值）覆盖当前设置 */
    fun reset(sections: List<SettingsSection>): Int {
        var count = 0
        sections.forEach { section ->
            val fresh = runCatching {
                val ctor = section.stateClass.getDeclaredConstructor()
                ctor.isAccessible = true
                ctor.newInstance()
            }.getOrNull() ?: return@forEach
            if (load(section, fresh).isSuccess) count++
        }
        return count
    }

    private fun parse(text: String): Element {
        val builder = SAXBuilder()
        // 本地文件也不给 XXE 机会：禁 DOCTYPE 与外部实体
        runCatching { builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { builder.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        builder.setExpandEntities(false)
        return builder.build(StringReader(text)).rootElement
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(section: SettingsSection, value: Any): Result<Unit> = runCatching {
        (section.service as PersistentStateComponent<Any>).loadState(value)
    }
}
