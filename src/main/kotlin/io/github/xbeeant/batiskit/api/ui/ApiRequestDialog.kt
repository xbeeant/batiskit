package io.github.xbeeant.batiskit.api.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.github.xbeeant.batiskit.ui.UiTokens
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.api.ApiRequestExecutor
import io.github.xbeeant.batiskit.api.ApiRequestRules
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * 「写接口 → 顺手测一下」的轻量对话框。
 *
 * 参数、请求头、请求体都是可编辑文本，改动后按「发送」即时生效；响应只是展示，
 * 需要保存用例 / 切环境变量时用「另存为 .http」交给 IDE 原生 HTTP Client。
 * 不维护请求历史、不存凭据、不接管 IDE 的 HTTP Client。
 */
class ApiRequestDialog(
    private val project: Project,
    private val initial: ApiRequestRules.Spec,
    private val endpointLabel: String = "",
) : DialogWrapper(project) {

    private val methodBox = ComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH"))

    private val urlField = JBTextField(initial.url)

    private val paramsArea = JBTextArea()

    private val headersArea = JBTextArea()

    private val bodyArea = JBTextArea()

    private val statusLabel = JBLabel("尚未发送请求")

    private val defaultLabelForeground = statusLabel.foreground

    private val responseArea = JBTextArea()

    @Volatile
    private var sending = false

    init {
        title = "发送请求 — Spring API"
        setOKButtonText("发送")
        methodBox.selectedItem = initial.method.uppercase()
        paramsArea.text = initialParams()
        headersArea.text = initial.headers.joinToString("\n") { (key, value) -> "$key: $value" }
        bodyArea.text = initial.body.orEmpty()
        responseArea.isEditable = false
        responseArea.text = "填写参数后点「发送」。响应会显示在这里，" +
            "需要保存为可复用的请求请点「另存为 .http」。"
        init()
    }

    override fun getDimensionServiceKey(): String = "BatisKit.ApiRequestDialog"

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(560))
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(methodBox, BorderLayout.WEST)
            add(urlField, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)

        val tabs = JBTabbedPane().apply {
            addTab("参数", scroll(paramsArea))
            addTab("请求头", scroll(headersArea))
            addTab("请求体", scroll(bodyArea))
            if (!ApiRequestRules.supportsBody(initial.method)) selectedIndex = 0
        }
        val response = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(scroll(responseArea), BorderLayout.CENTER)
        }
        add(
            JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, response).apply {
                resizeWeight = 0.48
                dividerLocation = JBUI.scale(240)
                border = null
            },
            BorderLayout.CENTER
        )

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttons.add(JButton("另存为 .http").apply { addActionListener { saveHttpFile() } })
        buttons.add(JButton("复制响应").apply { addActionListener { copyResponse() } })
        add(buttons, BorderLayout.SOUTH)
    }

    /** 覆盖 OK：发送后保持对话框打开，方便改参数重试；关闭走 Cancel / Esc */
    override fun doOKAction() {
        send()
    }

    private fun initialParams(): String {
        val lines = mutableListOf<String>()
        ApiRequestRules.pathVariables(initial.url).forEach { lines += "$it=" }
        // CurlBuilder 已经把 query 拼进 URL，这里列出已有参数方便改值
        val query = initial.url.substringAfter('?', "").substringBefore('#')
        if (query.isNotEmpty()) {
            query.split('&').forEach { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                if (key.isNotBlank() && key !in ApiRequestRules.pathVariables(initial.url)) {
                    lines += "$key=$value"
                }
            }
        }
        return lines.joinToString("\n").ifBlank { "# 每行一个 key=value；路径里的 {id} 会按同名 key 替换" }
    }

    private fun currentSpec(): ApiRequestRules.Spec {
        val method = methodBox.selectedItem as? String ?: initial.method
        val pairs = ApiRequestRules.parsePairs(paramsArea.text)
        val names = ApiRequestRules.pathVariables(urlField.text)
        val pathValues = pairs.filter { it.first in names }.toMap()
        val query = pairs.filter { it.first !in names }
        var url = ApiRequestRules.applyPathVariables(urlField.text.trim(), pathValues)
        url = ApiRequestRules.appendQuery(url, query)
        val headers = headersArea.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
            .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            .filter { it.first.isNotEmpty() }
            .toList()
        val body = bodyArea.text.takeIf { it.isNotBlank() && ApiRequestRules.supportsBody(method) }
        return ApiRequestRules.Spec(method.uppercase(), url, headers, body)
    }

    private fun send() {
        if (sending) return
        val spec = currentSpec()
        if (ApiRequestRules.pathVariables(spec.url).isNotEmpty()) {
            statusLabel.text = "URL 里仍有未替换的 {占位符}，请在「参数」页填值"
            statusLabel.foreground = warnColor()
            return
        }
        sending = true
        statusLabel.text = "正在请求 ${spec.method} ${spec.url} …"
        statusLabel.foreground = defaultLabelForeground
        ApiRequestExecutor.send(spec) { result ->
            sending = false
            if (project.isDisposed) return@send
            statusLabel.text = if (result.error != null) {
                "请求失败：${result.error}"
            } else {
                "HTTP ${result.status} · ${result.elapsedMs} ms · ${result.body.length} 字符"
            }
            statusLabel.foreground = if (result.ok) okColor() else warnColor()
            responseArea.text = if (result.error != null) {
                result.error.orEmpty()
            } else {
                buildString {
                    append(result.headers)
                    append("\n\n")
                    append(ApiRequestRules.prettyJson(result.body))
                }
            }
            responseArea.caretPosition = 0
        }
    }

    private fun saveHttpFile() {
        val spec = currentSpec()
        val descriptor = FileSaverDescriptor(
            "另存为 .http", "生成 IDEA HTTP Client 可直接打开的请求文件", "http"
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "api-request.http") ?: return
        val file = wrapper.file
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                FileUtil.writeToFile(file, ApiRequestRules.toHttpFile(spec, endpointLabel))
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                statusLabel.text = if (outcome.isSuccess) {
                    "已保存 ${file.name}，可用 IDE 原生 HTTP Client 打开继续编辑"
                } else {
                    "写入失败：${outcome.exceptionOrNull()?.message ?: "未知错误"}"
                }
                statusLabel.foreground = if (outcome.isSuccess) okColor() else warnColor()
            }
        }
    }

    private fun copyResponse() {
        CopyPasteManager.getInstance().setContents(StringSelection(responseArea.text))
        statusLabel.text = "响应已复制到剪贴板"
    }

    private fun scroll(area: JBTextArea): JComponent = JBScrollPane(area).apply { border = null }

    private fun okColor(): JBColor = UiTokens.success

    private fun warnColor(): JBColor = UiTokens.error
}
