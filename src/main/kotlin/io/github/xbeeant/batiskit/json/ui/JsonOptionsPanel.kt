package io.github.xbeeant.batiskit.json.ui

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import io.github.xbeeant.batiskit.json.JsonSettings
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import io.github.xbeeant.batiskit.ui.components.fixedHeight
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * 「JSON → Java 对象」的配置控件集合，生成弹窗与 Settings 页共用同一份。
 *
 * 两类回调分开：只影响渲染的（注解库 / lombok / record / 包名）走 [onRender]，
 * 会改变推断结果的（日期 / 数字策略 / 动态 Map / 复用 bean）走 [onInfer]。
 * 这样在字段表里手改过字段名之后，再切注解库不会把编辑冲掉。
 */
class JsonOptionsPanel(
    private val onRender: () -> Unit,
    private val onInfer: () -> Unit,
    private val showTemplateEditor: Boolean = false
) {

    private val classNameField = JBTextField(16)
    private val packageField = JBTextField(20)
    private val directoryLabel = JBLabel("")

    private val libChecks = linkedMapOf(
        "FASTJSON" to JBCheckBox("Fastjson 1.x"),
        "FASTJSON2" to JBCheckBox("Fastjson 2.x"),
        "GSON" to JBCheckBox("Gson"),
        "JACKSON" to JBCheckBox("Jackson")
    )
    private val nameMode = combo("不同才加", "始终加", "不加")
    private val nameModes = listOf("DIFFERENT", "ALWAYS", "NEVER")

    private val lombokCheck = JBCheckBox("Lombok")
    private val lombokField = JBTextField("DATA,NO_ARGS,ALL_ARGS,BUILDER", 18)
    private val classKind = combo("class", "record")
    private val splitCheck = JBCheckBox("每个类一个文件")

    private val numberKind = combo("自动（Integer/Long/BigDecimal）", "全部 BigDecimal", "浮点用 Double", "全部 String")
    private val numberKinds = listOf("AUTO", "BIG_DECIMAL", "DOUBLE", "STRING")
    private val dateKind = combo("按格式自动识别", "一律 String", "强制 LocalDateTime", "Instant", "java.util.Date")
    private val dateKinds = listOf("AUTO", "STRING", "LOCAL_DATE_TIME", "INSTANT", "JAVA_UTIL_DATE")
    private val visibility = combo("private", "public", "包可见")
    private val visibilities = listOf("PRIVATE", "PUBLIC", "PACKAGE_PRIVATE")
    private val wrapperCheck = JBCheckBox("包装类型")

    private val detectDates = JBCheckBox("识别日期")
    private val detectDynamicMap = JBCheckBox("动态 key → Map")
    private val detectEnum = JBCheckBox("枚举候选提示")
    private val detectUuid = JBCheckBox("识别 UUID")
    private val reuseBeans = JBCheckBox("复用同结构类")
    private val generateComments = JBCheckBox("生成注释")
    private val ignoreUnknown = JBCheckBox("忽略未知字段")
    private val includeNonNull = JBCheckBox("忽略 null 字段")
    private val serializable = JBCheckBox("Serializable")
    private val templateArea = JBTextArea(5, 40).apply {
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
    }

    /** 程序化回填时不要触发回调，否则 reset 会把预览刷一遍又一遍 */
    private var suppress = false

    val component: JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(row(JBLabel("类名"), classNameField, JBLabel("包名"), packageField))
        add(row(JBLabel("目标目录"), directoryLabel))
        add(row(JBLabel("注解库"), *libChecks.values.toTypedArray(), JBLabel("name 注解"), nameMode))
        add(row(lombokCheck, lombokField, JBLabel("形态"), classKind, splitCheck))
        add(row(JBLabel("数字"), numberKind, JBLabel("日期"), dateKind, visibility, wrapperCheck))
        add(row(detectDates, detectDynamicMap, detectEnum, detectUuid, reuseBeans, generateComments))
        add(row(ignoreUnknown, includeNonNull, serializable))
        if (showTemplateEditor) {
            add(row(JBLabel("自定义类模板（留空用内置模板；变量见 resources/json/pojo.tpl）")))
            add(JBScrollPane(templateArea).fixedHeight())
        }
        add(Box.createVerticalGlue())
    }

    /** 设置页没有目标目录上下文，隐藏那一行更干净 */
    fun setDirectory(text: String) {
        directoryLabel.text = text
    }

    fun resetFrom(settings: JsonSettings) {
        suppress = true
        try {
            classNameField.text = settings.className
            packageField.text = settings.packageName
            val selected = settings.annotationLibs.split(',').map { it.trim().uppercase() }.toSet()
            libChecks.forEach { (id, box) -> box.isSelected = id in selected }
            nameMode.selectedIndex = nameModes.indexOf(settings.nameAnnotation.uppercase()).coerceAtLeast(0)
            lombokCheck.isSelected = settings.useLombok
            lombokField.text = settings.lombokAnnotations
            classKind.selectedIndex = if (settings.classKind.equals("RECORD", true)) 1 else 0
            splitCheck.isSelected = settings.splitClasses
            numberKind.selectedIndex = numberKinds.indexOf(settings.numberKind.uppercase()).coerceAtLeast(0)
            dateKind.selectedIndex = dateKinds.indexOf(settings.dateKind.uppercase()).coerceAtLeast(0)
            visibility.selectedIndex = visibilities.indexOf(settings.fieldVisibility.uppercase()).coerceAtLeast(0)
            wrapperCheck.isSelected = settings.useWrapperTypes
            detectDates.isSelected = settings.detectDates
            detectDynamicMap.isSelected = settings.detectDynamicMap
            detectEnum.isSelected = settings.detectEnum
            detectUuid.isSelected = settings.detectUuid
            reuseBeans.isSelected = settings.reuseBeans
            generateComments.isSelected = settings.generateComments
            ignoreUnknown.isSelected = settings.ignoreUnknown
            includeNonNull.isSelected = settings.includeNonNull
            serializable.isSelected = settings.serializable
            if (showTemplateEditor) templateArea.text = settings.classTemplate
        } finally {
            suppress = false
        }
    }

    fun applyTo(settings: JsonSettings) {
        settings.className = classNameField.text.trim()
        settings.packageName = packageField.text.trim()
        settings.annotationLibs = libChecks.filter { (_, box) -> box.isSelected }.keys.joinToString(",")
        settings.nameAnnotation = nameModes.getOrElse(nameMode.selectedIndex) { "DIFFERENT" }
        settings.useLombok = lombokCheck.isSelected
        settings.lombokAnnotations = lombokField.text.trim()
        settings.classKind = if (classKind.selectedIndex == 1) "RECORD" else "CLASS"
        settings.splitClasses = splitCheck.isSelected
        settings.numberKind = numberKinds.getOrElse(numberKind.selectedIndex) { "AUTO" }
        settings.dateKind = dateKinds.getOrElse(dateKind.selectedIndex) { "AUTO" }
        settings.fieldVisibility = visibilities.getOrElse(visibility.selectedIndex) { "PRIVATE" }
        settings.useWrapperTypes = wrapperCheck.isSelected
        settings.detectDates = detectDates.isSelected
        settings.detectDynamicMap = detectDynamicMap.isSelected
        settings.detectEnum = detectEnum.isSelected
        settings.detectUuid = detectUuid.isSelected
        settings.reuseBeans = reuseBeans.isSelected
        settings.generateComments = generateComments.isSelected
        settings.ignoreUnknown = ignoreUnknown.isSelected
        settings.includeNonNull = includeNonNull.isSelected
        settings.serializable = serializable.isSelected
        if (showTemplateEditor) settings.classTemplate = templateArea.text
    }

    /** 固定高度行：Settings 页面板比内容高时，BoxLayout 不会把每一行拉散 */
    private fun row(vararg components: JComponent): JComponent = FixedHeightRow(
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { components.forEach { add(it) } }
    )

    private fun combo(vararg items: String): JComboBox<String> = JComboBox(items)

    private fun JComboBox<String>.onChange(callback: () -> Unit) {
        addActionListener { if (!suppress) callback() }
    }

    private fun JTextComponent.onChange(callback: () -> Unit) {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = fire()
            override fun removeUpdate(e: DocumentEvent) = fire()
            override fun changedUpdate(e: DocumentEvent) = fire()
            private fun fire() {
                if (!suppress) callback()
            }
        })
    }

    private fun JBCheckBox.onChange(callback: () -> Unit) {
        addActionListener { if (!suppress) callback() }
    }

    init {
        // 只影响渲染
        libChecks.values.forEach { it.onChange(onRender) }
        nameMode.onChange(onRender)
        lombokCheck.onChange(onRender)
        lombokField.onChange(onRender)
        classKind.onChange(onRender)
        splitCheck.onChange(onRender)
        visibility.onChange(onRender)
        wrapperCheck.onChange(onRender)
        ignoreUnknown.onChange(onRender)
        includeNonNull.onChange(onRender)
        serializable.onChange(onRender)
        generateComments.onChange(onRender)
        packageField.onChange(onRender)

        // 会改变推断结果
        classNameField.onChange(onInfer)
        numberKind.onChange(onInfer)
        dateKind.onChange(onInfer)
        detectDates.onChange(onInfer)
        detectDynamicMap.onChange(onInfer)
        detectEnum.onChange(onInfer)
        detectUuid.onChange(onInfer)
        reuseBeans.onChange(onInfer)
        if (showTemplateEditor) templateArea.onChange(onRender)
    }
}
