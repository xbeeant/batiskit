package io.github.xbeeant.batiskit.format

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener

/** Mapper XML 格式化的 IDE 级设置（所有工程共用，与 Grep Console / MyBatis Log 同一套写法） */
class MapperXmlFormatSettingsState {
    /** 总开关：关掉后 Ctrl+Alt+L、右键格式化与保存时格式化全部停用，平台自带 XML 格式化器重新接管 */
    var enabled: Boolean = true

    /** 每层缩进的空格数，合法范围 1..8 */
    var indentWidth: Int = 4

    /** 保存文件时自动格式化（走 fileDocumentManagerListener 扩展点） */
    var formatOnSave: Boolean = false
}

@Service(Service.Level.APP)
@State(
    name = "BatisKitMapperXmlFormatSettings",
    storages = [Storage("batiskit-mapper-xml-format.xml")]
)
class MapperXmlFormatSettings : PersistentStateComponent<MapperXmlFormatSettingsState> {

    /** 保持单例：调用方持有 state 引用，loadState 只能就地拷字段。@JvmField 规避 getState() 签名冲突 */
    @JvmField
    val state = MapperXmlFormatSettingsState()

    override fun getState(): MapperXmlFormatSettingsState = state

    override fun loadState(state: MapperXmlFormatSettingsState) {
        MapperXmlFormatSettingsState::class.java.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            field.set(this.state, field.get(state))
        }
    }

    /** 传给纯逻辑格式化器的选项；非法值在 [MapperXmlFormatter.Options] 里再收敛一次 */
    fun options(): MapperXmlFormatter.Options = MapperXmlFormatter.Options(indentUnit = state.indentWidth)

    companion object {
        fun getInstance(): MapperXmlFormatSettings =
            ApplicationManager.getApplication().getService(MapperXmlFormatSettings::class.java)
    }
}

/**
 * 保存时格式化 Mapper XML。
 *
 * 注册在 `com.intellij.fileDocumentManagerListener` **扩展点**上而不是消息总线 topic：
 * 平台明确说明在消息总线回调里改 document 会造成嵌套修改（IDEA-71701），
 * 只有扩展点回调允许改文档内容。
 *
 * 只按文本内容判断是不是 Mapper XML，不需要 Project / PSI，避免保存路径上做索引查询。
 * 格式化本身幂等，重新保存不会再次改动文档。
 */
class MapperXmlSaveFormatListener : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val settings = MapperXmlFormatSettings.getInstance()
        if (!settings.state.enabled || !settings.state.formatOnSave) return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.fileType != XmlFileType.INSTANCE || !document.isWritable) return
        val text = document.text
        if (text.length > MAX_CHARS || !MapperXmlFormatter.isMapperXml(text)) return
        val formatted = MapperXmlFormatter.format(text, settings.options())
        if (formatted == text) return
        // 保存回调可能已在写锁内，runWriteAction 可重入
        ApplicationManager.getApplication().runWriteAction {
            document.replaceString(0, document.textLength, formatted)
        }
    }

    companion object {
        private const val MAX_CHARS = 1024 * 1024
    }
}
