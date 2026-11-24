package io.github.xbeeant.batiskit.log

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * 读剪贴板文本（「导入剪贴板日志」「还原选中日志」在没有选区时回退到这里）。
 */
object MyBatisLogClipboard {

    fun readText(): String? {
        val text = runCatching {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        }.getOrNull()
        if (!text.isNullOrEmpty()) {
            return text
        }
        // 从浏览器 / 终端复制过来的内容不一定是平台自己的 Transferable，直接问系统剪贴板
        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }
}
